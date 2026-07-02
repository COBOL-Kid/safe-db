# safe-db — Agent Notes

## Project
**Primary:** Jetpack Compose Desktop + Kotlin/JDBC in [`compose-app/`](compose-app/) (`shared` module + Compose UI).

**Legacy (deprecated):** Tauri 2 + SvelteKit desktop app in `src/` + `src-tauri/`.

See the git history (early commits `P0`–`P4`) for the original implementation plan.

### Compose / Kotlin commands
- `cd compose-app && ./gradlew run` — boot the desktop app
- `cd compose-app && ./gradlew check` — shared unit tests (query engine, stores, adapters, connection parser)
- `cd compose-app && ./gradlew packageDistributionForCurrentOS` — native package (deb/AppImage/rpm on Linux)
- App data: `~/.local/share/com.safedb.app/` (Linux), same JSON filenames as Tauri
- Credentials: `SAFEDB_KEYCHAIN_BACKEND` (`auto`, `disabled`, `protected` on macOS)

### Legacy Tauri commands (still in tree)

## Features (for context)
- **Connections** — CRUD via `SafeDbService`; passwords in OS keyring, metadata in app data dir; form has show/hide password toggle
- **Schema introspection** — per-dialect JDBC adapters in `compose-app/shared/`; system schemas blocked in `query/validate`
- **Visual query builder** — frontend IR (`src/lib/ir.ts`) compiled to dialect-specific SQL in `src-tauri/src/query/compile.rs`; recursive filter groups with per-child AND/OR connector overrides
- **Safety** — read-only selects, row limit (default 100, interactive max 10 000, guidance above 1 000), 10 s query timeout, blocked schemas, filter literal type validation, and a cost-preview guard that requires confirmation when cost is unavailable or above threshold
- **Saved queries & history** — separate persisted stores in the app data dir (`saved-queries.svelte.ts` and `history.svelte.ts`); timestamps are Unix-seconds strings
- **In-app confirmations** — destructive actions use the `ConfirmDialog` component (not `window.confirm()`, which is unreliable in Tauri’s macOS WebView)
- **Settings** — theme, `explain_cost_threshold`, `blocked_schemas` (`src-tauri/src/settings.rs`); `syncWindowBackgroundColor` updates the Tauri window background to match light/dark
- **Command palette** — `Cmd+K` / `Ctrl+K` (`CommandPalette.svelte`)

## Tech stack
- **Tauri 2.11.3** (Rust backend) + **SvelteKit 2** + **Svelte 5 runes** (frontend)
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
- `pnpm db:seed:mysql` — stream generated SQL into the local MySQL test DB for a larger reporting fixture (~50k orders by default; pass args after `--`, e.g. `--orders 20000 --customers 5000`); leaves `connections.json` and `query_history.json` alone. `pnpm db:seed:mysql:static` loads the smaller bundled `testdata_mysql.sql` fixture. `db:seed:mysql:reset-state` additionally wipes connections + history (saved queries and settings are always left untouched). `db:seed:mysql:reset` is a shorthand for generated seeding with `--reset --reset-state` (drop + recreate DB, then wipe app state) — destructive, use deliberately. Auto-connects to `localhost:3306` as `root` into `safedb_test`, overridable via `SAFEDB_TEST_MYSQL_*` env vars. If no `mysql` client is on PATH, auto-detects a running mysql/mariadb Docker container and runs the client inside it via `docker exec` (pin one with `SAFEDB_TEST_MYSQL_DOCKER=<name>`). When password is unset, reads `MYSQL_ROOT_PASSWORD` from the container env (docker-exec and host-client modes against localhost).
- `pnpm tauri build` — production build
- `cargo check` — verify Rust backend compiles (run in `src-tauri/`)
- `cargo clippy -- -D warnings` — Rust lint gate (run in `src-tauri/`)
- `cargo build --features oracle` — enable Oracle adapter (requires Oracle Instant Client SDK)

## Lint / typecheck
Run `pnpm check` after editing Svelte/TS files. Run `cargo check` in `src-tauri/` after editing Rust. Run `cargo clippy -- -D warnings` before merging backend changes.

