# MCP server

Stdio MCP server so agents can inspect schema and run a single `SELECT` through the same parser, validator, compiler, risk gate, and row/time caps as the desktop app. It maps tools onto [`SafeDbService`](../shared/src/main/kotlin/com/safedb/service/SafeDbService.kt). It does not reimplement JDBC, validation, or gating.

The server is intended to ship as an npm package. That package is not published yet; this page covers behavior, connections, tools, and data paths once a client is launching the `safe-db-mcp` binary. Client install and download steps will land here when it ships.

Developer Gradle commands (`:mcp:run`, `:mcp:shadowJar`) stay in [testing.md](testing.md). Query-engine contracts stay in [query-engine.md](query-engine.md).

## Platforms

macOS, Windows, and Linux. The desktop app still rejects Linux in [`Main.kt`](../src/main/kotlin/com/safedb/Main.kt) before Compose.

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

`query_risk_gate` and `blocked_schemas` come from `settings.json` in that same data directory (default gate `Standard`). There is no MCP tool to change them. On Windows, the desktop Settings screen writes that file; on macOS and Linux, MCP has its own `settings.json`.

## CLI

The binary is `safe-db-mcp`. With no arguments it speaks MCP JSON-RPC on stdin/stdout (stdout is reserved for protocol; logs go to stderr).

Add connections with the human CLI, not with tools. Passwords are never flags.

```text
safe-db-mcp
safe-db-mcp setup
safe-db-mcp connections add [options]
safe-db-mcp connections list
safe-db-mcp connections delete <id>
```

`setup` and `connections add` are the same command. On a TTY they prompt for missing fields and a no-echo password; non-interactive use requires `--password-file`. They test the connection, then call `createConnection`.

| Flag | Notes |
| --- | --- |
| `--name` | Defaults to the database name |
| `--dialect` | `postgres`, `mysql`, `mssql`, or `oracle` |
| `--host` | Defaults to `localhost` |
| `--port` | Dialect default when omitted |
| `--database` | Required |
| `--username` | Required |
| `--password-file` | Absolute path; one UTF-8 line; owner-only (`0600`). Setup copies the secret into the credential store. |
| `--transport` | `disabled`, `encrypt-only`, `verify-ca`, or `verify-identity`. Off a TTY, defaults from host location. |
| `--oracle-wallet` | Absolute path; required for Oracle when transport is not `disabled` |

`--password` and `-p` are rejected. Do not put host, user, database, or password in environment variables.

## Tools

Progressive catalog, then a gated query. Do not expect the full [`Schema`](../shared/src/main/kotlin/com/safedb/model/Schema.kt) or full [`QueryResult`](../shared/src/main/kotlin/com/safedb/model/Ir.kt) as a tool payload. No tool accepts a password or a URL with embedded credentials.

| Tool | Behavior |
| --- | --- |
| `list_connections` | `id`, `name`, `dialect`, `database`. No secrets. |
| `delete_connection` | Delete a saved connection by `connection_id`. Invalidates the schema cache for that id. |
| `list_tables` | `schema`, `name`, `qualified_name`, `size_class`, `column_count`. Cached `getSchema` (5 min TTL, optional `refresh=true`). Honors `blocked_schemas` and built-in system catalogs. |
| `describe_table` | `connection_id` + `schema` + `table` from `list_tables`. Columns (`name`, `data_type`, `nullable`), indexes, FKs. Same cache. Unknown or blocked → `Table not found`. |
| `run_query` | `connection_id` and exactly one of `sql` or `spec`. Optional `default_schema` (else settings default for that connection). Parses SQL to `QuerySpec`, then `SafeDbService.runQuery` (fresh introspect on execute). Receipt, not the grid. |
| `get_result_rows` | Page the in-memory sample for a `result_id`. `offset` default 0 (negative clamps to 0); `limit` default 50, hard cap 50. Unknown or expired → `Result not found`. |
| `summarize_result` | Per-column stats on that same sample. Unknown or expired → `Result not found`. |

Do not `Read` the JSONL artifact file into chat. Page with `get_result_rows` or summarize with `summarize_result`.

Constants (change the constant and update this page in the same change):

