# safe-db — Agent Notes

## Project
Tauri 2 + SvelteKit desktop app for safely exploring production databases.
See the git history (early commits `P0`–`P4`) for the original implementation plan; it has been fully implemented.

## Features (for context)
- **Connections** — CRUD via Tauri commands; passwords in OS keyring, metadata in app data dir; form has show/hide password toggle
- **Schema introspection** — per-dialect adapters in `src-tauri/src/adapters/`; system schemas blocked in `query/validate.rs`
- **Visual query builder** — frontend IR (`src/lib/ir.ts`) compiled to dialect-specific SQL in `src-tauri/src/query/compile.rs`; recursive filter groups with per-child AND/OR connector overrides
- **Safety** — read-only selects, row limit (max 1 000, default 100), 10 s query timeout, EXPLAIN cost warnings, custom blocked schemas in settings
- **Saved queries & history** — separate persisted stores in the app data dir (`saved-queries.svelte.ts` and `history.svelte.ts`)
- **In-app confirmations** — destructive actions use the `ConfirmDialog` component (not `window.confirm()`, which is unreliable in Tauri’s macOS WebView)
- **Settings** — theme, `explain_cost_threshold`, `blocked_schemas` (`src-tauri/src/settings.rs`); `syncWindowBackgroundColor` updates the Tauri window background to match light/dark
- **Command palette** — `Cmd+K` / `Ctrl+K` (`CommandPalette.svelte`)

## Tech stack
- **Tauri 2.11** (Rust backend) + **SvelteKit 2** + **Svelte 5 runes** (frontend)
- **TailwindCSS 4** (via `@tailwindcss/vite`, imported in `src/routes/layout.css`)
- **@sveltejs/adapter-static** (SPA fallback mode, SSR disabled)
- DB drivers: `sqlx` 0.9 (PG/MySQL), `tiberius` (MSSQL), `oracle` crate (Oracle, optional `oracle` Cargo feature)
- Credentials: `keyring-core` 1.0 with platform stores (`apple-native-keyring-store`, `windows-native-keyring-store`, `linux-keyutils-keyring-store`)

## Commands
- `pnpm tauri dev` — boot the desktop app (Vite + Rust, opens a window)
- `pnpm dev` — frontend-only dev server on port 1420
- `pnpm check` — svelte-check (TypeScript + Svelte type checking)
- `pnpm check:watch` — svelte-check in watch mode
- `pnpm test` — `pnpm check`, frontend unit tests (`vitest`), and Rust tests
- `pnpm test:unit` — Vitest unit/component tests (no database required)
- `pnpm test:unit:watch` — Vitest in watch mode
- `pnpm test:rust` — Rust unit and integration tests (`cargo test` in `src-tauri/`)
- `pnpm test:smoke` — `secrets_smoke`, env-gated `pg_smoke`, and env-gated `mysql_smoke`
- `pnpm db:seed:mysql` — load `testdata_mysql.sql` into the local MySQL test DB; leaves `connections.json` and `query_history.json` alone. `db:seed:mysql:reset-state` additionally wipes connections + history (saved queries and settings are always left untouched). `db:seed:mysql:reset` is a shorthand for `--reset --reset-state` (drop + recreate DB, then wipe app state) — destructive, use deliberately. Auto-connects to `localhost:3306` as `root` into `safedb_test`, overridable via `SAFEDB_TEST_MYSQL_*` env vars. If no `mysql` client is on PATH, auto-detects a running mysql/mariadb Docker container and runs the client inside it via `docker exec` (pin one with `SAFEDB_TEST_MYSQL_DOCKER=<name>`). When password is unset, reads `MYSQL_ROOT_PASSWORD` from the container env (docker-exec and host-client modes against localhost).
- `pnpm tauri build` — production build
- `cargo check` — verify Rust backend compiles (run in `src-tauri/`)
- `cargo clippy -- -D warnings` — Rust lint gate (run in `src-tauri/`)
- `cargo build --features oracle` — enable Oracle adapter (requires Oracle Instant Client SDK)

## Lint / typecheck
Run `pnpm check` after editing Svelte/TS files. Run `cargo check` in `src-tauri/` after editing Rust. Run `cargo clippy -- -D warnings` before merging backend changes.

