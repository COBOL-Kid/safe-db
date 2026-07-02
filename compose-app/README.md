# safe-db (Kotlin + Compose Desktop)

Jetpack Compose Desktop migration of safe-db. Replaces the Tauri/Svelte/Rust stack with a single JVM process using JDBC for PostgreSQL, MySQL, SQL Server, and Oracle.

## Prerequisites

- **JDK 25**. The Gradle projects use `jvmToolchain(25)`, matching the repo agent notes and the current development VM.
- Network access to your databases (same as the Tauri app)

## Run

```bash
cd compose-app
./gradlew run
```

## Test

```bash
./gradlew check          # shared unit tests (query engine, stores, adapters, parser)
./gradlew :shared:test   # shared module only
```

## Build distributable

```bash
./gradlew packageDistributionForCurrentOS
```

Packages are written under `compose-app/build/compose/binaries/`.

## Data directory

The app uses the same on-disk layout as Tauri (`com.safedb.app`):

| OS      | Path |
|---------|------|
| Linux   | `~/.local/share/com.safedb.app/` |
| macOS   | `~/Library/Application Support/com.safedb.app/` |
| Windows | `%APPDATA%\com.safedb.app\` |

On first launch, if Tauri data files (`connections.json`, etc.) are found, they are reused automatically.

## Credentials

Set `SAFEDB_KEYCHAIN_BACKEND=disabled` for in-memory credentials (debug/CI). Default `auto` uses Java keyring-backed platform stores (macOS Keychain, Windows Credential Manager, Linux keyutils when available). On Linux hosts where the Java keyring delegate is unavailable, the Compose app falls back to the same in-memory `disabled` backend rather than writing a credential file; saved connection profiles remain on disk, but passwords must be re-entered after restart.

If stored credentials cannot be read after moving data from Tauri or changing credential backends, delete and add the connection again so the password is rebound to the current endpoint and transport settings.

## Module layout

- **`shared/`** — models, query engine, JDBC adapters, persistence, secrets, `SafeDbService`
- **root project** — Compose UI (shell, connection form, query builder canvas)
