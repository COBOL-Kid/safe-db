# safe-db — Implementation Plan

A Tauri 2 + SvelteKit desktop app that lets non-technical users interact with production relational databases **safely**: non-locking reads, joins only on indexed fields, and enforced best practices. Design is intuitive, hands-on, and modern — no complex menus or dropdowns.

## Locked-in decisions

- **Interaction model**: Visual builder only. Frontend emits a structured `QuerySpec`; Rust validates and compiles it to dialect-specific parameterized SQL. No raw SQL pane.
- **DB rollout order**: PostgreSQL + MySQL first (pure-Rust `sqlx`), then MSSQL (`tiberius`, pure-Rust), then Oracle (`oracle` crate, requires Oracle Instant Client).
- **No-lock philosophy**: Strict no-lock (dirty-read tolerant) where the dialect allows it. READ UNCOMMITTED / MSSQL NOLOCK equivalents. PostgreSQL cannot dirty-read — it gets read-consistent minimal-locking reads (UI will be honest about this).
- **Credential storage**: OS keyring (`keyring` crate — macOS Keychain / Windows Credential Manager / Linux Secret Service).
- **Guards enforced (all)**: mandatory row LIMIT, statement timeout, EXPLAIN cost guard, recommend read-only DB role, block sensitive/system tables.

## Tech stack

- **Tauri 2** (Rust backend) + **SvelteKit + Svelte 5 runes** (frontend, `@sveltejs/adapter-static`, SPA fallback).
- **TailwindCSS** for modern/sleek styling; hand-rolled SVG canvas (no heavy graph lib).
- DB drivers: `sqlx` (PG/MySQL), `tiberius` (MSSQL), `oracle` crate (Oracle + Instant Client).
- `keyring` crate for OS keyring credential storage.
- `sqlparser-rs` only if we later add an advanced SQL mode (not in P1–P3).

## Project structure

```
safe-db/
├── src-tauri/                  # Rust backend
│   ├── src/
│   │   ├── main.rs
│   │   ├── adapters/           # DatabaseAdapter trait + per-dialect impls
│   │   │   ├── mod.rs  pg.rs  mysql.rs  mssql.rs  oracle.rs
│   │   ├── introspect/         # schema + index model per dialect
│   │   ├── query/              # QuerySpec IR → validated → dialect SQL
│   │   │   ├── ir.rs  validate.rs  compile.rs  explain.rs
│   │   ├── safety/             # no-lock policy, limit/timeout, cost guard, table blocklist
│   │   ├── secrets.rs          # OS keyring
│   │   └── commands.rs         # Tauri IPC commands
│   └── Cargo.toml
├── src/                        # SvelteKit
│   ├── routes/                 # home, connections, builder, results
│   ├── lib/
│   │   ├── components/         # Canvas, TableCard, JoinEdge, FilterChip, ResultsTable
│   │   ├── stores/             # connections, schema, query, results
│   │   ├── ir.ts               # QuerySpec types (mirror of Rust IR)
│   │   └── api.ts              # invoke() wrappers
│   └── app.css                 # Tailwind
├── static/  svelte.config.js  tauri.conf.json  package.json
```

## Safety engine (core differentiator)

Frontend sends a **`QuerySpec`** (tables, selected columns, joins as `{left_tbl, left_col, right_tbl, right_col}`, filters, limit). Rust pipeline:

1. **Validate** — join keys must exist in the introspected **index model** (PK/unique/secondary index); reject otherwise. Tables must not be in the **sensitive blocklist** (`pg_catalog`, `information_schema`, `mysql.*`, `sys`, `SYSTEM.*`, etc., configurable).
2. **Guard** — cap `LIMIT` (default 100, max 1000), set **statement timeout** (default 10s), run **EXPLAIN** and block/warn if estimated cost > threshold.
3. **Execute** in a short-lived **READ ONLY** transaction at the **lowest isolation** the dialect supports:

| Dialect | Isolation / no-lock | Notes |
|---|---|---|
| PostgreSQL | `READ UNCOMMITTED` (PG silently maps to READ COMMITTED) + `READ ONLY` | PG can't dirty-read; minimal locking, read-consistent. UI surfaces this honestly. |
| MySQL | `READ UNCOMMITTED` + `START TRANSACTION READ ONLY` | True dirty reads; minimal InnoDB locking. |
| MSSQL | `SET TRANSACTION ISOLATION LEVEL READ UNCOMMITTED` (≈ NOLOCK all tables) + read-only txn | True dirty reads. |
| Oracle | `SET TRANSACTION READ ONLY` | Always read-consistent; no locks for SELECTs. |

4. **Compile** the validated IR to **dialect-specific parameterized SQL** (placeholders `$N`/`?`/`@p`, NOLOCK hints for MSSQL, etc.) → `sqlx::query().bind()` / tiberius / oracle. Injection-safe by construction.
5. **Recommend** (in connection UI) a dedicated read-only DB role; defense in depth beyond app-level READ ONLY.

## UX / design

- **Home**: connection cards + saved/recent query cards.
- **Schema browser**: flat searchable list (one box, no nested menus); click a table → adds a card to the canvas.
- **Query canvas**: tables as cards; click columns to select; **drag between indexed columns to join** (non-indexed columns aren't draggable — safety is felt, not configured); filters are inline chips; a top bar shows live LIMIT/timeout/cost and a big **Run** button.
- **Results panel**: sticky bottom, virtualized table, row count + guard warnings.
- No dropdown menus — segmented controls, toggle chips, and **Cmd+K command palette**.
- Tailwind: neutral palette + one accent, generous spacing, rounded cards, subtle shadows — modern/sleek.

## Phased milestones

### P0 — Scaffold
Install Rust (rustup), `pnpm create tauri-app@2` (SvelteKit template), add `adapter-static`, Tailwind, app shell + routing.
**Deliverable**: `pnpm tauri dev` boots a styled empty app.

### P1 — Connections & schema (PG + MySQL)
`DatabaseAdapter` trait + PG/MySQL impls, OS keyring secrets, schema+index introspection, connection form (with Test + read-only-role hint), schema browser UI.
**Deliverable**: connect to PG/MySQL and browse tables/indexes.

### P2 — Builder + safety engine
`QuerySpec` IR (Rust + TS mirror), validation against index model, no-lock READ-ONLY execution, mandatory LIMIT + statement timeout, results table, canvas UI with draggable indexed joins + filter chips + Run.
**Deliverable**: build & safely run queries on PG/MySQL.

### P3 — MSSQL + Oracle adapters
tiberius + oracle crate integration, dialect-specific EXPLAIN/compile, Oracle Instant Client packaging notes.
**Deliverable**: all four DBs work in the builder.

### P4 — Polish
saved/recent queries, history, EXPLAIN cost guard UI, sensitive-table blocklist config, theming, Cmd+K palette, empty/loading/error states.

## Risks / notes

- **Oracle Instant Client** must be present on each user's machine at runtime (P3) — document install and consider bundling later.
- **PostgreSQL cannot truly dirty-read**; the UI will be honest that PG gets read-consistent minimal-locking reads, not dirty reads.
- **EXPLAIN cost guard** needs per-dialect plan parsing (PG `EXPLAIN (FORMAT JSON)`, MySQL `EXPLAIN FORMAT=JSON`, MSSQL `SHOWPLAN_XML`, Oracle `PLAN_TABLE`) — real work, scheduled in P4 (P2 enforces limit+timeout first).
- Svelte 5 runes + Tauri 2 are both current-stable; follow official `v2.tauri.app` conventions.
