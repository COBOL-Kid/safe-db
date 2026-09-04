#!/usr/bin/env bash
# Fixture tests for assert-conveyor-site.sh.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/safedb-conveyor-site-test.XXXXXX")"
trap 'rm -rf "$TEST_ROOT"' EXIT

ASSERT="$ROOT/scripts/assert-conveyor-site.sh"
fail() {
	echo "assert-conveyor-site regression failed: $*" >&2
	exit 1
}

write_site() {
	local dir="$1" version="$2" quad="$3" base_url="$4" msix_name="$5" signed="${6:-yes}"
	mkdir -p "$dir"
	printf 'stub\n' >"$dir/safedb-windows-x64.exe"
	cat >"$dir/metadata.properties" <<EOF
app.version=$version
app.windows.manifests.version-quad=$quad
app.revision=0
EOF
	cat >"$dir/safedb.appinstaller" <<EOF
<?xml version="1.0" encoding="utf-8"?>
<AppInstaller Uri="${base_url}/safedb.appinstaller" Version="$quad">
  <MainPackage Uri="${base_url}/${msix_name}" Version="$quad" />
</AppInstaller>
EOF
	python3 - "$dir/$msix_name" "$quad" "$signed" <<'PY'
import sys
import zipfile

path, quad, signed = sys.argv[1], sys.argv[2], sys.argv[3]
with zipfile.ZipFile(path, "w") as z:
    z.writestr("AppxManifest.xml", f'<Identity Name="safedb" Version="{quad}" />\n')
    if signed == "yes":
        z.writestr("AppxSignature.p7x", "sig")
PY
}

run_assert() {
	local dir="$1"
	shift
	env VERSION="${VERSION:-0.1.7}" "$@" "$ASSERT" "$dir"
}

site="$TEST_ROOT/ok"
write_site "$site" 0.1.7 0.1.7.0 "https://storage.googleapis.com/safedb-download" "safedb-0.1.7.msix"
run_assert "$site" >/dev/null || fail "valid 0.1.7 site was rejected"

padded="$TEST_ROOT/padded"
write_site "$padded" 1.2 1.2.0.0 "https://storage.googleapis.com/safedb-download" "safedb-1.2.msix"
VERSION=1.2 run_assert "$padded" >/dev/null || fail "valid 1.2 site was rejected"

wrong_version="$TEST_ROOT/wrong-version"
write_site "$wrong_version" 0.1.6 0.1.6.0 "https://storage.googleapis.com/safedb-download" "safedb-0.1.6.msix"
if run_assert "$wrong_version" >/dev/null 2>&1; then
	fail "accepted metadata version 0.1.6 against desktop 0.1.7"
fi

wrong_url="$TEST_ROOT/wrong-url"
write_site "$wrong_url" 0.1.7 0.1.7.0 "https://example.invalid/downloads" "safedb-0.1.7.msix"
if run_assert "$wrong_url" >/dev/null 2>&1; then
	fail "accepted appinstaller with the wrong site base URL"
fi

unsigned="$TEST_ROOT/unsigned"
write_site "$unsigned" 0.1.7 0.1.7.0 "https://storage.googleapis.com/safedb-download" "safedb-0.1.7.msix" no
if run_assert "$unsigned" >/dev/null 2>&1; then
	fail "accepted an MSIX without AppxSignature.p7x"
fi

namespaced="$TEST_ROOT/namespaced"
write_site "$namespaced" 0.1.7 0.1.7.0 "https://storage.googleapis.com/safedb-download" "safedb-0.1.7.msix"
cat >"$namespaced/safedb.appinstaller" <<'EOF'
<?xml version="1.0" encoding="utf-8"?>
<AppInstaller xmlns="http://schemas.microsoft.com/appx/appinstaller/2018"
    Uri="https://storage.googleapis.com/safedb-download/safedb.appinstaller" Version="0.1.7.0">
  <MainPackage Uri="https://storage.googleapis.com/safedb-download/safedb-0.1.7.msix" Version="0.1.7.0" />
</AppInstaller>
EOF
run_assert "$namespaced" >/dev/null || fail "valid namespaced appinstaller was rejected"

spoofed_urls="$TEST_ROOT/spoofed-urls"
write_site "$spoofed_urls" 0.1.7 0.1.7.0 "https://storage.googleapis.com/safedb-download" "safedb-0.1.7.msix"
cat >"$spoofed_urls/safedb.appinstaller" <<'EOF'
<?xml version="1.0" encoding="utf-8"?>
<AppInstaller Uri="https://example.invalid/safedb.appinstaller" Version="0.1.7.0">
  <!-- https://storage.googleapis.com/safedb-download/safedb.appinstaller -->
  <Note>https://storage.googleapis.com/safedb-download/safedb-0.1.7.msix</Note>
  <MainPackage Uri="https://example.invalid/safedb-0.1.7.msix" Version="0.1.7.0" />
</AppInstaller>
EOF
if run_assert "$spoofed_urls" >/dev/null 2>&1; then
	fail "accepted appinstaller whose Uri attributes are not the site URLs"
fi

PUBLISH="$ROOT/scripts/publish-release.sh"
reject_publish() {
	local dir="$1" reason="$2" out
	if out="$("$PUBLISH" "$dir" 2>&1)"; then
		fail "publish-release.sh $reason"
	fi
	[[ "$out" == *"publish aborted:"* ]] || fail "publish-release.sh $reason (unexpected error: $out)"
}

empty_publish="$TEST_ROOT/publish-empty"
mkdir -p "$empty_publish"
reject_publish "$empty_publish" "accepted an empty directory"

no_msix="$TEST_ROOT/publish-no-msix"
write_site "$no_msix" 0.1.7 0.1.7.0 "https://storage.googleapis.com/safedb-download" "safedb-0.1.7.msix"
rm -f "$no_msix"/*.msix
reject_publish "$no_msix" "accepted a site with no MSIX"

empty_msix="$TEST_ROOT/publish-empty-msix"
write_site "$empty_msix" 0.1.7 0.1.7.0 "https://storage.googleapis.com/safedb-download" "safedb-0.1.7.msix"
: >"$empty_msix/safedb-0.1.7.msix"
reject_publish "$empty_msix" "accepted a site with an empty MSIX"