## Testing
- **Fast gate (CI/local default):** `pnpm test` — typecheck, Vitest (`src/**/*.test.ts`), and all Rust tests including `secrets_cache` and `stores` (no live DB required).
- **Frontend only:** `pnpm test:unit` — query store + hydration, schema/settings/connections stores, `ConnectionForm`, `ConfirmDialog`, `FilterBuilder`, `ResultsTable`, plus page-level tests for connections; mocks Tauri invoke and SvelteKit `$app/*` in `src/lib/test/setup.ts`.
- **Backend unit tests:** inline `#[cfg(test)]` in `query/validate.rs`, `query/compile.rs`, `introspect.rs`.
- **Smoke gate (optional, needs DB):** `pnpm test:smoke` after seeding/configuring databases:
  - `secrets_smoke` — always runs (disabled keyring backend).
  - `pg_smoke` — set `SAFEDB_TEST_PG_HOST`, `SAFEDB_TEST_PG_DATABASE`, `SAFEDB_TEST_PG_USER`, `SAFEDB_TEST_PG_PASSWORD` (optional `SAFEDB_TEST_PG_PORT`).
  - `mysql_smoke` — seed with `pnpm db:seed:mysql`, then set `SAFEDB_TEST_MYSQL_HOST`, `SAFEDB_TEST_MYSQL_DATABASE`, `SAFEDB_TEST_MYSQL_USER` (optional `SAFEDB_TEST_MYSQL_PASSWORD`, `SAFEDB_TEST_MYSQL_PORT`; empty password is valid for local root).
- Smoke tests skip gracefully when env vars are unset; `secrets_macos` remains `#[ignore]` for manual macOS verification.

## Project structure
```
safe-db/
├── src/                              # SvelteKit frontend (SPA)
│   ├── routes/                       # home, connections, builder, history
│   ├── lib/
│   │   ├── components/               # Canvas, TableCard, FilterBuilder, FilterGroupCard,
│   │   │                             #   FilterRow, SchemaBrowser, ResultsTable,
│   │   │                             #   ConnectionForm, ConfirmDialog, CommandPalette
│   │   ├── stores/                   # connections, schema, query, settings,
│   │   │                             #   history, saved-queries
│   │   ├── ir.ts                     # shared types (mirrors Rust query IR)
│   │   ├── hydrate-query.ts          # legacy QuerySpec → current IR migration
│   │   ├── api.ts                    # Tauri invoke wrappers
│   │   ├── window.ts                 # syncWindowBackgroundColor (Tauri only)
│   │   └── test/setup.ts             # Vitest mocks (Tauri invoke, $app/*)
│   └── app.html
├── src-tauri/                        # Rust backend
│   ├── src/
│   │   ├── adapters/                 # pg, mysql, mssql, oracle (feature-gated)
│   │   ├── query/                    # ir, validate, compile
│   │   ├── commands.rs               # Tauri command handlers
│   │   ├── secrets.rs                # keyring + in-process session cache
│   │   ├── introspect.rs             # shared schema types
│   │   ├── config.rs                 # connection profiles (no passwords)
│   │   ├── queries.rs                # saved queries + history store
│   │   ├── settings.rs
│   │   ├── types.rs
│   │   ├── lib.rs / main.rs
│   ├── tests/
│   │   ├── secrets_smoke.rs          # keyring round-trip (disabled backend)
│   │   ├── secrets_cache.rs          # session cache avoids repeat OS reads
│   │   ├── secrets_macos.rs          # manual macOS Protected Data smoke (#[ignore])
│   │   ├── pg_smoke.rs               # env-gated Postgres connect + introspect
│   │   ├── mysql_smoke.rs            # env-gated MySQL connect + introspect
│   │   └── stores.rs                 # saved queries + history store round-trip
│   ├── capabilities/                 # Tauri 2 permissions
│   ├── Entitlements.plist            # macOS sandbox + keychain-access-groups
│   ├── Cargo.toml
│   └── tauri.conf.json
├── vite.config.ts
├── scripts/seed_mysql.sh
├── testdata_mysql.sql
└── package.json
```

## Key conventions
- Svelte 5 runes mode is forced in `vite.config.ts` (use `$props()`, `$state()`, `$derived()`)
- SPA mode: `+layout.ts` exports `ssr = false` and `prerender = false`
- Tailwind 4: no `tailwind.config.js` — CSS-first config via `@import 'tailwindcss'`
- Tauri 2 config schema in `src-tauri/tauri.conf.json` (uses `app.windows`, `build.frontendDist`)
- Rust lib name is `safe_db_lib` (set in `Cargo.toml`, called from `main.rs`)
- Rust edition **2024** — let-chains (`if let X = ... && let Y = ...`) preferred; `std::env::set_var` / `remove_var` are `unsafe`
- Empty database passwords are valid (especially local MySQL) — preserve `""` through form submission, credential storage, and builder/query paths

## Cursor Cloud specific instructions
Standard commands live in `## Commands` above. Notes below are cloud-environment specifics that are not obvious.

