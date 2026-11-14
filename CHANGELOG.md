# Changelog

## 1.1.0

Desktop app, MCP server (`safe-db-mcp` / `@safe-db/mcp`), and shared query engine. Version is `1.1.0` in `build.gradle.kts`; the MCP fat JAR is `safe-db-mcp-1.1.0-all.jar`; npm packages are stamped 1.1.0 at assemble time.

- Schema discovery and join guidance: `describe_table` includes `join_eligible` on columns and `incoming_foreign_keys`. Added `search_catalog` for case-insensitive matching across tables, columns, indexes, and foreign keys, and `find_join_paths` for direct neighbors or multi-hop foreign-key and indexed-column paths.
- Dry-run query assessment: added `assess_query` (`SafeDbService.assessQuery` / `assessQueryCore`) to parse, validate, compile, run `EXPLAIN`, and evaluate the risk gate and confirmation requirements without executing the `SELECT` or writing query history.
- In-memory sample analysis: added `pivot_result` and `filter_result_rows` on `result_id`, backed by the `:shared` Explore pivot and worksheet engines, plus `export_result` to write owner-only CSV exports under `{dataDir}/exports/`.
- Workspace tools: added `list_saved_queries`, `run_saved_query`, `save_query`, `delete_saved_query` (two-step confirmation), `list_recipes`, `apply_recipe`, and `list_query_history`, all backed by the shared `specToSql` compiler.
- MCP resources and prompts: added `safedb://connections`, `safedb://connections/{connection_id}/tables`, and `safedb://results/{result_id}/summary` resources plus `explore-database` and `analyze-result` prompts.
- CLI enhancements: added `safe-db-mcp connections test [<id>]` and `safe-db-mcp settings` (`show`, `risk-gate`, `default-connection`, `clear-default-connection`, `block-schema`, `unblock-schema`); added `safe-db-mcp recipes list`, `recipes import <absolute path>`, and `recipes export <recipe_id>`.
- Case-insensitive catalog lookup for `describe_table` and `find_join_paths`, with up to 5 suggested tables when a name is unknown and `invalid_arguments` listing both names when a reference is ambiguous.
- Db2 internal catalog tables (`EXPLAIN_*`, `ADVISE_*`) are hidden from `list_tables`, `search_catalog`, `describe_table`, `find_join_paths`, and the tables resource.
- `find_join_paths` accepts `foreign_key_only` to exclude indexed-column candidates.
- Unknown-column errors on `filter_result_rows`, `pivot_result`, and `sorts` list known result columns.
- `run_query`, `run_saved_query`, and `assess_query` receipts include `dialect`; capped samples report `sample_cap_reason` (`sql_limit` or `byte_budget`) and unbounded SQL sets `sample_unbounded`.
- Additive `structuredContent` for object tools and published `outputSchema` for all 16 object tools: `list_tables`, `describe_table`, `search_catalog`, `find_join_paths`, `run_query`, `assess_query`, `get_result_rows`, `summarize_result`, `pivot_result`, `filter_result_rows`, `apply_recipe`, `run_saved_query`, `save_query`, `delete_connection`, `delete_saved_query`, and `export_result`.
- Intentional MCP contract changes: a visualization-only `apply_recipe` (no pivot and no worksheet) is a `validation` error; worksheet receipt column names are source columns, not display labels; `analyze-result` `result_id` is optional.
- Unknown-table suggestions rank visible tables by spelling similarity (edit distance / prefix) instead of alphabetical order.
- `run_query` / `assess_query` risk `reasons` include the same scoring evidence on allowed queries as on blocked queries.
- Empty `run_query` samples keep JDBC column types (instead of `"unknown"`) and omit `sample_unbounded` plus the large-limit warning when the result is known empty.
- Pivot dimension names use table-aware display labels for default, explicit, and recipe pivots (strip `{alias}__`; qualify collisions as `table.column`).
- Pivot measure names use the same table-aware display labels (`Sum amount`, `Max orders.id` on collision).
- Multi-dimension pivots emit one output column per row dimension; totals stay in the totaled dimension instead of collapsing into a single header.
- `filter_result_rows` `members` / `included_keys` matches actual cell values (`view`, `1`) as well as internal `TextCell:view` keys.
- Failure to parse a MySQL JSON plan, including Impossible WHERE / no matching const-table plans, no longer forces `confirmation_required` on ordinary gated SELECTs.
- `setup` / `connections add` refuse to save a connection when `SAFEDB_KEYCHAIN_BACKEND=disabled`; `connections test` then says to unset that override rather than add the connection again.
- Empty `run_query` samples drop the leftover material-payload risk score/reason.
- Duplicate “joins an additional relation” risk reasons collapse to one signal.
- Duplicate “high-row scan of a large table” risk reasons from a self-join collapse to one reason; both sides still count toward the score.
- Multi-dimension pivots no longer emit empty parent-group header rows with a null child and null measures, including when `show_subtotals` is false.
- A mismatched destructive confirmation no longer replaces a still-valid outstanding token.
- `filter_result_rows` documents that `members` takes `included_keys`, not `value`.
- `runQueryCore` fails closed with `QueryError.RiskGate` (`risk_gate`) when risk assessment is still pending (`AssessmentPending`).
- `LIKE` / `ILIKE` / `NOT LIKE ... ESCAPE '!'` round-trips through SQL parsing, `FilterSpec`, and `specToSql` / saved queries (and clears `likeEscape` when changing columns or operators in the Query Builder).

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
