#!/usr/bin/env bash
#
# Upload a Hydraulic Conveyor output directory to the public download bucket.
#
#   ./scripts/publish-release.sh ./output
#
# Cloud Storage has no server-side headers config: Content-Type and Cache-Control are
# per-object metadata stamped at upload time. Windows App Installer refuses packages served
# with the wrong Content-Type, so each extension group is uploaded in its own pass with the
# right flags. That is also why this is not a single `gcloud storage rsync` call, which
# cannot vary metadata per file.
#
# Prerequisites (one time):
#   - the safe-db-35169 project on the Blaze plan
#   - a dedicated public bucket, kept separate from the default Firebase bucket:
#       gs://safedb-download
#     Firebase Security Rules do NOT govern storage.googleapis.com access, so public read is
#     this IAM grant, and with uniform access it applies to the whole bucket. Never grant it
#     on the default safe-db-35169.firebasestorage.app bucket.

set -euo pipefail

BUCKET="${BUCKET:-gs://safedb-download}"
OUTPUT_DIR="${1:-}"

if [[ -z "$OUTPUT_DIR" || ! -d "$OUTPUT_DIR" ]]; then
	echo "usage: $0 <conveyor-output-dir>" >&2
	exit 1
fi

IMMUTABLE="public, max-age=31536000, immutable"
NO_CACHE="no-cache, max-age=0"

# upload <content-type> <cache-control> <glob>...
upload() {
	local content_type="$1" cache_control="$2"
	shift 2

	local files=()
	local pattern
	for pattern in "$@"; do
		while IFS= read -r -d '' file; do
			files+=("$file")
		done < <(find "$OUTPUT_DIR" -maxdepth 1 -type f -name "$pattern" -print0)
	done

	if [[ ${#files[@]} -eq 0 ]]; then
		return 0
	fi

	echo "==> ${#files[@]} file(s) as $content_type"
	gcloud storage cp \
		--content-type="$content_type" \
		--cache-control="$cache_control" \
		"${files[@]}" "$BUCKET/"
}

# Version-stamped payloads never change under a given name, so cache them forever.
upload application/msix       "$IMMUTABLE" '*.msix'
upload application/msixbundle "$IMMUTABLE" '*.msixbundle'
upload application/appx       "$IMMUTABLE" '*.appx'
upload application/octet-stream "$IMMUTABLE" '*.zip' '*.dmg' '*.pkg' '*.deb' '*.tar.gz'

# The Windows stub installer keeps a stable filename (safedb-windows-x64.exe).
# Sites that link to it would keep serving a stale installer if it were immutable.
upload application/octet-stream "$NO_CACHE" '*.exe'

# Update metadata is polled by installed copies. A stale cached copy means missed updates.
upload application/appinstaller "$NO_CACHE" '*.appinstaller'
upload application/rss+xml      "$NO_CACHE" '*.rss'
upload text/plain               "$NO_CACHE" 'metadata.properties'

# The generated download page and its assets.
upload text/html       "$NO_CACHE"   '*.html'
upload image/svg+xml   "$IMMUTABLE"  '*.svg'
upload image/png       "$IMMUTABLE"  '*.png'

echo
echo "Published to https://storage.googleapis.com/${BUCKET#gs://}/"
