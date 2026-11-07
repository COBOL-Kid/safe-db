# Changelog

## Unreleased

- Query Builder toolbar stays readable at the 960×600 minimum with the sidebar expanded: connection name and Run wrap as groups instead of stacking character-by-character.
- Filter chips stay fully visible at the default window width with the sidebar expanded, including IN-list wrapping and timestamp values.

## 1.0.1

Desktop app, MCP server (`safe-db-mcp` / `@safe-db/mcp`), and shared query engine. Version is `1.0.1` in `build.gradle.kts`; the MCP fat JAR is `safe-db-mcp-1.0.1-all.jar`; npm packages are stamped 1.0.1 at assemble time.

- History lists queries as they complete in this session, without restarting the app.
- Query Builder timestamp filter values stay readable, and table-card column names are no longer squeezed by type labels at the default canvas width.
- New installs and settings with a missing or unrecognized `theme` value default to light mode; an explicitly saved dark theme is unchanged.
- The SQL tab no longer shows instructional copy when no connection is selected.
- Individual History entries can be deleted without clearing the rest of the list.

## 1.0.0

Desktop app, MCP server (`safe-db-mcp` / `@safe-db/mcp`), and shared query engine. Version is `1.0.0` in `build.gradle.kts`; the MCP fat JAR is `safe-db-mcp-1.0.0-all.jar`; npm packages are stamped 1.0.0 at assemble time.

- Connect timeout of ~10s via driver connect/login/socket properties plus an interruptible deadline. Auth failure and unreachable hosts are MCP error `connection`.
- Every MCP tool failure is JSON `{error, message, warnings}` with `isError=true`. Unknown extra arguments are `invalid_arguments`.
- `run_query` previews and `get_result_rows` pages stop around 32 KiB. Long cells end with `…[truncated]`. `page_truncated` plus `next_offset` when more sample rows remain.
- Risk gate: severity decides (High blocks at Standard, VeryHigh at Flexible). Postgres and MySQL cap EXPLAIN rows under LIMIT. Large join-side lookups score High. FK-without-index is ignored when a Limit keeps examined rows in the Low band. Disabled skips EXPLAIN. Plan-check timeout is a short `execution` error, not confirm.
- Checked-in fixtures include bulk `scan_events` (1M), `wide_rows`, `payload_rows`, and `event_notes`. Load and reload: [docs/testing.md](docs/testing.md).
