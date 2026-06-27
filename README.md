# safe-db

Desktop app for safely exploring production databases. Connect to PostgreSQL, MySQL, SQL Server, or Oracle, browse schema visually, build read-only queries in a canvas UI, and run them with guardrails: non-locking reads, enforced row limits, blocked system schemas, and EXPLAIN cost warnings.

Built with **Tauri 2** (Rust) and **SvelteKit 2** (Svelte 5).

## Features

- **Connections** — save named profiles; passwords stored in the OS credential store (not on disk); show/hide toggle on the password field
- **Schema browser** — tables, columns, and indexes with system/catalog schemas filtered out
- **Visual query builder** — drag tables onto a canvas, join, filter, select columns, set a row limit; **recursive filter groups** with per-child AND/OR connector overrides
- **Safety rails** — read-only `SELECT` queries, max 1 000 rows (default 100), 10 s timeout, custom blocked schemas, optional EXPLAIN cost threshold (warning surfaced in the UI)
- **Saved queries & history** — separate stores; reopen past work from the home screen or history page
- **In-app confirmations** — destructive actions (delete connection, clear history) use a Tauri-native dialog instead of `window.confirm()` (which hides behind the WebView on macOS)
- **Command palette** — `Cmd+K` / `Ctrl+K` for quick navigation
- **Light / dark theme** — Tauri window background color is synced to the active theme

## Supported databases

| Database     | Driver    | Notes                                      |
| ------------ | --------- | ------------------------------------------ |
| PostgreSQL   | sqlx      |                                            |
| MySQL        | sqlx      | `READ ONLY` transactions for non-locking reads |
| SQL Server   | tiberius  |                                            |
| Oracle       | `oracle` crate | Optional — build with `--features oracle` (requires Oracle Instant Client SDK) |

## Prerequisites

