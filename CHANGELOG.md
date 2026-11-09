# Changelog

## 1.1.0

Desktop app, MCP server (`safe-db-mcp` / `@safe-db/mcp`), and shared query engine. Version is `1.1.0` in `build.gradle.kts`; the MCP fat JAR is `safe-db-mcp-1.1.0-all.jar`; npm packages are stamped 1.1.0 at assemble time.

- Schema discovery and join guidance: `describe_table` includes `join_eligible` on columns and `incoming_foreign_keys`. Added `search_catalog` for case-insensitive matching across tables, columns, indexes, and foreign keys, and `find_join_paths` for direct neighbors or multi-hop foreign-key and indexed-column paths.
- Dry-run query assessment: added `assess_query` (`SafeDbService.assessQuery` / `assessQueryCore`) to parse, validate, compile, run `EXPLAIN`, and evaluate the risk gate and confirmation requirements without executing the `SELECT` or writing query history.
- In-memory sample analysis: added `pivot_result` and `filter_result_rows` on `result_id`, backed by the `:shared` Explore pivot and worksheet engines, plus `export_result` to write owner-only CSV exports under `{dataDir}/results/exports/`.
- Workspace tools: added `list_saved_queries`, `run_saved_query`, `save_query`, `delete_saved_query` (two-step confirmation), `list_recipes`, `apply_recipe`, and `list_query_history`, all backed by the shared `specToSql` compiler.
- MCP resources and prompts: added `safedb://connections`, `safedb://connections/{connection_id}/tables`, and `safedb://results/{result_id}/summary` resources plus `explore-database` and `analyze-result` prompts.
- CLI enhancements: added `safe-db-mcp connections test [<id>]` and `safe-db-mcp settings` (`show`, `risk-gate`, `default-connection`, `clear-default-connection`, `block-schema`, `unblock-schema`).

## 1.0.1

Desktop app, MCP server (`safe-db-mcp` / `@safe-db/mcp`), and shared query engine. Version is `1.0.1` in `build.gradle.kts`; the MCP fat JAR is `safe-db-mcp-1.0.1-all.jar`; npm packages are stamped 1.0.1 at assemble time.

- History lists queries as they complete in this session, without restarting the app.
- Individual History entries can be deleted without clearing the rest of the list.
- New installs and settings with a missing or unrecognized theme value default to light mode; an explicitly saved dark theme still opens in dark mode.
- The SQL tab no longer shows instructional copy when no connection is selected.
- Query Builder timestamp filter values stay readable, and table-card column names are no longer squeezed by type labels at the default canvas width.
- Query Builder toolbar stays readable at the 960×600 minimum with the sidebar expanded: connection name and Run wrap as groups instead of stacking character-by-character.
- SQL workspace header Run stays readable at the 960×600 minimum: title, connection/schema pickers, and Run wrap as groups instead of stacking the Run label character-by-character.
- Filter chips stay fully visible at the default window width with the sidebar expanded, including IN-list wrapping and timestamp values.
- BETWEEN timestamp fields use the same readable width as single timestamps and keep from/and/to grouped when they wrap.
- Query Builder Query Options and Joins stay reachable at the 960×600 minimum with the sidebar expanded instead of being squeezed out of the canvas.
- Query Builder filters occupy a height-bounded scrolling strip above the canvas so IN chips cannot paint over table cards; Query Options stays on a non-scrolling button that opens a dialog.
- Filter operator dropdowns keep BETWEEN and the arrow readable instead of truncating to "BETW...".
- Table cards reveal column types once the card is wide enough, and the type label is no longer truncated at the reveal width.
- Wrapped filter values keep their remove buttons on the same line, and BETWEEN inputs stay together.
- Table-card column rows expose one accessible label per column toggle.

## 1.0.0

Desktop app, MCP server (`safe-db-mcp` / `@safe-db/mcp`), and shared query engine. Version is `1.0.0` in `build.gradle.kts`; the MCP fat JAR is `safe-db-mcp-1.0.0-all.jar`; npm packages are stamped 1.0.0 at assemble time.

- Connect timeout of ~10s via driver connect/login/socket properties plus an interruptible deadline. Auth failure and unreachable hosts are MCP error `connection`.
- Every MCP tool failure is JSON `{error, message, warnings}` with `isError=true`. Unknown extra arguments are `invalid_arguments`.
- `run_query` previews and `get_result_rows` pages stop around 32 KiB. Long cells end with `…[truncated]`. `page_truncated` plus `next_offset` when more sample rows remain.
- Risk gate: severity decides (High blocks at Standard, VeryHigh at Flexible). Postgres and MySQL cap EXPLAIN rows under LIMIT. Large join-side lookups score High. FK-without-index is ignored when a Limit keeps examined rows in the Low band. Disabled skips EXPLAIN. Plan-check timeout is a short `execution` error, not confirm.
- Checked-in fixtures include bulk `scan_events` (1M), `wide_rows`, `payload_rows`, and `event_notes`. Load and reload: [docs/testing.md](docs/testing.md).
