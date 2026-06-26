# safe-db

Desktop app for safely exploring production databases. Connect to PostgreSQL, MySQL, SQL Server, or Oracle, browse schema visually, build read-only queries in a canvas UI, and run them with guardrails: non-locking reads, enforced row limits, blocked system schemas, and EXPLAIN cost warnings.

Built with **Tauri 2** (Rust) and **SvelteKit 2** (Svelte 5).

## Features

- **Connections** — save named profiles; passwords stored in the OS credential store (not on disk)
- **Schema browser** — tables, columns, and indexes with system/catalog schemas filtered out
- **Visual query builder** — drag tables onto a canvas, join, filter, select columns, set a row limit
- **Safety rails** — read-only `SELECT` queries, max 1 000 rows, 10 s timeout, blocked schemas, optional EXPLAIN cost threshold
- **Saved queries & history** — reopen past work from the home screen or history page
- **Command palette** — `Cmd+K` / `Ctrl+K` for quick navigation
- **Light / dark theme**

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
pnpm test               # check + all Rust tests
pnpm test:rust          # Rust unit and integration tests
pnpm test:smoke         # keyring round-trip + env-gated Postgres smoke test
```

Rust lint gate (run in `src-tauri/`):

```sh
cargo clippy -- -D warnings
```

### Local MySQL test database

Load the bundled e-commerce fixture (`categories`, `products`, `customers`, `orders`, `order_items`, `inventory_log`) into `safedb_test`:

```sh
pnpm db:seed:mysql              # seed
pnpm db:seed:mysql:reset        # drop + recreate, then seed
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

- `auto` (default) — Protected Data on macOS when available; in **debug** builds falls back to in-memory `disabled` if Protected Data is unavailable (unsigned `pnpm tauri dev`)
- `protected` — force Protected Data (macOS only)
- `disabled` — in-memory only (tests, CI)

In **release** builds on macOS, Protected Data must initialize or startup fails. Release bundles need sandbox entitlements — see `src-tauri/Entitlements.plist` and `bundle.macOS.entitlements` in `src-tauri/tauri.conf.json`.

After the first unlock, builder and query paths reuse an in-process credential session so repeated schema loads and queries do not re-hit the OS store.

## Project layout

```
safe-db/
├── src/                      # SvelteKit frontend
│   ├── routes/               # home, connections, builder, history
│   └── lib/                  # components, stores, IR types, API wrappers
├── src-tauri/                # Rust backend
│   ├── src/
│   │   ├── adapters/         # PG, MySQL, MSSQL, Oracle drivers
│   │   ├── query/            # IR, validation, SQL compilation
│   │   ├── commands.rs       # Tauri invoke handlers
│   │   └── secrets.rs        # keyring + session cache
│   ├── tests/                # smoke and integration tests
│   └── Entitlements.plist    # macOS sandbox entitlements
├── scripts/seed_mysql.sh      # local MySQL fixture loader
└── testdata_mysql.sql        # MySQL DDL + seed data
```

Agent-oriented notes (commands, conventions, cloud VM tips) live in [AGENTS.md](AGENTS.md).
