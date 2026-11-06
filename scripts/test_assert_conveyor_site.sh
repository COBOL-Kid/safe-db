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
	local dir="$1" version="$2" quad="$3" base_url="$4" msix_name="$5" signed="${6:-yes}" skiko="${7:-bundled}"
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
	python3 - "$dir/safedb-${version}-mac-aarch64.zip" "$skiko" <<'PY'
import struct
import sys
import zipfile

path, mode = sys.argv[1], sys.argv[2]
arm64 = struct.pack("<II", 0xFEEDFACF, 0x0100000C) + b"\x00" * 24
intel = struct.pack("<II", 0xFEEDFACF, 0x01000007) + b"\x00" * 24
with zipfile.ZipFile(path, "w") as z:
    if mode == "bundled":
        z.writestr(
            "safe-db.app/Contents/runtime/Contents/Home/lib/libskiko-macos-arm64.dylib",
            arm64,
        )
    elif mode == "intel":
        z.writestr(
            "safe-db.app/Contents/runtime/Contents/Home/lib/libskiko-macos-arm64.dylib",
            intel,
        )
    elif mode == "jar-only":
        z.writestr(
            "safe-db.app/Contents/Resources/skiko-awt-runtime-macos-arm64.jar",
            b"jar",
        )
    else:
        sys.stderr.write(f"unknown skiko fixture mode {mode}\n")
        sys.exit(1)
PY
	printf 'enclosure url="%s/safedb-%s-mac-aarch64.zip"\n' "$base_url" "$version" >"$dir/appcast-aarch64.rss"
}

run_assert() {
	local dir="$1"
	shift
	env VERSION="${VERSION:-0.1.7}" "$@" "$ASSERT" "$dir"
}

site="$TEST_ROOT/ok"
write_site "$site" 0.1.7 0.1.7.0 "https://storage.googleapis.com/safedb-download" "safedb-0.1.7.msix"
run_assert "$site" >/dev/null || fail "valid 0.1.7 site was rejected"

arch_site="$TEST_ROOT/ok-x64"
write_site "$arch_site" 0.1.7 0.1.7.0 "https://storage.googleapis.com/safedb-download" "safedb-0.1.7.x64.msix"
run_assert "$arch_site" >/dev/null || fail "valid 0.1.7.x64 site was rejected"

padded="$TEST_ROOT/padded"
write_site "$padded" 1.2 1.2.0.0 "https://storage.googleapis.com/safedb-download" "safedb-1.2.msix"
VERSION=1.2 run_assert "$padded" >/dev/null || fail "valid 1.2 site was rejected"

prefix_version="$TEST_ROOT/prefix-version"
write_site "$prefix_version" 0.1 0.1.0.0 "https://storage.googleapis.com/safedb-download" "safedb-0.1.7.x64.msix"
if VERSION=0.1 run_assert "$prefix_version" >/dev/null 2>&1; then
	fail "accepted MSIX 0.1.7.x64 as desktop version 0.1"
fi

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

jar_only="$TEST_ROOT/jar-only-skiko"
write_site "$jar_only" 0.1.7 0.1.7.0 "https://storage.googleapis.com/safedb-download" "safedb-0.1.7.msix" yes jar-only
if run_assert "$jar_only" >/dev/null 2>&1; then
	fail "accepted a Mac zip whose Skiko dylib exists only inside the runtime JAR"
fi

intel_skiko="$TEST_ROOT/intel-skiko"
write_site "$intel_skiko" 0.1.7 0.1.7.0 "https://storage.googleapis.com/safedb-download" "safedb-0.1.7.msix" yes intel
if run_assert "$intel_skiko" >/dev/null 2>&1; then
	fail "accepted an Intel Mach-O at the arm64 Skiko path"
fi

missing_mac="$TEST_ROOT/missing-mac"
write_site "$missing_mac" 0.1.7 0.1.7.0 "https://storage.googleapis.com/safedb-download" "safedb-0.1.7.msix"
rm -f "$missing_mac/safedb-0.1.7-mac-aarch64.zip"
if run_assert "$missing_mac" >/dev/null 2>&1; then
	fail "accepted a site missing the Apple Silicon zip"
fi

