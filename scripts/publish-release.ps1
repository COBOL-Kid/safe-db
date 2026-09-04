# Upload a Hydraulic Conveyor output directory to the public download bucket.
#
#   .\scripts\publish-release.ps1 .\output
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
#     Public read is an IAM grant (allUsers objectViewer). Firebase Security Rules do NOT
#     govern storage.googleapis.com access. Never grant public read on the default
#     safe-db-35169.firebasestorage.app bucket.

[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [string]$OutputDir
)

$ErrorActionPreference = "Stop"

$Bucket = if ($env:BUCKET) { $env:BUCKET } else { "gs://safedb-download" }

if (-not $OutputDir -or -not (Test-Path -LiteralPath $OutputDir -PathType Container)) {
    Write-Error "usage: $($MyInvocation.MyCommand.Name) <conveyor-output-dir>"
    exit 1
}

$OutputDir = (Resolve-Path -LiteralPath $OutputDir).Path
$Immutable = "public, max-age=31536000, immutable"
$NoCache = "no-cache, max-age=0"

function Assert-RequiredReleaseFile {
    param(
        [string]$Name
    )
    $path = Join-Path $OutputDir $Name
    if (-not (Test-Path -LiteralPath $path -PathType Leaf) -or (Get-Item -LiteralPath $path).Length -le 0) {
        throw "publish aborted: expected nonempty $path"
    }
}

Assert-RequiredReleaseFile "metadata.properties"
Assert-RequiredReleaseFile "safedb.appinstaller"
Assert-RequiredReleaseFile "safedb-windows-x64.exe"

$msixFiles = @(Get-ChildItem -LiteralPath $OutputDir -File -Filter "*.msix" -ErrorAction SilentlyContinue)
if ($msixFiles.Count -eq 0) {
    throw "publish aborted: expected at least one nonempty .msix in $OutputDir"
}
foreach ($msix in $msixFiles) {
    if ($msix.Length -le 0) {
        throw "publish aborted: expected nonempty $($msix.FullName)"
    }
}

function Get-ReleaseFiles {
    param(
        [string[]]$Patterns
    )
    $files = @()
    foreach ($pattern in $Patterns) {
        $files += @(Get-ChildItem -LiteralPath $OutputDir -File -Filter $pattern -ErrorAction SilentlyContinue)
    }
    return $files
}

function Upload-ReleaseFiles {
    param(
        [string]$ContentType,
        [string]$CacheControl,
        [string[]]$Patterns
    )

    $files = Get-ReleaseFiles -Patterns $Patterns
    if ($files.Count -eq 0) {
        return
    }

    Write-Host "==> $($files.Count) file(s) as $ContentType"
    $paths = @($files | ForEach-Object { $_.FullName })
    & gcloud storage cp `
        --content-type="$ContentType" `
        --cache-control="$CacheControl" `
        @paths `
        "$Bucket/"
    if ($LASTEXITCODE -ne 0) {
        throw "gcloud storage cp failed with exit code $LASTEXITCODE"
    }
}

# Version-stamped payloads never change under a given name, so cache them forever.
Upload-ReleaseFiles application/msix $Immutable @("*.msix")
Upload-ReleaseFiles application/msixbundle $Immutable @("*.msixbundle")
Upload-ReleaseFiles application/appx $Immutable @("*.appx")
Upload-ReleaseFiles application/octet-stream $Immutable @("*.zip", "*.dmg", "*.pkg", "*.deb", "*.tar.gz")

# The Windows stub installer keeps a stable filename (safedb-windows-x64.exe).
# Sites that link to it would keep serving a stale installer if it were immutable.
Upload-ReleaseFiles application/octet-stream $NoCache @("*.exe")

# Update metadata is polled by installed copies. A stale cached copy means missed updates.
Upload-ReleaseFiles application/appinstaller $NoCache @("*.appinstaller")
Upload-ReleaseFiles application/rss+xml $NoCache @("*.rss")
Upload-ReleaseFiles text/plain $NoCache @("metadata.properties")

# The generated download page and its assets.
Upload-ReleaseFiles text/html $NoCache @("*.html")
Upload-ReleaseFiles image/svg+xml $Immutable @("*.svg")
Upload-ReleaseFiles image/png $Immutable @("*.png")

function Assert-NoCacheControl {
    param(
        [string]$Object
    )

    $jsonText = & gcloud --quiet storage objects describe "$Object" --format=json | Out-String
    if ($LASTEXITCODE -ne 0) {
        throw "gcloud storage objects describe failed with exit code $LASTEXITCODE"
    }
    $json = $jsonText | ConvertFrom-Json
    $cacheControl = $json.cacheControl
    if (-not $cacheControl) {
        $cacheControl = $json.cache_control
    }
    if ($cacheControl -notmatch "no-cache") {
        throw "expected $Object Cache-Control to contain no-cache; got: $cacheControl"
    }
}

Assert-NoCacheControl "$Bucket/metadata.properties"
Assert-NoCacheControl "$Bucket/safedb.appinstaller"

$HostName = $Bucket -replace "^gs://", ""
Write-Host ""
Write-Host "Published to https://storage.googleapis.com/$HostName/"
