# MCP server

Stdio MCP server so agents can inspect schema and run a single `SELECT` through the same parser, validator, compiler, risk gate, and row/time caps as the desktop app. It maps tools onto [`SafeDbService`](../shared/src/main/kotlin/com/safedb/service/SafeDbService.kt). It does not reimplement JDBC, validation, or gating.

Install with npm. The package bundles a Temurin 25 jlink runtime, so the machine does not need Java:

```bash
npx -y @safe-db/mcp setup --dialect mysql --database app --username readonly --password-file /absolute/path
```

MCP client config:

```json
{
  "mcpServers": {
    "safe-db": {
      "command": "npx",
      "args": ["-y", "@safe-db/mcp"]
    }
  }
}
```

Developer Gradle commands (`:mcp:run`, `:mcp:shadowJar`, `:mcp:assembleNpm`) stay in [testing.md](testing.md). Query-engine contracts stay in [query-engine.md](query-engine.md).

## Platforms

macOS arm64 (not x64), Windows x64, and glibc Linux (x64 and arm64). Alpine/musl is not supported. `@safe-db/mcp` bundles a jlink image of Eclipse Temurin 25, so Java does not need to be installed. The desktop app still rejects Linux in [`Main.kt`](../src/main/kotlin/com/safedb/Main.kt) before Compose.

MCP does not load a desktop `--launch-profile`. Transport is per connection (`--transport` on setup). Oracle TCPS still needs `--oracle-wallet`.

## Data directory and credentials

Non-secrets live in `connections.json` via [`ConfigStore`](../shared/src/main/kotlin/com/safedb/store/ConfigStore.kt). Passwords never go in that file, in MCP client config, in tool arguments, or in environment variables.

[`McpDataDirectory`](../mcp/src/main/kotlin/com/safedb/mcp/McpDataDirectory.kt) chooses the store:

| Platform | Data directory | Passwords |
| --- | --- | --- |
| Windows | `%APPDATA%\com.safedb.app` (same as the desktop app) | Windows Credential Manager, same fingerprint as the app |
| macOS | `~/Library/Application Support/com.safedb.mcp` | Owner-only files under `{dataDir}/credentials/`. Does not open Keychain and does not reuse desktop-app passwords. |
| Linux | `$XDG_DATA_HOME/com.safedb.mcp`, or `~/.local/share/com.safedb.mcp` | Same file store as macOS |

On Windows, `list_connections` is the same list the UI uses. On macOS, MCP connections are a separate store; do not assume the agent can use app Keychain items.

`SAFEDB_KEYCHAIN_BACKEND` remains a store-selection switch (including `disabled` for tests). It is not how users pass connection details.

`query_risk_gate` and `blocked_schemas` come from `settings.json` in that same data directory (default gate `Standard`). They are configurable via `safe-db-mcp settings` or, on Windows, the desktop Settings screen; there is still no MCP tool to change them. On macOS and Linux, MCP has its own `settings.json`.

## CLI

The binary is `safe-db-mcp`. With no arguments it speaks MCP JSON-RPC on stdin/stdout (stdout is reserved for protocol; logs go to stderr). Stdout is authoritative protocol output; stderr may include expected SDK `ERROR` stack traces (including `-32002` resource-not-found) during normal calls.

Add connections with the human CLI, not with tools. Passwords are never flags.

```text
safe-db-mcp
safe-db-mcp setup
safe-db-mcp connections add [options]
safe-db-mcp connections list
safe-db-mcp connections delete <id>
safe-db-mcp connections test [<id>]
safe-db-mcp settings
safe-db-mcp settings risk-gate <cautious|standard|flexible|disabled>
safe-db-mcp settings default-connection <id> [--default-schema <schema>]
safe-db-mcp settings clear-default-connection
safe-db-mcp settings block-schema <schema>
safe-db-mcp settings unblock-schema <schema>
safe-db-mcp recipes list
safe-db-mcp recipes import /absolute/path/recipe.json
safe-db-mcp recipes export <recipe_id>
```