# Policy lock for scripts/publish-release-prune-policy.txt (keep/glob table).
# Both publish-release.sh and publish-release.ps1 copy that table; this fixture
# is the behavioral lock. Update expected-pruned-objects when the policy changes.
# Do not merge .sh/.ps1; publish-release.ps1 must match the same table.
PUBLISH="$ROOT/scripts/publish-release.sh"
PRUNE_POLICY="$ROOT/scripts/publish-release-prune-policy.txt"
[[ -f "$PRUNE_POLICY" ]] || fail "missing prune policy $PRUNE_POLICY"
grep -q 'publish-release-prune-policy.txt' "$PUBLISH" ||
	fail "publish-release.sh does not read the prune policy table"
grep -q 'publish-release-prune-policy.txt' "$ROOT/scripts/publish-release.ps1" ||
	fail "publish-release.ps1 does not read the prune policy table"
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

no_mac_zip="$TEST_ROOT/publish-no-mac"
write_site "$no_mac_zip" 0.1.7 0.1.7.0 "https://storage.googleapis.com/safedb-download" "safedb-0.1.7.msix"
rm -f "$no_mac_zip"/safedb-*-mac-*.zip
reject_publish "$no_mac_zip" "accepted a site with no Mac zip"

fake_bin="$TEST_ROOT/fake-bin"
mkdir -p "$fake_bin"
cat >"$fake_bin/gcloud" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

if [[ "${1:-}" == "--quiet" ]]; then
	shift
fi

case "${1:-} ${2:-}" in
"storage objects")
	case "${3:-}" in
	list) cat "$FAKE_OBJECTS" ;;
	describe) printf '{"cacheControl":"no-cache, max-age=0"}\n' ;;
	*)
		echo "unexpected fake gcloud objects argument: ${3:-}" >&2
		exit 1
		;;
	esac
	;;
"storage rm")
	shift 2
	printf '%s\n' "$@" >>"$FAKE_RM_LOG"
	;;
"storage cp") ;;
*)
	echo "unexpected fake gcloud invocation: $*" >&2
	exit 1
	;;
esac
EOF
chmod +x "$fake_bin/gcloud"

cleanup_site="$TEST_ROOT/publish-cleanup"
write_site "$cleanup_site" 0.1.7 0.1.7.0 "https://storage.googleapis.com/safedb-download" "safedb-0.1.7.x64.msix"
objects="$TEST_ROOT/bucket-objects.txt"
cat >"$objects" <<'EOF'
safedb-0.1.7.x64.msix
safedb-0.1.7-mac-aarch64.zip
safedb-0.1.7-windows-amd64.zip
safedb-0.1.6-0.1.7-mac-aarch64.delta
safedb-0.1.5-0.1.7-mac-aarch64.delta
safedb-0.1.4-0.1.7-mac-aarch64.delta
safedb-0.1.3-0.1.7-mac-aarch64.delta
safedb-0.1.2-0.1.7-mac-aarch64.delta
safedb-0.1.1-0.1.7-mac-aarch64.delta
safedb-0.1.0-0.1.7-mac-aarch64.delta
safedb-0.1.6.x64.msix
safedb-0.1.6-mac-aarch64.zip
safedb-0.1.6-windows-amd64.zip
safedb-0.1.5.x64.msix
safedb-0.1.5-mac-aarch64.zip
safedb-0.1.5-windows-amd64.zip
metadata.properties
safedb.appinstaller
safedb-windows-x64.exe
EOF
rm_log="$TEST_ROOT/pruned-objects.txt"
expected="$TEST_ROOT/expected-pruned-objects.txt"
cat >"$expected" <<'EOF'
gs://safedb-download/safedb-0.1.5-mac-aarch64.zip
gs://safedb-download/safedb-0.1.1-0.1.7-mac-aarch64.delta
gs://safedb-download/safedb-0.1.0-0.1.7-mac-aarch64.delta
gs://safedb-download/safedb-0.1.6-windows-amd64.zip
gs://safedb-download/safedb-0.1.5-windows-amd64.zip
gs://safedb-download/safedb-0.1.6.x64.msix
gs://safedb-download/safedb-0.1.5.x64.msix
EOF
if ! PATH="$fake_bin:$PATH" \
	FAKE_OBJECTS="$objects" \
	FAKE_RM_LOG="$rm_log" \
	"$PUBLISH" "$cleanup_site" >/dev/null; then
	fail "publish-release.sh failed while pruning old objects"
fi
if ! diff -u "$expected" "$rm_log"; then
	fail "publish-release.sh pruned the wrong objects"
fi
