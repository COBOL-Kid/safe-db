# Changelog

## 1.1.4

Desktop app, MCP server (`safe-db-mcp` / `@safe-db/mcp`), and shared query engine. Version is `1.1.4` in `build.gradle.kts`; the MCP fat JAR is `safe-db-mcp-1.1.4-all.jar`; npm packages are stamped 1.1.4 at assemble time.

- Refreshed the app icon and added a Liquid Glass icon (`Assets.car` / `CFBundleIconName = SafeDB`) for macOS 26+ ([#120](https://github.com/COBOL-Kid/safe-db/pull/120)).
- The sidebar footer title now shows the connection status label to match its indicator dot ([#119](https://github.com/COBOL-Kid/safe-db/pull/119)).
- Explore Visualization: added an opt-in secondary value axis (`Right axis` / `Top axis`) for multi-measure non-stacked Bar and Line charts, with `(left)`/`(right)` or `(bottom)`/`(top)` legend labels, a scale-difference warning when measures differ by 20x or more, automatic normalization back to a single primary axis when the primary measure is removed, and recipe persistence.
- Explore Visualization and HTML/PNG/CSV export: aligned dark-mode bar value-label contrast between in-app charts and HTML exports, stepped bar value-label font size down uniformly (`12sp` -> `11sp` -> `10sp`) on narrower grouped bars, grouped Auto number formatting, improved histogram bin sizing and bucket ordering for date/binned series, filled contiguous empty histogram bins (up to 500 bins, with a warning if wider) and used a uniform stride for evenly spaced horizontal category axis labels, and shared chart layout, label, and legend policy between in-app charts and HTML reports.
- Explore window and drill-down: tracked sample runs so the stale-sample banner appears and refreshes reliably after new Builder or SQL runs, brought an already-open or minimized Explore window to the front when re-opened, hid Pinned filter state in Visualization mode, and moved sample-truncation context into the `Source rows` drill-down subtitle instead of showing a `Truncated` badge on drill-down subsets.
- Save and open file choosers for HTML, CSV, PNG, and Explore recipes now remember the last chosen directory, sanitize suggested filenames, and apply matching file-extension filters.
- Connections and Command Palette: added `Edit connection: <name>` actions to the Command Palette, anchored Connection Form status banners above the footer, updated missing-password guidance to direct users to edit the connection (and testing a connection with a missing password switches Edit Connection directly into focused password entry), and updated connection reachability immediately after a successful query run.
- SQL Editor: autocomplete popup closes when the editor loses focus or runs via `Ctrl/Cmd+Enter`, and stays non-focusable so toolbar buttons such as Run fire on the first click.

## 1.1.3

Desktop app, MCP server (`safe-db-mcp` / `@safe-db/mcp`), and shared query engine. Version is `1.1.3` in `build.gradle.kts`; the MCP fat JAR is `safe-db-mcp-1.1.3-all.jar`; npm packages are stamped 1.1.3 at assemble time.

- SQL Server `SET SHOWPLAN_XML ON` is restored to `OFF` even when enabling SHOWPLAN fails. If that restore fails, the pooled connection is evicted instead of being returned with SHOWPLAN left on.
- SQL Server lock timeout (error 1222) is still reported as "Query timed out after …ms", but it no longer closes the whole connection pool. The pool is recycled only for a dead connection (a JDBC driver timeout, a socket timeout, or a closed connection).
- MCP `list_connections`, `list_saved_queries`, and `list_recipes` now return objects (`{connections|saved_queries|recipes: [...], total_count, truncated, next_offset?}`) instead of top-level arrays, and publish `outputSchema` (19 object tools; `list_query_history` stays a text-only array). The `safedb://connections` resource changed the same way, to `{connections, total_count, truncated}`. Clients that read these results as arrays must read the named field instead.
- MCP `list_connections`, `list_tables`, `find_join_paths` (without `to_table`), `list_saved_queries`, and `list_recipes` accept optional `offset` and `limit` (default 100, cap 200) and report `total_count`, `truncated`, and `next_offset`. The `safedb://connections` and `safedb://connections/{connection_id}/tables` resources return at most 200 entries with `total_count` and `truncated`.
- MCP `find_join_paths` ignores `offset` and `limit` when `to_table` is set, including invalid values, and still returns `{from_table, to_table, paths}`. Those arguments still page neighbors and reject invalid values when `to_table` is omitted.
- MCP `apply_recipe` rejects a `field_mapping` key that is not a recipe required field, or a value that is not a result column, with `invalid_arguments` instead of silently falling back to auto-matching. A type-incompatible mapping still falls back, and Oracle/Db2 recipes saved from lowercase columns need `field_mapping` because auto-matching compares column names case-sensitively.
- MCP `assess_query` reports `readOnlyHint: false` and says so in its description: unless the risk gate is `Disabled`, Oracle and Db2 `EXPLAIN` write and delete plan-table rows.
- Unexpected MCP tool failures return a generic `<tool> failed` message (for example `run_query failed`) with no internal details; the error class is logged on stderr.
- A result whose JSONL cache file fails to write still counts toward the result store's 32 MiB cap, using an estimated in-memory size.
- Saved connections, saved queries, query history, and recipes are guarded by cross-process file locks (`*.lock` files in the data directory), not just in-process locks, so separate processes sharing a store serialize their reads and writes.
- `safe-db-mcp connections delete` says when the saved queries and query history it removed live in the shared store used by the desktop app.
- SQL Server queries run with JDBC `autoCommit = false` at `READ_UNCOMMITTED`, restore `LOCK_TIMEOUT` to `-1` afterward, and discard the pooled connection when that restore fails. JDBC query, socket, and lock timeouts (error 1222) are reported as "Query timed out after …ms".
- The desktop sidebar shows "Checking connection…" while it probes a newly selected connection instead of flashing Not connected, and skips reachability probes only while no Safe-DB window (main or Explore) is active.
- Query Builder says "Running query · Run unavailable" while a run is in progress with Risk gate Off, instead of "Assessing query risk · Run unavailable".
- Editing a query, switching connections, or otherwise discarding an in-flight desktop run no longer writes that run to Query History. MCP runs and ordinary completed or failed runs still record as before.
- Editing a query while a desktop run is in progress shows "Query edited while a run was in progress; the in-flight result will be discarded."
- Loading a query whose filters are all dropped shows "all filters were dropped (query is now unfiltered)" instead of "some filters were dropped".
- Escape cancels an armed click-to-join on the Query Builder canvas.
- MCP `run_query` and `assess_query` read the schema once per call instead of twice.

## 1.1.2

Desktop app, MCP server (`safe-db-mcp` / `@safe-db/mcp`), and shared query engine. Version is `1.1.2` in `build.gradle.kts`; the MCP fat JAR is `safe-db-mcp-1.1.2-all.jar`; npm packages are stamped 1.1.2 at assemble time.

- SQL Server queries with bound parameters read their optimizer cost from the `SELECT` statement, not the leading `DECLARE` assignment, so they no longer always require confirmation with "The query plan did not provide a valid optimizer cost".
- SQL Server query timeouts surface as "Query timed out after …ms" instead of "Connection is closed".
- `summarize_result` min/max compares integer, decimal, and numeric-typed text columns (including Oracle `NUMBER`, `float`, and `money`) numerically instead of lexicographically; values that are not numbers are skipped, like float `NaN`.
- MCP `delete_connection` and `safe-db-mcp connections delete` also delete the connection's saved queries and query history. The tool's confirmation reasons say so. The desktop app is unchanged.
- Connection failures caused by a TLS handshake error now hint to check the transport setting.
- Expected `-32002` resource-not-found errors no longer print SDK stack traces on stderr.

## 1.1.1

Desktop app, MCP server (`safe-db-mcp` / `@safe-db/mcp`), and shared query engine. Version is `1.1.1` in `build.gradle.kts`; the MCP fat JAR is `safe-db-mcp-1.1.1-all.jar`; npm packages are stamped 1.1.1 at assemble time.

- Settings query timeout is configurable from 1 to 120 seconds, default 10; it applies to the next desktop or MCP query run and its plan check; connect and schema introspection stay fixed. `safe-db-mcp settings` prints `query_timeout_seconds`; `safe-db-mcp settings timeout <seconds>` writes it.
- The sidebar shows a reachability indicator (Connected / Not connected) for the selected connection, and the Connections screen labels that connection Selected instead of Active.
- Updated the bundled Temurin 25 jlink runtime for `@safe-db/mcp` to `jdk-25.0.5+8` and updated runtime dependencies (`mssql-jdbc` 13.6.2, `kotlinx-serialization-json` 1.13.0) ([#112](https://github.com/COBOL-Kid/safe-db/pull/112)).
- Refreshed README and documentation links to `safe-db.dev` and trimmed `"single SELECT"` wording from MCP tool descriptions and CTE error copy ([#110](https://github.com/COBOL-Kid/safe-db/pull/110)).

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