Example:

```text
safe-db-mcp setup --dialect mysql --database app --username readonly --password-file /absolute/path
```

`setup` and `connections add` are the same command. On a TTY they prompt for missing fields and a no-echo password; non-interactive use requires `--password-file`. They test the connection (same ~10s driver connect timeout as the desktop app), then call `createConnection`.

| Flag | Notes |
| --- | --- |
| `--name` | Defaults to the database name |
| `--dialect` | `postgres`, `mysql`, `mssql`, `oracle`, or `db2` |
| `--host` | Defaults to `localhost` |
| `--port` | Dialect default when omitted |
| `--database` | Required |
| `--username` | Required |
| `--password-file` | Absolute path; one UTF-8 line; owner-only (`0600`). Setup copies the secret into the credential store. |
| `--transport` | `disabled`, `encrypt-only`, `verify-ca`, or `verify-identity`. Off a TTY, defaults from host location. Db2 honors `disabled` and `verify-identity`; other modes connect with `sslConnection=true` and JCC BASIC hostname checks. |
| `--oracle-wallet` | Absolute path; required for Oracle when transport is not `disabled`. Db2 uses JVM trust and needs no wallet |

`--password` and `-p` are rejected. Do not put host, user, database, or password in environment variables.

`connections test [<id>]` tests one saved connection, or every saved connection when `<id>` is omitted, with the same ~10s driver connect timeout as `setup`. It prints `OK` or `FAIL` per connection and never prints a password.

`safe-db-mcp settings` with no subcommand is `settings show`. It prints `query_risk_gate`, `default_connection_id`, `default_schema`, and `blocked_schemas`. `risk-gate` writes the stored gate. `default-connection` also stores the optional `--default-schema`; `clear-default-connection` clears both. `block-schema` appends a schema and `unblock-schema` removes it.

`recipes list` prints `id  name  defaultMode  N fields  updatedAt` per recipe, or `No recipes.` `recipes import` requires an absolute regular file (UTF-8, at most 1 MiB); the store validates and versions it, renames duplicates, and assigns a new id. `recipes export <recipe_id>` prints the recipe JSON to stdout. Recipes contain no credentials.

## Tools

Progressive catalog, then a gated query. Do not expect the full [`Schema`](../shared/src/main/kotlin/com/safedb/model/Schema.kt) or full [`QueryResult`](../shared/src/main/kotlin/com/safedb/model/Ir.kt) as a tool payload. No tool accepts a password or a URL with embedded credentials.

