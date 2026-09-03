#!/usr/bin/env bash
#
# Check a Hydraulic Conveyor site directory before publish.
#
#   ./scripts/assert-conveyor-site.sh ./output
#
# VERSION defaults to the root Gradle desktop version. Override in tests.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUTPUT_DIR="${1:-}"
EXPECTED_SITE_BASE_URL="https://storage.googleapis.com/safedb-download"

if [[ -z "$OUTPUT_DIR" || ! -d "$OUTPUT_DIR" ]]; then
	echo "usage: $0 <conveyor-output-dir>" >&2
	exit 1
fi

fail() {
	echo "conveyor site assertion failed: $*" >&2
	exit 1
}

read_desktop_version() {
	awk -F'"' '/^version = "/ { print $2; exit }' "$ROOT/build.gradle.kts"
}

version_quad() {
	local v="$1"
	local -a parts
	IFS='.' read -ra parts <<<"$v"
	if ((${#parts[@]} < 1 || ${#parts[@]} > 4)); then
		fail "desktop version '$v' is not 1-4 numeric components"
	fi
	local part
	for part in "${parts[@]}"; do
		[[ "$part" =~ ^[0-9]+$ ]] || fail "desktop version '$v' is not dotted numbers"
	done
	while ((${#parts[@]} < 4)); do
		parts+=("0")
	done
	echo "${parts[0]}.${parts[1]}.${parts[2]}.${parts[3]}"
}

property_value() {
	local key="$1" file="$2"
	local value
	value="$(
		awk -F= -v key="$key" '
			{ sub(/\r$/, "") }
			$1 == key { print substr($0, index($0, "=") + 1) }
		' "$file" | tail -n 1
	)"
	[[ -n "$value" ]] || fail "$file is missing $key"
	printf '%s\n' "$value"
}

VERSION="${VERSION:-$(read_desktop_version)}"
[[ -n "$VERSION" ]] || fail "could not read desktop version from build.gradle.kts"
QUAD="$(version_quad "$VERSION")"

exe="$OUTPUT_DIR/safedb-windows-x64.exe"
appinstaller="$OUTPUT_DIR/safedb.appinstaller"
metadata="$OUTPUT_DIR/metadata.properties"

[[ -s "$exe" ]] || fail "expected nonempty $exe"
[[ -s "$appinstaller" ]] || fail "expected nonempty $appinstaller"
[[ -s "$metadata" ]] || fail "expected nonempty $metadata"

shopt -s nullglob
msix_files=("$OUTPUT_DIR"/*.msix)
shopt -u nullglob
if ((${#msix_files[@]} != 1)); then
	echo "expected exactly one nonempty .msix in $OUTPUT_DIR:" >&2
	ls -la "$OUTPUT_DIR" || true
	exit 1
fi
msix="${msix_files[0]}"
[[ -s "$msix" ]] || fail "expected nonempty $msix"

msix_base="$(basename "$msix")"
if [[ "$msix_base" != *"$VERSION".msix && "$msix_base" != *"$VERSION"-*.msix ]]; then
	fail "MSIX filename '$msix_base' does not include desktop version $VERSION"
fi

[[ "$(property_value app.version "$metadata")" == "$VERSION" ]] ||
	fail "metadata.properties app.version is not $VERSION"
[[ "$(property_value app.windows.manifests.version-quad "$metadata")" == "$QUAD" ]] ||
	fail "metadata.properties version-quad is not $QUAD"

url_hits="$(grep -oF "$EXPECTED_SITE_BASE_URL" "$appinstaller" | wc -l | tr -d ' ')"
if ((url_hits < 2)); then
	fail "safedb.appinstaller must reference $EXPECTED_SITE_BASE_URL at least twice (AppInstaller Uri and MainPackage Uri)"
fi
grep -qF "$msix_base" "$appinstaller" ||
	fail "safedb.appinstaller does not reference $msix_base"

if ! python3 - "$msix" "$QUAD" <<'PY'
import sys
import zipfile

path, quad = sys.argv[1], sys.argv[2]
try:
    with zipfile.ZipFile(path) as z:
        names = z.namelist()
        if not any(name.endswith("AppxSignature.p7x") for name in names):
            sys.stderr.write(f"{path} is not a signed MSIX (missing AppxSignature.p7x)\n")
            sys.exit(1)
        try:
            manifest = z.read("AppxManifest.xml").decode("utf-8")
        except KeyError:
            sys.stderr.write(f"{path} is missing AppxManifest.xml\n")
            sys.exit(1)
except zipfile.BadZipFile:
    sys.stderr.write(f"{path} is not a zip/MSIX\n")
    sys.exit(1)
if f'Version="{quad}"' not in manifest:
    sys.stderr.write(f"AppxManifest.xml Version is not {quad}\n")
    sys.exit(1)
PY
then
	fail "signed MSIX/AppxManifest checks failed for $msix_base"
fi

ls -l "$OUTPUT_DIR"