## Testing
- **Fast gate (CI/local default):** `pnpm test` — typecheck, Vitest (`src/**/*.test.ts`), and all Rust tests including `secrets_cache` and `stores` (no live DB required).
- **Frontend only:** `pnpm test:unit` — query store + hydration, schema/settings/connections stores, `ConnectionForm`, `ConfirmDialog`, `FilterBuilder`, `ResultsTable`, plus page-level tests for connections; mocks Tauri invoke and SvelteKit `$app/*` in `src/lib/test/setup.ts`.
- **Backend unit/integration tests:** Rust tests live under `src-tauri/tests/` and cover adapters, commands/query core, stores, query IR/validation/compilation, secrets, and DB smoke paths.
- **Smoke gate (optional, needs DB):** `pnpm test:smoke` after seeding/configuring databases:
  - `secrets_smoke` — always runs (disabled keyring backend).
  - `pg_smoke` — set `SAFEDB_TEST_PG_HOST`, `SAFEDB_TEST_PG_DATABASE`, `SAFEDB_TEST_PG_USER`, `SAFEDB_TEST_PG_PASSWORD` (optional `SAFEDB_TEST_PG_PORT`).
  - `mysql_smoke` — seed with `pnpm db:seed:mysql`, then set `SAFEDB_TEST_MYSQL_HOST`, `SAFEDB_TEST_MYSQL_DATABASE`, `SAFEDB_TEST_MYSQL_USER` (optional `SAFEDB_TEST_MYSQL_PASSWORD`, `SAFEDB_TEST_MYSQL_PORT`; empty password is valid for local root).
- Smoke tests skip gracefully when env vars are unset; `secrets_native` remains `#[ignore]` for manual macOS / Linux verification.

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
│   │   ├── query/                    # ir, validate + helpers, compile + helpers
│   │   ├── commands.rs               # command module root / re-exports
│   │   ├── commands/                 # connections, query, query_core, saved_queries, settings
│   │   ├── secrets.rs                # secrets module root / re-exports
│   │   ├── secrets/                  # backend selection, store access, session cache
│   │   ├── introspect.rs             # shared schema types
│   │   ├── config.rs                 # connection profiles (no passwords)
│   │   ├── queries.rs                # saved queries + history store
│   │   ├── settings.rs
│   │   ├── types.rs
│   │   ├── lib.rs / main.rs
│   ├── tests/
│   │   ├── adapters.rs               # adapter helper behavior
│   │   ├── commands.rs               # query core behavior and cost guard
│   │   ├── core.rs                   # connection validation, indexes, atomic write
│   │   ├── ir.rs / query.rs          # IR, validation, compilation
│   │   ├── secrets*.rs               # secrets backend/cache/native/smoke coverage
│   │   ├── pg_smoke.rs / mysql_smoke.rs
│   │   └── stores.rs                 # config/settings/query store round-trips and migrations
│   ├── capabilities/                 # Tauri 2 permissions
│   ├── Entitlements.plist            # macOS sandbox + keychain-access-groups
│   ├── Cargo.toml
│   └── tauri.conf.json
├── vite.config.ts
├── scripts/seed_mysql.sh
├── scripts/generate_mysql_fixture.mjs
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

### Kotlin + Jetpack Compose migration (`compose-app/`)
The app is being migrated to Kotlin using Jetpack Compose (Compose Multiplatform for **Desktop/JVM**, the runnable analog of Android Jetpack Compose for this desktop app). The Rust/Svelte stack still exists in `src/` and `src-tauri/`, but Kotlin/Compose work happens under `compose-app/`.