| Tool | Behavior |
| --- | --- |
| `list_connections` | `id`, `name`, `dialect`, `database`. No secrets. `readOnlyHint`. |
| `delete_connection` | Delete a saved connection by `connection_id`. First call returns `confirmation_required` with reasons and a confirmation object; show the user, then retry with that object. Do not auto-confirm or invent the object. Invalidates the schema cache for that id. `destructiveHint`. |
| `list_tables` | JSON array of `schema`, `name`, `qualified_name`, `size_class`, `column_count`. Cached `getSchema` (5 min TTL, optional `refresh=true`). Honors `blocked_schemas` and built-in system catalogs; on Db2 also hides `EXPLAIN_*` / `ADVISE_*` internal catalog tables. `readOnlyHint`. |
| `describe_table` | `connection_id` + `schema` + `table` from `list_tables`. Exact match first, then case-insensitive; Oracle/Db2 catalog names are often uppercase. Columns (`name`, `data_type`, `nullable`, `join_eligible`), indexes, `foreign_keys`, and `incoming_foreign_keys` (source table and columns, cardinality, optionality). Same cache. Unknown → `not_found` with up to 5 suggested tables; a case-only ambiguity → `invalid_arguments` listing both names. Blocked/system schemas stay `not_found`. `readOnlyHint`. |
| `search_catalog` | Case-insensitive substring search of the cached catalog across table and schema names, column names and types, index names and key columns, and foreign-key names, columns, and referenced tables. `limit` defaults to `SEARCH_CATALOG_DEFAULT_LIMIT` (25) and caps at `SEARCH_CATALOG_MAX_LIMIT` (50). Returns `match_count` (before the limit), `truncated`, and per-table `matched_columns`, `matched_indexes`, `matched_foreign_keys`. Honors `blocked_schemas`; same cache and `refresh`. `readOnlyHint`. |
| `find_join_paths` | Without `to_table`, returns `{from_table, neighbors}`: direct neighbors of `from_table` with `foreign_key` or `indexed_columns` edges, column pairs, an `on_clause`, and FK cardinality and optionality. With `to_table`, returns `{from_table, to_table, paths}`: up to 10 paths of at most `max_hops` (default `FIND_JOIN_PATHS_DEFAULT_HOPS` (2), max `FIND_JOIN_PATHS_MAX_HOPS` (3)). Optional `foreign_key_only` (default false) excludes `indexed_columns` candidates. Tables accept `schema.table` or `table`; unqualified names use `default_schema`, the saved default schema for this connection, or a unique visible table. Honors `blocked_schemas`; same cache and `refresh`. `readOnlyHint`. |
| `assess_query` | Dry-run one `SELECT` on a saved connection: parse, validate, compile, run `EXPLAIN`, and evaluate the risk gate and confirmation requirements without executing the `SELECT` or writing query history. Returns `dialect`, `columns`, `compiled_sql`, `parameter_count`, `warnings`, a slim `risk` summary, and `confirmation_required` with reasons when confirmation would be required. `readOnlyHint`. |
| `run_query` | `connection_id` and `sql`. Optional `default_schema`: schema used when a table name has no prefix. Copy `list_tables.schema` for this connection (MySQL: usually the database name). Omit when every table is already `schema.table`. Not filled from `connection.database`. If the argument is omitted, [`settingsDefaultSchema`](../mcp/src/main/kotlin/com/safedb/mcp/QueryTools.kt) uses `default_schema` from `settings.json` only when `default_connection_id` is this connection; any other connection does not inherit it. An explicit `default_schema` argument always wins. Accepted SQL matches the desktop app ([query-engine.md](query-engine.md)); compiled columns are `table__column`. Parses SQL to `QuerySpec`, then `SafeDbService.runQuery` (fresh introspect on execute). Receipt, not the grid, including `dialect` and the sample-cap fields. No tool annotations. |
| `get_result_rows` | Page the in-memory sample for a `result_id`. `offset` default 0 (negative clamps to 0). `limit` default 50, hard cap 50; 0 or negative is `invalid_arguments`. Pages also stop around 32 KiB; long cell values are truncated with `…[truncated]`. `page_truncated` plus `next_offset` when more sample rows remain. Unknown or expired → `not_found`. `readOnlyHint`. |
| `summarize_result` | Per-column stats on that same sample. Echoes `sample_capped` when the original fetch was capped. Unknown or expired → `not_found`. `readOnlyHint`. |
| `pivot_result` | Pivot the in-memory sample by `result_id` with `row_dimensions`, `column_dimensions`, `measures`, `filters`, and `sort`; the same `:shared` Explore pivot engine as the desktop app. With no dimensions or measures it uses a default row count pivot, which adds a `Total` row; its measure column keeps the default `Count` name. A measure's `label` names the output column (defaults to the function label); `alias` is for formulas and `sort.measure_alias`. Use receipt column names verbatim (dialect casing). Returns pivoted columns, `row_count`, `pivot_truncated`, warnings, and at most 50 rows / 32 KiB. Unknown or expired → `not_found`. `readOnlyHint`. |
| `filter_result_rows` | Filter, sort, project, and page the in-memory sample by `result_id` using the `:shared` worksheet engine. `filters` support comparison ops incl. `between`, `is_null`, and `members`; `sorts` accept `column` and `asc`/`desc`; `columns` limits the projection. Use receipt column names verbatim (dialect casing); an unknown column reports the known columns. `offset` defaults to 0 and `limit` defaults to and caps at 50. `matched_row_count` is after filtering; `sample_row_count` is the stored sample size. Unknown or expired → `not_found`. `readOnlyHint`. |
| `export_result` | Write the in-memory sample for a `result_id` to an owner-only CSV under `{dataDir}/results/exports/`. Optional simple `filename` (letters, digits, dot, underscore, dash; `.csv` appended when missing). Returns `result_id`, `file_path`, `row_count`, `bytes`, and `sample_capped`. Unknown or expired → `not_found`. |
| `list_saved_queries` | Saved queries from the MCP store as `id`, `name`, `connection_id`, `sql`, `tables`, and `created_at`. Optional `connection_id` filter. `readOnlyHint`. |
| `run_saved_query` | Run a saved query by `query_id` on its saved connection. Accepts the same confirmation object as `run_query` and returns a `run_query`-style receipt (including `dialect` and the sample-cap fields) with `result_id`. Unknown `query_id` → `not_found`. |
| `save_query` | Validate and save a `SELECT` for a connection with `name`, `sql`, and optional `default_schema`. Rejects the same SQL as `run_query`. Returns the saved query summary. |
| `delete_saved_query` | Delete a saved query by `query_id`. First call returns `confirmation_required`; show the reasons, then retry with the returned confirmation object. Do not auto-confirm or invent the object. `destructiveHint`. |
| `list_recipes` | List Explore recipes from the MCP store as `id`, `name`, `description`, `default_mode`, `required_fields`, and `updated_at`. `readOnlyHint`. |
| `apply_recipe` | Apply a saved Explore recipe (`recipe_id` from `list_recipes`) to the in-memory sample for a `result_id`. `mode` is `pivot` or `worksheet` (defaults to the recipe); `field_mapping` maps required recipe columns to result columns. Returns the pivoted or worksheet columns, `row_count`, truncation flag, warnings, and rows. Unknown `result_id` or `recipe_id` → `not_found`. `readOnlyHint`. |
| `list_query_history` | Recent query history from the MCP store as `id`, `connection_id`, `connection_name`, `sql`, `row_count`, `warnings`, optional `error`, `timestamp`, and optional risk fields. Optional `connection_id` filter. `limit` defaults to `HISTORY_DEFAULT_LIMIT` (20) and caps at `HISTORY_MAX_LIMIT` (50); 0 or negative is `invalid_arguments`. `readOnlyHint`. |