- **Node.js** `24.17.0` (see `.nvmrc` / `package.json` `devEngines`)
- **pnpm** `10.14.0` (`corepack enable` or install from `packageManager` in `package.json`)
- **Rust** `1.96.0+` (pinned in `src-tauri/rust-toolchain.toml`)
- Platform deps for Tauri 2 ([Tauri prerequisites](https://v2.tauri.app/start/prerequisites/))

## Getting started

```sh
pnpm install
pnpm tauri dev          # desktop app (starts Vite on port 1420 internally)
```

Frontend-only (no Tauri window):

```sh
pnpm dev                # http://localhost:1420
```

Production build:

```sh
pnpm tauri build
```

## Testing

```sh
pnpm check              # Svelte/TypeScript typecheck
pnpm test               # check + vitest (frontend) + cargo test (Rust)
pnpm test:unit          # vitest only (frontend unit + component)
pnpm test:rust          # cargo test only (Rust unit + integration)
pnpm test:smoke         # secrets_smoke + env-gated pg_smoke + env-gated mysql_smoke
```

Rust lint gate (run in `src-tauri/`):

```sh
cargo clippy -- -D warnings
```

### Local MySQL test database

Load the bundled e-commerce fixture (`categories`, `products`, `customers`, `orders`, `order_items`, `inventory_log`) into `safedb_test`:

```sh
pnpm db:seed:mysql                  # seed (preserves local connections + history)
pnpm db:seed:mysql:reset-state     # also wipe safe-db connections + history
pnpm db:seed:mysql:reset            # drop + recreate DB, then wipe safe-db state, then seed
```

The script targets `localhost:3306` as `root` by default. Override with `SAFEDB_TEST_MYSQL_*` env vars (see `scripts/seed_mysql.sh`). If no `mysql` client is on `PATH`, it auto-detects a running MySQL/MariaDB Docker container and runs the client via `docker exec` (pin one with `SAFEDB_TEST_MYSQL_DOCKER=<name>`).

Connect in the app: host `localhost`, port `3306`, database `safedb_test`, user `root` (empty password is valid for local Docker).

### PostgreSQL smoke test

Start PostgreSQL, create a demo database, then export credentials before `pnpm test:smoke`:

```sh
export SAFEDB_TEST_PG_HOST=localhost
export SAFEDB_TEST_PG_DATABASE=demo
export SAFEDB_TEST_PG_USER=safedb
export SAFEDB_TEST_PG_PASSWORD=<password>
pnpm test:smoke
```

## Credentials & keyring

Connection passwords are stored via `keyring-core` with platform-native backends:

| Platform | Store |
| -------- | ----- |
| macOS    | Apple Protected Data (`apple-native-keyring-store`) |
| Windows  | Credential store |
| Linux    | Kernel keyutils |

Override with `SAFEDB_KEYCHAIN_BACKEND`:

- `auto` (default) — Protected Data on macOS when a startup write probe succeeds; in **debug** builds (`pnpm tauri dev`) falls back to in-memory `disabled` when the unsigned binary lacks keychain entitlements (common for local dev)
- `protected` — force Protected Data (macOS only); requires a signed app with entitlements
- `disabled` — in-memory only (tests, CI); passwords do not survive app restart

**Test Connection** uses the password from the form only and does not touch the keyring. **Save Connection** stores the password in the OS credential store (or the in-memory fallback above).

In **release** builds on macOS, Protected Data must pass the write probe or startup fails. Release bundles need sandbox entitlements — see `src-tauri/Entitlements.plist` and `bundle.macOS.entitlements` in `src-tauri/tauri.conf.json`. Use `pnpm tauri build` and run the signed `.app` to verify credentials persist across restarts.

After the first unlock, builder and query paths reuse an in-process credential session so repeated schema loads and queries do not re-hit the OS store.

## Project layout

```
safe-db/
├── src/                              # SvelteKit frontend (SPA)
│   ├── routes/
│   │   ├── +layout.svelte            # global layout (theme, settings load, title bar)
│   │   ├── +page.svelte              # home (recent + saved queries)
│   │   ├── builder/                  # query builder canvas + filter panel
│   │   ├── connections/              # connection list, add form, delete confirm
│   │   └── history/                  # query history with confirm-clear
│   └── lib/
│       ├── components/               # Canvas, TableCard, FilterBuilder, FilterGroupCard,
│       │                             #   FilterRow, SchemaBrowser, ResultsTable,
│       │                             #   ConnectionForm, ConfirmDialog, CommandPalette
│       ├── stores/                   # connections, schema, query, settings,
│       │                             #   history, saved-queries
│       ├── ir.ts                     # shared types (mirrors Rust query IR)
│       ├── hydrate-query.ts          # legacy QuerySpec → current IR migration
│       ├── api.ts                    # Tauri invoke wrappers
│       ├── window.ts                 # syncWindowBackgroundColor (Tauri only)
│       └── test/setup.ts             # Vitest mocks (Tauri invoke, $app/*)
├── src-tauri/                        # Rust backend
│   ├── src/
│   │   ├── adapters/                 # pg, mysql, mssql, oracle (feature-gated)
│   │   ├── query/                    # ir, validate, compile
│   │   ├── commands.rs               # Tauri invoke handlers
│   │   ├── secrets.rs                # keyring + in-process session cache
│   │   ├── introspect.rs             # shared schema types
│   │   ├── config.rs                 # connection profiles (no passwords)
│   │   ├── queries.rs                # saved queries + history store
│   │   ├── settings.rs
│   │   ├── types.rs
│   │   ├── lib.rs / main.rs
│   ├── tests/                        # secrets_smoke, secrets_cache, secrets_macos,
│   │                                 #   pg_smoke, mysql_smoke, stores
│   ├── capabilities/                 # Tauri 2 permissions
│   ├── Entitlements.plist            # macOS sandbox + keychain-access-groups
│   ├── Cargo.toml
│   └── tauri.conf.json
├── scripts/seed_mysql.sh             # local MySQL fixture loader
├── testdata_mysql.sql                # MySQL DDL + seed data
├── vite.config.ts
└── package.json
```

Agent-oriented notes (commands, conventions, cloud VM tips) live in [AGENTS.md](AGENTS.md).
