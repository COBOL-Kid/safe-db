# Windows packaging

GitHub Actions is the release path. [`.github/workflows/conveyor.yml`](../.github/workflows/conveyor.yml) builds a signed Windows download site on Linux and, when asked, publishes it to `gs://safedb-download`. Local `make site` and unsigned `--key` overrides remain a fallback.

`conveyor.conf` pulls the app definition out of Gradle via `include "#!/./gradlew -q printConveyorConfig"`. That include only prints configuration; it does not assemble anything, so `./gradlew jar` must run first. The Windows installer file is `safedb-windows-x64.exe`. The app identity (`app.fsname`) is `safedb`, so a later macOS build can share the same name, data directories, and update site.

Durability CI still produces unsigned jpackage MSI/DMG artifacts. Those jobs are not this release path.

## GitHub Actions release

On GitHub, **Run workflow** on `.github/workflows/conveyor.yml`. Leave `publish` false to package and keep a 14-day artifact after `check`, MySQL integration, and Authenticode smoke. Set `publish` true only from `main` to upload `output/` to `gs://safedb-download`.

The `test` and `integration` jobs run `./gradlew check` and required static-MySQL `integrationTest` (same bar as labeled PR CI) before any signing. The `package` job then runs on `ubuntu-latest`. It assembles the jar, logs into Azure with OIDC, mints `AZURE_CODESIGNING_TOKEN`, installs Conveyor 22.2, runs `make site` with the existing root key, and asserts `output/` contains `safedb-windows-x64.exe`, `safedb.appinstaller`, `metadata.properties`, and a nonempty `.msix` whose version and `AppInstaller`/`MainPackage` URLs match `build.gradle.kts` and `https://storage.googleapis.com/safedb-download`. Conveyor is installed in the workflow rather than via `hydraulic-software/conveyor/actions/build`, whose nested unpinned actions org policy rejects. A Windows `smoke` job checks Authenticode on the stub installer and the `.msix`. The `publish` job downloads that artifact, refuses incomplete `output/` directories, runs `./scripts/publish-release.sh ./output`, and checks that `metadata.properties` and `safedb.appinstaller` were stored with `Cache-Control` containing `no-cache`.

Bump `version` in `build.gradle.kts` for every release so the MSIX filename changes. Windows misbehaves when an MSIX filename is reused with different content, and Conveyor refuses to overwrite an existing MSIX of the same version. The `shared` module carries its own independent version and does not need to move with the desktop version.

### Out-of-band setup

These live in GitHub and cloud consoles, not in this repository. Use the **existing** Conveyor root key; a new key would break auto-update for installed copies.

- GitHub Environment `conveyor` (unprotected until reviewers are added, same pattern as `npm`). Required reviewers for Environment `conveyor` are configured in the GitHub UI; add them before treating `publish: true` as a production release.
- Secret `CONVEYOR_SIGNING_KEY`: existing root key from the local Hydraulic config.
- Azure OIDC secrets `AZURE_CLIENT_ID`, `AZURE_TENANT_ID`, `AZURE_SUBSCRIPTION_ID`. The federated credential subject must be `repo:COBOL-Kid/safe-db:environment:conveyor`. The identity needs **Code Signing Certificate Profile Signer** for alias `safe-db/safe-db`.
- GCP Workload Identity Federation secrets `GCP_WORKLOAD_IDENTITY_PROVIDER` and `GCP_SERVICE_ACCOUNT`, with write access to `gs://safedb-download` (not the default Firebase bucket).

Do not commit signing keys, Azure tokens, or GCP credentials.

## Local fallback

Conveyor 22.2 or newer. Earlier releases cannot build this project: their bundled JDK table stops at 24.0.2, while `build.gradle.kts` sets `jvmToolchain(25)`. Check with `conveyor jdk-table`.

Do not install from winget. The `Hydraulic.Conveyor` package is pinned to 20.0, and its MSIX fails to install with `0x800b010c` because the signing certificate was revoked by its issuer. Install from the zip instead:

```text
https://downloads.hydraulic.dev/conveyor/conveyor-22.2-windows-amd64.zip
```