Initialize includes a short `instructions` string: add connections with the CLI; start with `list_connections`; pass `id` as `connection_id`; pass `default_schema` from `list_tables` for unqualified SQL; page with `result_id`; `delete_connection` requires an echoed confirmation; never send passwords. Tool list capability is `listChanged: false`.

Page with `get_result_rows`, summarize with `summarize_result`, pivot with `pivot_result`, filter with `filter_result_rows`, or export with `export_result`. Results expire after 30 minutes (16 entries, 32 MiB).

### Casing, catalog resolution, and envelopes

Oracle and Db2 return uppercase catalog names. Copy `list_tables` values exactly; lookups are exact-match first, then case-insensitive. Two quoted identifiers that differ only by case make a reference ambiguous: `invalid_arguments` with both names (`Table reference is ambiguous: public.Foo, public.FOO`). An unknown table is `not_found` with up to 5 visible suggestions (`Table not found. Suggested tables: public.customers, public.orders`); blocked and system schemas stay `not_found` and are not suggested.

Result columns use dialect casing (`customers__id` vs `CUSTOMERS__ID`). Pass receipt names verbatim to `filter_result_rows`, `pivot_result`, and `sorts`. Unknown-column errors list the known columns, sorted and capped at 8 (`Unknown column 'customers__id'. Known columns: CUSTOMERS__EMAIL, CUSTOMERS__ID`).

`list_tables` returns a JSON array; `describe_table`, `run_query`, and `find_join_paths` return objects. `find_join_paths` is `{from_table, neighbors}` without `to_table` and `{from_table, to_table, paths}` with it:

```json
{ "from_table": "public.orders", "neighbors": [
  { "from_schema": "public", "from_table": "orders", "to_schema": "public", "to_table": "customers",
    "kind": "foreign_key", "columns": [{ "left_column": "customer_id", "right_column": "id" }],
    "on_clause": "public.orders.customer_id = public.customers.id", "cardinality": "ManyToOne" } ] }
```

```json
{ "from_table": "public.orders", "to_table": "public.customers",
  "paths": [{ "hops": 1, "steps": [{ "from_table": "orders", "to_table": "customers", "kind": "foreign_key" }] }] }
```

The default `pivot_result` adds a `Total` row and keeps the `Count` measure name:

```json
{ "columns": [{ "name": "region", "data_type": "text" }, { "name": "Count", "data_type": "int8" }],
  "row_count": 3, "pivot_truncated": false, "warnings": [],
  "rows": [{ "region": "EU", "Count": 2 }, { "region": "US", "Count": 5 }, { "region": "Total", "Count": 7 }] }
```

Db2 `EXPLAIN_*` / `ADVISE_*` internal tables are hidden from `list_tables`, `search_catalog`, `describe_table`, `find_join_paths`, and the tables resource. They stay queryable through `run_query` when named explicitly; there is no `include_internal` flag.

Object tools also return `structuredContent` equal to the parsed text object: `run_query`, `run_saved_query`, `assess_query`, `describe_table`, `find_join_paths`, `get_result_rows`, `summarize_result`, `pivot_result`, `filter_result_rows`, and `export_result`. Array tools stay text-only. `outputSchema` is published for `run_query`, `assess_query`, `describe_table`, and `find_join_paths`.

Constants (change the constant and update this page in the same change):

| Constant | Value | File |
| --- | --- | --- |
| `SCHEMA_CACHE_TTL_MS` | 5 minutes | [`SchemaCache.kt`](../mcp/src/main/kotlin/com/safedb/mcp/SchemaCache.kt) |
| `PREVIEW_ROW_LIMIT` | 10 rows | [`ResultPreview.kt`](../mcp/src/main/kotlin/com/safedb/mcp/ResultPreview.kt) |
| `GET_RESULT_ROWS_MAX` | 50 rows | [`ResultPreview.kt`](../mcp/src/main/kotlin/com/safedb/mcp/ResultPreview.kt) |
| `MCP_PAYLOAD_MAX_BYTES` | 32 KiB | [`ResultPreview.kt`](../mcp/src/main/kotlin/com/safedb/mcp/ResultPreview.kt) |
| `MCP_CELL_MAX_BYTES` | 512 bytes, then `…[truncated]` | [`ResultPreview.kt`](../mcp/src/main/kotlin/com/safedb/mcp/ResultPreview.kt) |
| `RESULT_STORE_TTL_MS` | 30 minutes | [`ResultStore.kt`](../mcp/src/main/kotlin/com/safedb/mcp/ResultStore.kt) |
| `RESULT_STORE_MAX_ENTRIES` | 16 | [`ResultStore.kt`](../mcp/src/main/kotlin/com/safedb/mcp/ResultStore.kt) |
| `RESULT_STORE_MAX_BYTES` | 32 MiB | [`ResultStore.kt`](../mcp/src/main/kotlin/com/safedb/mcp/ResultStore.kt) |
| `DISTINCT_VALUE_LIMIT` | 8 values | [`ResultSummary.kt`](../mcp/src/main/kotlin/com/safedb/mcp/ResultSummary.kt) |
| `SEARCH_CATALOG_DEFAULT_LIMIT` | 25 | [`SchemaTools.kt`](../mcp/src/main/kotlin/com/safedb/mcp/SchemaTools.kt) |
| `SEARCH_CATALOG_MAX_LIMIT` | 50 | [`SchemaTools.kt`](../mcp/src/main/kotlin/com/safedb/mcp/SchemaTools.kt) |
| `FIND_JOIN_PATHS_DEFAULT_HOPS` | 2 | [`SchemaTools.kt`](../mcp/src/main/kotlin/com/safedb/mcp/SchemaTools.kt) |
| `FIND_JOIN_PATHS_MAX_HOPS` | 3 | [`SchemaTools.kt`](../mcp/src/main/kotlin/com/safedb/mcp/SchemaTools.kt) |
| `HISTORY_DEFAULT_LIMIT` | 20 | [`WorkspaceTools.kt`](../mcp/src/main/kotlin/com/safedb/mcp/WorkspaceTools.kt) |
| `HISTORY_MAX_LIMIT` | 50 | [`WorkspaceTools.kt`](../mcp/src/main/kotlin/com/safedb/mcp/WorkspaceTools.kt) |

