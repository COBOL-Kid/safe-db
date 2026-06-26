# safe-db — Agent Notes

## Project
Tauri 2 + SvelteKit desktop app for safely exploring production databases.
See `PLAN.md` for the full implementation plan and architecture.

## Tech stack
- **Tauri 2.11** (Rust backend) + **SvelteKit 2** + **Svelte 5 runes** (frontend)
- **TailwindCSS 4** (via `@tailwindcss/vite`, imported in `src/routes/layout.css`)
- **@sveltejs/adapter-static** (SPA fallback mode, SSR disabled)
- DB drivers (P1+): `sqlx` (PG/MySQL), `tiberius` (MSSQL), `oracle` crate (Oracle)

## Commands
- `pnpm tauri dev` — boot the desktop app (Vite + Rust, opens a window)
- `pnpm dev` — frontend-only dev server on port 1420
- `pnpm check` — svelte-check (TypeScript + Svelte type checking)
- `pnpm test` — frontend typecheck plus Rust tests
- `pnpm test:rust` — Rust unit and smoke tests
- `pnpm test:smoke` — keyring round-trip and env-gated Postgres smoke tests
- `pnpm tauri build` — production build
- `cargo check` — verify Rust backend compiles (run in `src-tauri/`)
- `cargo clippy -- -D warnings` — Rust lint gate (run in `src-tauri/`)

## Lint / typecheck
Run `pnpm check` after editing Svelte/TS files. Run `cargo check` in `src-tauri/` after editing Rust. Run `cargo clippy -- -D warnings` before merging backend changes.

## Project structure
```
safe-db/
├── src/                  # SvelteKit frontend
│   ├── routes/           # File-based routing (home, connections, builder, history)
│   ├── lib/              # Shared components, stores, IR types, API wrappers
│   └── app.html
├── src-tauri/            # Rust backend
│   ├── src/              # lib.rs (Tauri builder), main.rs (entry point)
│   ├── capabilities/     # Tauri 2 permissions
│   ├── Cargo.toml
│   └── tauri.conf.json
├── vite.config.ts        # Vite + SvelteKit + Tailwind + adapter-static config
├── PLAN.md               # Full implementation plan
└── package.json
```

## Key conventions
- Svelte 5 runes mode is forced in `vite.config.ts` (use `$props()`, `$state()`, `$derived()`)
- SPA mode: `+layout.ts` exports `ssr = false` and `prerender = false`
- Tailwind 4: no `tailwind.config.js` — CSS-first config via `@import 'tailwindcss'`
- Tauri 2 config schema in `src-tauri/tauri.conf.json` (uses `app.windows`, `build.frontendDist`)
- Rust lib name is `safe_db_lib` (set in `Cargo.toml`, called from `main.rs`)

## Cursor Cloud specific instructions
Standard commands live in `## Commands` above. Notes below are cloud-environment specifics that are not obvious.

- **Toolchain**: Node `24.17.0` (`.nvmrc` + `package.json` `devEngines`) and `pnpm@10.14.0` (`packageManager`). Use a login shell or `nvm use` so Node 24 is on `PATH` before running `pnpm`. Rust `1.96.0+` is pinned in `src-tauri/rust-toolchain.toml` and `Cargo.toml` (`rust-version = "1.96.0"`). The Rust crate is on **edition 2024** — let-chains (`if let X = ... && let Y = ...`) are the preferred style for nested pattern matches, and `std::env::set_var` / `remove_var` are `unsafe` and must be wrapped accordingly.
- **System deps** (already baked into the VM snapshot): Tauri 2 GTK/WebKit libs (`libwebkit2gtk-4.1-dev`, `libgtk-3-dev`, `libsoup-3.0-dev`, `librsvg2-dev`, `libayatana-appindicator3-dev`, `libxdo-dev`, `build-essential`, `pkg-config`) plus `postgresql`.
- **Running the desktop app**: `pnpm tauri dev` needs an X display — use `DISPLAY=:1` (the virtual display). `libEGL ... DRI3` warnings on launch are harmless (software rendering). `pnpm tauri dev` runs its own `pnpm dev` (vite on port `1420`, `strictPort`), so do NOT also run a standalone `pnpm dev` at the same time or the port will conflict.
- **Testing DB connectivity**: the app reads from a real PG/MySQL DB. PostgreSQL is installed but not auto-started; start it with `sudo pg_ctlcluster 16 main start`. A throwaway demo DB can be created (role `safedb` / db `demo`) and connected via the in-app connection form (host `localhost`, port `5432`).
- **Credentials/keyring**: `keyring-core` with the macOS protected data store (`apple-native-keyring-store` feature `protected`), Windows credential store, and Linux kernel keyutils (`linux-keyutils-keyring-store`, headless, no D-Bus Secret Service required). The `protected` store needs the app to be sandboxed/signed; unsigned `pnpm tauri dev` runs will fail to initialize it. For dev iteration, set `SAFEDB_KEYCHAIN_BACKEND=disabled` to use the in-process memory store (no Keychain calls, no prompts; credentials do NOT survive an app restart). In-Rust password cache (15-minute TTL, `Zeroizing<String>`) sits in `src-tauri/src/secrets.rs` to keep queries off the Keychain hot path.
- **Smoke tests**: `pnpm test:smoke` always runs the keyring round-trip test (which opts into the disabled backend, so it passes on any host). For Postgres smoke coverage, start PG (`sudo pg_ctlcluster 16 main start`), create the demo DB (`safedb` / `demo`), then export `SAFEDB_TEST_PG_HOST=localhost`, `SAFEDB_TEST_PG_DATABASE=demo`, `SAFEDB_TEST_PG_USER=safedb`, `SAFEDB_TEST_PG_PASSWORD=<password>` before running `pnpm test:smoke`.
- Running a query currently surfaces an `EXPLAIN failed` guard warning (the cost-guard EXPLAIN step), but the query itself still executes and returns rows — this is app behavior, not an environment problem.
