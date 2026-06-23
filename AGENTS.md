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
- `pnpm tauri build` — production build
- `cargo check` — verify Rust backend compiles (run in `src-tauri/`)

## Lint / typecheck
Run `pnpm check` after editing Svelte/TS files. Run `cargo check` in `src-tauri/` after editing Rust.

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