List/describe cache a `Schema` per connection in the MCP process. `run_query` does not use that cache for the risk gate: `runQuery` introspects fresh schema before `runQueryCore`.

## Resources and prompts

Resources return the same JSON shapes as the tools and share the schema cache and `blocked_schemas`:

| Resource | Contents |
| --- | --- |
| `safedb://connections` | Saved connections (`id`, `name`, `dialect`, `database`). No secrets. |
| `safedb://connections/{connection_id}/tables` | Visible tables for one connection (`schema`, `name`, `qualified_name`, `column_count`). Honors `blocked_schemas`; omits Db2 `EXPLAIN_*` / `ADVISE_*` internal tables. Unknown connection → MCP resource-not-found (`-32002`). |
| `safedb://results/{result_id}/summary` | Column summary of the in-memory sample: `row_count`, `sample_capped`, and per-column `null_count`, min/max, and distinct values. Unknown or expired `result_id` → MCP resource-not-found (`-32002`). |

Prompts provide a short guided workflow:

| Prompt | Arguments | Behavior |
| --- | --- | --- |
| `explore-database` | Optional `connection_id` | List connections and tables, describe tables, search the catalog, find join paths, assess a `SELECT`, then run it and page or summarize the sample. |
| `analyze-result` | Required `result_id` | Summarize, pivot, filter, or apply a saved recipe to a fetched sample, then export it to CSV. |

Resource and prompt list capability is `listChanged: false`; resource subscriptions are not supported.

## `run_query` receipt

A successful call returns JSON with:

- `dialect` (`Postgres`, `MySql`, `Mssql`, `Oracle`, `Db2`).
- `columns` (`name`, `data_type`). Names are `table__column` (the compiled result alias) in dialect casing, e.g. `customers__id` or `CUSTOMERS__ID`.
- `row_count`, `preview_truncated`, `warnings`
- `sample_capped` when the fetched sample hit a caller `LIMIT` or the encoded-byte budget (omitted when false). `sample_cap_reason` is then `sql_limit` or `byte_budget`. `sample_unbounded` is true when the original SQL omitted `LIMIT` (omitted when false). A row-limit stop names that `LIMIT` and says to raise it or add filters. A byte-budget stop, including a query that omitted `LIMIT`, says to add filters or select fewer columns. Raising `LIMIT` does not read past the budget
- `risk`: `state`, `severity`, `score`, `effective_gate`, `reasons`, `plan_status`
- `preview`: about 10 flattened rows (`null` / bool / number / string; binary as base64), also stopped at the 32 KiB payload budget. Long cells end with `…[truncated]`. Not tagged `ResultCell` objects.
- `result_id` for later paging and summary

Read `sample_capped`, `sample_cap_reason`, and `sample_unbounded` instead of string-matching warning text.

```json
{
  "dialect": "Postgres",
  "columns": [{ "name": "customers__id", "data_type": "int4" }],
  "row_count": 1,
  "sample_capped": true,
  "sample_cap_reason": "sql_limit",
  "warnings": ["This sample is the full fetch (LIMIT 100); paging does not get more table rows. Raise LIMIT or add filters."],
  "risk": { "state": "Allowed", "severity": "Minimal", "score": 0, "effective_gate": "Standard", "reasons": [], "plan_status": "Available" },
  "preview": [{ "customers__id": 1 }],
  "preview_truncated": false,
  "result_id": "res_01"
}
```