- **Toolchain (baked into the VM snapshot)**: Temurin **JDK 25** at `/opt/jdk-25` (`JAVA_HOME` is exported in `~/.bashrc`), Gradle **9.6.1** at `/opt/gradle`. Prefer the project wrapper `compose-app/gradlew` over the global `gradle`. Versions: Kotlin `2.4.0`, Compose Multiplatform `1.9.3`, `jvmToolchain(25)` (Gradle running on JDK 25 satisfies the toolchain).
- **Commands** (run from `compose-app/`, ensure `JAVA_HOME=/opt/jdk-25`): `./gradlew build` (compile + test), `./gradlew test` (unit tests), `./gradlew run` (launch the desktop window). `./gradlew check` is the lint/test gate; there is no separate linter configured yet.
- **Running the app**: `./gradlew run` needs an X display — use `DISPLAY=:1`. On launch Skiko logs `Cannot create Linux GL context` then `Fallback to next API`; this is expected (no GPU) and it renders via software rasterization. The window title is `safe-db (Compose)`.
- **Do NOT run a Gradle daemon-less build (`--no-daemon`) and `./gradlew run` at the same time from two shells** if they would contend on `compose-app/build/`; stop the running app first or use separate tasks.


- **Toolchain**: Node `24.18.0` (`.nvmrc` + `package.json` `devEngines`) and `pnpm@11.9.0` (`packageManager`). Use a login shell or `nvm use` so Node 24 is on `PATH` before running `pnpm`. Rust `1.96.0+` is pinned in `src-tauri/rust-toolchain.toml` and `Cargo.toml` (`rust-version = "1.96.0"`).
- **System deps** (already baked into the VM snapshot): Tauri 2 GTK/WebKit libs (`libwebkit2gtk-4.1-dev`, `libgtk-3-dev`, `libsoup-3.0-dev`, `librsvg2-dev`, `libayatana-appindicator3-dev`, `libxdo-dev`, `build-essential`, `pkg-config`) plus `postgresql`.
- **Running the desktop app**: `pnpm tauri dev` needs an X display — use `DISPLAY=:1` (the virtual display). `libEGL ... DRI3` warnings on launch are harmless (software rendering). `pnpm tauri dev` runs its own `pnpm dev` (vite on port `1420`, `strictPort`), so do NOT also run a standalone `pnpm dev` at the same time or the port will conflict.
- **Testing DB connectivity**: the app reads from a real PG/MySQL DB. For MySQL, `pnpm db:seed:mysql` streams a deterministic generated version of the e-commerce schema (categories, products, customers, orders, order_items, inventory_log) for larger reporting-style datasets without checking in a giant SQL file. Use `pnpm db:seed:mysql:static` to load the smaller bundled `testdata_mysql.sql` fixture instead. The script auto-connects to `localhost:3306` / `root` and reads `SAFEDB_TEST_MYSQL_*` env vars for overrides. PostgreSQL is installed but not auto-started; start it with `sudo pg_ctlcluster 16 main start`. A throwaway demo DB can be created (role `safedb` / db `demo`) and connected via the in-app connection form (host `localhost`, port `5432`).
- **Credentials/keyring**: `keyring-core` with platform-native stores. On macOS the only OS-backed store is Apple **Protected Data** (`apple-native-keyring-store` feature `protected`); legacy Keychain is intentionally unsupported. The default (`SAFEDB_KEYCHAIN_BACKEND=auto`) probes Protected Data with a throwaway write at startup; in **debug** builds it falls back to in-memory `disabled` when the probe fails (unsigned `pnpm tauri dev` lacks keychain entitlements even though `Store::new()` succeeds). **Test Connection** does not use the keyring; **Save Connection** does. In **release** builds, the probe must pass or startup fails. Override with `protected` or `disabled`. Protected Data requires sandbox entitlements — see [src-tauri/Entitlements.plist](src-tauri/Entitlements.plist) and `bundle.macOS.entitlements` in [src-tauri/tauri.conf.json](src-tauri/tauri.conf.json). Use `pnpm tauri build` and the signed `.app` to test credential persistence across restarts. Windows uses the credential store; Linux uses kernel keyutils (`linux-keyutils-keyring-store`, headless). Builder/query paths use an in-process credential **session** in `src-tauri/src/secrets/session.rs` so repeated `get_schema` / `run_query` calls do not hit the OS credential store after the first unlock.
- **Smoke tests**: `pnpm test:smoke` runs `secrets_smoke` (opts into `disabled` backend, passes on any host), env-gated `pg_smoke`, and env-gated `mysql_smoke` (seed with `pnpm db:seed:mysql` first). `secrets_cache` and `stores` run via `pnpm test:rust` / `pnpm test`. `secrets_native` is `#[ignore]` and meant for manual macOS / Linux verification: `SAFEDB_KEYCHAIN_BACKEND=auto cargo test --test secrets_native -- --ignored --nocapture`.
- Running a query may surface a cost-preview guard. The first run is blocked and the UI asks to "Run with safeguards"; a confirmed forced retry executes with the same validation, row limit, and timeout.