| Constant | Value | File |
| --- | --- | --- |
| `SCHEMA_CACHE_TTL_MS` | 5 minutes | [`SchemaCache.kt`](../mcp/src/main/kotlin/com/safedb/mcp/SchemaCache.kt) |
| `PREVIEW_ROW_LIMIT` | 10 rows | [`ResultPreview.kt`](../mcp/src/main/kotlin/com/safedb/mcp/ResultPreview.kt) |
| `GET_RESULT_ROWS_MAX` | 50 rows | [`ResultPreview.kt`](../mcp/src/main/kotlin/com/safedb/mcp/ResultPreview.kt) |
| `RESULT_STORE_TTL_MS` | 5 minutes | [`ResultStore.kt`](../mcp/src/main/kotlin/com/safedb/mcp/ResultStore.kt) |
| `RESULT_STORE_MAX_ENTRIES` | 8 | [`ResultStore.kt`](../mcp/src/main/kotlin/com/safedb/mcp/ResultStore.kt) |
| `RESULT_STORE_MAX_BYTES` | 32 MiB | [`ResultStore.kt`](../mcp/src/main/kotlin/com/safedb/mcp/ResultStore.kt) |
| `DISTINCT_VALUE_LIMIT` | 8 values | [`ResultSummary.kt`](../mcp/src/main/kotlin/com/safedb/mcp/ResultSummary.kt) |

List/describe cache a `Schema` per connection in the MCP process. `run_query` does not use that cache for the risk gate: `runQuery` introspects fresh schema before `runQueryCore`.

## `run_query` receipt

A successful call returns JSON with:

- `columns` (`name`, `data_type`)
- `row_count`, `truncated`, `preview_truncated`, `warnings`
- `risk`: `state`, `severity`, `score`, `effective_gate`, `reasons`, `plan_status`
- `preview`: about 10 flattened rows (`null` / bool / number / string; binary as base64). Not tagged `ResultCell` objects.
- `result_id` for later paging and summary
- optional `artifact_path` / `artifact_bytes` when the JSONL write succeeded; those keys are omitted (not null) when it failed

`truncated` is the engine byte/limit cap from [query-engine.md](query-engine.md). `preview_truncated` is true when the preview is shorter than `row_count`. The engine can still fetch up to `DEFAULT_LIMIT` / `MAX_LIMIT`; the receipt does not include the full sample.

Accepted and rejected SQL are the same as the desktop app. Optional `spec` is `QuerySpec` JSON from the parser or builder.

### Errors

Tool errors are JSON with `error` and `message` (and usually `warnings`):

| `error` | What to do |
| --- | --- |
| `parse` | Fix `sql` or `spec`. |
| `validation` / `compilation` / `execution` | Same meanings as [`QueryError`](../shared/src/main/kotlin/com/safedb/query/QueryCore.kt). |
| `risk_gate` | Includes a slim `risk` summary. Rewrite the query or change `query_risk_gate` in settings. Do not retry the same query. |
| `confirmation_required` | Includes `reasons` and a `confirmation` object. Show the reasons to the user, then retry `run_query` with that object. Do not auto-confirm. Do not invent the object. |

## Result store

[`ResultStore`](../mcp/src/main/kotlin/com/safedb/mcp/ResultStore.kt) keeps the fetched `QueryResult` in process under `result_id` (5 min TTL, last 8, 32 MiB JSONL cap, access-order LRU). That is the MCP analogue of the Explore sample.

On put it also writes `{dataDir}/results/{result_id}.jsonl`: one flattened object per row, owner-only. JSONL write failure does not fail the query: `result_id` and the in-memory sample remain, artifact fields omitted. Evict/expire deletes the file. The results directory is wiped when the store is constructed (process start).

`get_result_rows` pages the in-memory sample, not the file. `page_truncated` is true when this page is shorter than the remaining sample. `summarize_result` reports per-column `null_count`, min/max (int/float numeric, text lexicographic, bool; omitted for binary and when there are no comparable values), and up to 8 distinct flattened values (`distinct_truncated` if more). Both include `truncated` with the engine-cap meaning from the original query.

## Not in this version

- Sharing macOS Keychain items with the signed desktop app
- MCP tools that add or update a connection
- MCP tools that change settings
- Environment variables for host, user, database, or password
- Dumping full `Schema` or full `QueryResult` into tool JSON
- MCP resources, elicitation, catalog search, and CSV export
- Desktop Conveyor / MSI / DMG as the MCP install path