`sample_capped` is the engine byte or caller `LIMIT` cap from [query-engine.md](query-engine.md). `preview_truncated` is true when the preview is shorter than `row_count` or cells were truncated to the MCP cell budget. A positive SQL limit is kept up to the maximum 32-bit integer; a larger whole number clamps. Omitting `LIMIT` is the same as the builder's ALL choice and emits no SQL row limit. The client reads with a statement fetch size and stops at `MAX_RESULT_BYTES` (10 MiB encoded). The receipt does not include the full sample.

Accepted and rejected SQL are the same as the desktop app. Unknown extra arguments, including a stray `spec`, are `invalid_arguments`; agents send `sql`.

### Errors

Every tool error is JSON `{error, message, warnings}` with `isError=true`. Extra keys `risk` / `reasons` / `confirmation` are omitted when unused (never sent as `null`):

| `error` | What to do |
| --- | --- |
| `invalid_arguments` | Missing required fields, unknown extra arguments (the message names the argument), wrong JSON types (`sql` must be a string, `refresh` a boolean, `limit` an integer 1–50), or a malformed `confirmation` object. |
| `not_found` | Unknown tool, connection, table, saved query, recipe, or expired/unknown `result_id`. |
| `parse` | Fix `sql`. Unqualified tables: pass `default_schema` from `list_tables.schema`, or qualify as `schema.table`. Aggregates: run the base query, then `summarize_result`. |
| `validation` / `compilation` / `execution` | Same meanings as [`QueryError`](../shared/src/main/kotlin/com/safedb/query/QueryCore.kt). Plan-check timeout or connection/I/O failure is `execution` with a short message (no driver dump), not a confirm prompt. |
| `risk_gate` | Includes a slim `risk` summary. Rewrite the query. Do not retry the same query. |
| `confirmation_required` | Includes `reasons` and a `confirmation` object. Show the reasons to the user, then retry the same tool (`run_query`, `run_saved_query`, `delete_connection`, or `delete_saved_query`) with that object. Do not auto-confirm. Do not invent the object. Confirmations are bound to the SQL and connection; tampered objects are rejected. A plan-check timeout is not this error. |
| `connection` | Auth failure or the host could not be reached (including connect timeout). |
| `internal` | Unexpected store or runtime failure. |

## Result store

[`ResultStore`](../mcp/src/main/kotlin/com/safedb/mcp/ResultStore.kt) keeps the fetched `QueryResult` in process under `result_id` (30 min TTL, last 16, 32 MiB JSONL cap, access-order LRU). That is the MCP analogue of the Explore sample.

On put it also writes `{dataDir}/results/{result_id}.jsonl` for LRU accounting: one flattened object per row, owner-only. JSONL write failure does not fail the query: `result_id` and the in-memory sample remain. The path is not in the receipt. Evict/expire deletes the file. The results directory is wiped when the store is constructed (process start).

`get_result_rows` pages the in-memory sample, not the file. A page stops at 50 rows or about 32 KiB, whichever comes first. Long cell values are truncated with `…[truncated]`. `page_truncated` is true when more of the in-memory sample remains; `next_offset` is then the offset to request next (omitted on the last page). It does not echo `sample_capped`. `summarize_result` reports per-column `null_count`, min/max (int/float numeric, text lexicographic, bool; omitted for binary and when there are no comparable values), and up to 8 distinct flattened values (`distinct_truncated` if more). `summarize_result` echoes `sample_capped` (omitted when false) from the original query.

## Not in 1.1.0

- Sharing macOS Keychain items with the signed desktop app
- MCP tools that add or update a connection
- MCP tools that change settings
- Environment variables for host, user, database, or password
- Dumping full `Schema` or full `QueryResult` into tool JSON
- MCP elicitation
- Desktop Conveyor / MSI / DMG as the MCP install path