## Learned User Preferences
- Trust DB admin/infra teams for transport security configuration; do not surface secure-transport acknowledgment or notification checkboxes to end users (the app assumes users have not configured the database).
- Avoid security status indicators that can be temporarily misleading (e.g., a "not secure" dot before the host autoresolver has run); prefer omitting such indicators over showing potentially-wrong state.
- Advanced connection settings target technical users; use direct, explicit labels with "SSL" terminology (e.g., "SSL with hostname verification", "SSL encrypt only (no cert check)").

## Learned Workspace Facts
- Empty database passwords are valid connection credentials, especially for local MySQL; preserve `""` through form submission, credential storage, and builder/query paths.
- `pnpm db:seed:mysql` no longer wipes the app data dir by default; it leaves `connections.json` and `query_history.json` alone and streams a generated larger dataset (~50k orders by default, configurable with args after `--`) without committing generated SQL. Use `pnpm db:seed:mysql:static` to load the smaller bundled `testdata_mysql.sql` fixture. Use `pnpm db:seed:mysql:reset-state` to wipe connections and history (saved queries and settings are always left untouched). `pnpm db:seed:mysql:reset` uses generated seeding with `--reset --reset-state` (drop DB + wipe app state).
- `window.confirm()` and `prompt()` are unreliable in Tauri’s macOS WebView (dialogs can hide behind the app window); use in-app `ConfirmDialog` / `PromptDialog` for delete, clear-history, and save-query flows.
- Connection profiles (version 2) include `transport_security`; legacy entries missing the field migrate on load to `Disabled` with `legacy_implicit: true` (backup at `connections.migration.bak`), keeping local plaintext DBs working until re-saved with an explicit transport choice.
- Local MySQL for dev/smoke tests uses sibling `mysql-test-container` (`safedb-mysql`): `root` with empty password on `safedb_test`, or read-only `testuser` on `safedb_test` / `honestcar`.
- There is no in-app connection edit flow; delete and add a new connection is the supported path.
- Query filter values must use literal kinds matching the schema-derived column category; hydration should normalize old saved/history specs before rerun.
- Empty result sets should still expose selected column metadata in results where the adapter can infer it from compiled SQL.
- MSSQL `EXPLAIN` opens a short-lived separate `mssql::connect()` client; the main execution client is never put in `SHOWPLAN_XML` mode, so forced query retries stay on a clean session.
- The insecure-transport acknowledgment was fully removed from the stack (`insecure_acknowledged` dropped from `ir.ts`, `connection-presets.ts`, `parse-connection-string.ts`, `types.rs`, and the `config.rs` legacy migration); the misleading colored-dot security status strip was also removed from `ConnectionForm.svelte`.
- `handleHostInput` resyncs transport when the host crosses local/remote boundaries on both guided and string paths (skipping auto-resync when `location === 'organization'` or transport was manually overridden); `resetToChoose()` clears password and parsed-string state when switching between guided and string paths to prevent password leaks.
- Connection-string parser defaults: Postgres localhost default mirrors MySQL (`isLocalHost(host) ? 'Disabled' : 'VerifyIdentity'`); Oracle requires an explicit `jdbc:oracle:thin:`, `tcps:`, or `@//` prefix (no bare `//`); TLS error classification maps `certificate required` to `certificate_required` (checked before `untrusted_ca`), and the SSL troubleshooting UI shows a CA PEM textarea only for `untrusted_ca` / org `unknown` with static guidance for `hostname_mismatch` / `certificate_required`.
