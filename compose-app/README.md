# safe-db (Kotlin + Compose Desktop)

Jetpack Compose Desktop migration of safe-db. Replaces the Tauri/Svelte/Rust stack with a single JVM process using JDBC for PostgreSQL, MySQL, SQL Server, and Oracle.

## Prerequisites

- **JDK 21+**
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

Set `SAFEDB_KEYCHAIN_BACKEND=disabled` for in-memory credentials (debug/CI). Default `auto` uses platform stores (Keychain, Credential Manager, Secret Service) with a file fallback on headless Linux.

## Module layout

- **`shared/`** — models, query engine, JDBC adapters, persistence, secrets, `SafeDbService`
- **root project** — Compose UI (shell, connection form, query builder canvas)