Extracting it to `%LOCALAPPDATA%\Hydraulic` puts the executable at `%LOCALAPPDATA%\Hydraulic\bin\conveyor.exe`; it is not added to `PATH`.

Windows must also be in Developer Mode, or the account needs `SeCreateSymbolicLinkPrivilege`, because Conveyor creates symlinks while assembling the package. Linux CI does not have that requirement; that is why releases are built there.

The same existing root key used in CI signs update metadata. Unsigned local builds still need that key so Conveyor can derive a self-signed certificate. Non-interactive runs need `--passphrase "env:VARNAME"`.

Uploading a local release needs the Google Cloud CLI (`gcloud`), signed into an account that can write `gs://safedb-download`. Azure CLI (`az login`) is only for Trusted Signing.

### Build

```powershell
./gradlew jar
& "$env:LOCALAPPDATA\Hydraulic\bin\conveyor.exe" --console=plain --passphrase "env:CONVEYOR_PASS" make site
```

`make site` produces the download site in `output/`, including the stub installer, MSIX, `download.html`, and update metadata. `make windows-app` produces the unpacked application tree only. `output/` is gitignored. After a local `make site`, `./scripts/assert-conveyor-site.sh ./output` checks the same version, URL, and signature-presence rules the workflow uses.

`api-access-token` is optional. When `AZURE_CODESIGNING_TOKEN` is unset, Conveyor runs `az account get-access-token --resource https://codesigning.azure.net` itself, so an `az login` session is enough. There is no client-id or client-secret option. Tokens last about an hour; mint one per CI run rather than storing it.

### Unsigned local builds

`app.sign=false` alone is not enough. The App User Model Id task still reads the certificate subject to derive the MSIX publisher identity, so it contacts Azure Trusted Signing and fails with `HTTP Error 403 - Forbidden` when credentials are absent. Override the key as well:

```powershell
& "$env:LOCALAPPDATA\Hydraulic\bin\conveyor.exe" --console=plain --passphrase "env:CONVEYOR_PASS" `
  --key "app.sign=false" --key "app.windows.signing-key=derived" --key "app.windows.signing-key-alias=null" `
  make windows-app
```

The resolved certificate becomes `self signed by CN=safe-db`. PowerShell mangles the short form `-Kapp.sign=false`; use `--key "app.sign=false"`.

An absent token never means an unsigned build: the `azure-trusted-signing` block stays selected and Conveyor falls through to the CLI session. If there is no session either, the signing call is still attempted and fails with a 403.

## Publishing to Cloud Storage

`app.site.base-url` is `https://storage.googleapis.com/safedb-download`. Installed copies poll that directory for `metadata.properties` and the `.appinstaller` file. `app.updates = background`, so Windows checks about every eight hours and upgrades silently.

Do not use `make copied-site` against this bucket. Conveyor's S3 uploader cannot stamp the per-extension `Content-Type` and `Cache-Control` that Windows App Installer requires. The workflow (and the local fallback) uses `make site`, then:

```powershell
.\scripts\publish-release.ps1 .\output
```

On macOS or Linux, `./scripts/publish-release.sh ./output` does the same thing. Both default to `gs://safedb-download`.

The layout must stay flat: `base-url` resolves each file directly under the bucket root. The `.appinstaller`, `metadata.properties`, and `safedb-windows-x64.exe` objects are uploaded `Cache-Control: no-cache`. Versioned `.msix` files are immutable. The publish scripts check `Cache-Control` on `metadata.properties` and `safedb.appinstaller` after upload. You can also confirm:

```powershell
curl.exe -sI https://storage.googleapis.com/safedb-download/metadata.properties
curl.exe -sI https://storage.googleapis.com/safedb-download/safedb.appinstaller
```

`Cache-Control` should contain `no-cache`. The default Firebase bucket (`safe-db-35169.firebasestorage.app`) is unrelated and must stay deny-all; never grant `allUsers` objectViewer on it.

`base-url` is baked into the signed MSIX. Changing it later breaks auto-update for already-installed copies unless you run a Conveyor site move.