- **Toolchain**: Node `24.17.0` (`.nvmrc` + `package.json` `devEngines`) and `pnpm@10.14.0` (`packageManager`). Use a login shell or `nvm use` so Node 24 is on `PATH` before running `pnpm`. Rust `1.96.0+` is pinned in `src-tauri/rust-toolchain.toml` and `Cargo.toml` (`rust-version = "1.96.0"`).
- **System deps** (already baked into the VM snapshot): Tauri 2 GTK/WebKit libs (`libwebkit2gtk-4.1-dev`, `libgtk-3-dev`, `libsoup-3.0-dev`, `librsvg2-dev`, `libayatana-appindicator3-dev`, `libxdo-dev`, `build-essential`, `pkg-config`) plus `postgresql`.
- **Running the desktop app**: `pnpm tauri dev` needs an X display — use `DISPLAY=:1` (the virtual display). `libEGL ... DRI3` warnings on launch are harmless (software rendering). `pnpm tauri dev` runs its own `pnpm dev` (vite on port `1420`, `strictPort`), so do NOT also run a standalone `pnpm dev` at the same time or the port will conflict.
- **Testing DB connectivity**: the app reads from a real PG/MySQL DB. For MySQL, `pnpm db:seed:mysql` loads `testdata_mysql.sql` (e-commerce schema: categories, products, customers, orders, order_items, inventory_log) into `safedb_test`; the script auto-connects to `localhost:3306` / `root` and reads `SAFEDB_TEST_MYSQL_*` env vars for overrides. PostgreSQL is installed but not auto-started; start it with `sudo pg_ctlcluster 16 main start`. A throwaway demo DB can be created (role `safedb` / db `demo`) and connected via the in-app connection form (host `localhost`, port `5432`).
- **Credentials/keyring**: `keyring-core` with platform-native stores. On macOS the only OS-backed store is Apple **Protected Data** (`apple-native-keyring-store` feature `protected`); legacy Keychain is intentionally unsupported. The default (`SAFEDB_KEYCHAIN_BACKEND=auto`) probes Protected Data with a throwaway write at startup; in **debug** builds it falls back to in-memory `disabled` when the probe fails (unsigned `pnpm tauri dev` lacks keychain entitlements even though `Store::new()` succeeds). **Test Connection** does not use the keyring; **Save Connection** does. In **release** builds, the probe must pass or startup fails. Override with `protected` or `disabled`. Protected Data requires sandbox entitlements — see [src-tauri/Entitlements.plist](src-tauri/Entitlements.plist) and `bundle.macOS.entitlements` in [src-tauri/tauri.conf.json](src-tauri/tauri.conf.json). Use `pnpm tauri build` and the signed `.app` to test credential persistence across restarts. Windows uses the credential store; Linux uses kernel keyutils (`linux-keyutils-keyring-store`, headless). Builder/query paths use an in-process credential **session** in [src-tauri/src/secrets.rs](src-tauri/src/secrets.rs) so repeated `get_schema` / `run_query` calls do not hit the OS credential store after the first unlock.
- **Smoke tests**: `pnpm test:smoke` runs `secrets_smoke` (opts into `disabled` backend, passes on any host), env-gated `pg_smoke`, and env-gated `mysql_smoke` (seed with `pnpm db:seed:mysql` first). `secrets_cache` and `stores` run via `pnpm test:rust` / `pnpm test`. `secrets_macos` is `#[ignore]` and meant for manual macOS verification: `SAFEDB_KEYCHAIN_BACKEND=auto cargo test --test secrets_macos -- --ignored --nocapture`.
- Running a query currently surfaces an `EXPLAIN failed` guard warning (the cost-guard EXPLAIN step), but the query itself still executes and returns rows — this is app behavior, not an environment problem.

## Learned User Preferences

## Learned Workspace Facts
- Empty database passwords are valid connection credentials, especially for local MySQL; preserve `""` through form submission, credential storage, and builder/query paths.
- `pnpm db:seed:mysql` no longer wipes the app data dir by default; it leaves `connections.json` and `query_history.json` alone. Use `pnpm db:seed:mysql:reset-state` to wipe connections and history (saved queries and settings are always left untouched). `pnpm db:seed:mysql:reset` is now `--reset --reset-state` (drop DB + wipe app state).
- `window.confirm()` is unreliable in Tauri’s macOS WebView (dialog can hide behind the app window); use in-app or Tauri-native confirmation for delete and other destructive actions.
- Local MySQL for dev/smoke tests uses sibling `mysql-test-container` (`safedb-mysql`): `root` with empty password on `safedb_test`, or read-only `testuser` on `safedb_test` / `honestcar`.
- There is no in-app connection edit flow; delete and add a new connection is the supported path.
