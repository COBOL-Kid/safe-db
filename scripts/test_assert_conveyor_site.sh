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
