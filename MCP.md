# MCP server design

Stdio MCP server so agents can inspect schema and run `SELECT`s through the same parser, validator, compiler, and risk gate as the desktop app. This is a design sketch, not a contract: change it in the same PR that implements the relevant piece.

## Goals

- Reuse `:shared` (`SafeDbService`, adapters, `QuerySpec`, risk gate, stores). No second catalog client and no agent-written `information_schema` SQL.
- Easy install and update for Cursor and similar clients.
- Windows and Linux as first MCP platforms. macOS can run the server; sharing the desktop app’s Keychain items is **out of scope** for v1 (see [Credentials](#connections-and-credentials)).
- Results stay parseable without dumping the full grid into the model context.

## Distribution

Gradle builds the artifact. Users never run Gradle to install.

| Channel | Role |
| --- | --- |
| GitHub Releases | Versioned fat JAR (Shadow) and, when needed, per-OS binaries with a bundled JRE. JDK 25 is the toolchain; a bare JAR is not a reasonable default for people without that JVM. |
| npm package (`npx -y @safedb/mcp` or similar) | Thin launcher: detect OS, download/cache the matching release, exec stdio. Do not publish a 100MB+ JAR or JRE inside the npm tarball. |

Cursor config is a command, not an installer wizard:

```json
{
  "mcpServers": {
    "safe-db": {
      "command": "npx",
      "args": ["-y", "@safedb/mcp"]
    }
  }
}
```

Do not use Conveyor or the Compose `nativeDistributions` path as the primary MCP install. Those remain for the desktop app. Docker is a later optional channel (awkward for JDBC to `localhost`).

MCP Registry: `registryType: "npm"` when publishing. Maven/Gradle artifacts are not a registry package type.

## Architecture

New `:mcp` module (stdio CLI) depends on `:shared` only. It maps MCP tools onto `SafeDbService` (and small helpers that slice `Schema` / `QueryResult`). It does not reimplement JDBC, validation, or gating.

`run_query` always introspects fresh schema before `runQueryCore`, matching `getSchemaAndRunQueryAlwaysIntrospectFreshSchema`. List/describe tools cache a `Schema` per connection for 5 minutes (optional `refresh=true` bypasses the cache); stale index/FK metadata must not be used for the risk gate.

Linux is a resolved `DesktopPlatform`. `DataDirectory` (`com.safedb.app`) and the OS credential store (`auto` / `protected`) stay desktop-app capabilities and throw `DesktopStoreUnavailableException` on Linux. The GUI still rejects Linux in `Main.kt` before Compose. MCP data paths live in `McpDataDirectory` (Windows shares the desktop app dir; Mac and Linux use `com.safedb.mcp`, Linux under XDG). Windows MCP initializes `SecretsManager` against Credential Manager. Mac and Linux MCP use `SecretsManager.initFileStore` (`{dataDir}/credentials/`, POSIX 0600 files) and never open Keychain.

## Connections and credentials

Non-secrets stay in `connections.json` via `ConfigStore`. Passwords never go in that file, in `mcp.json`, in tool arguments, or in environment variables.

**Reuse desktop connections when they exist.** Default data directory is the same as the app (`APPDATA/com.safedb.app` on Windows). `list_connections` is then the same list the UI uses.

**Windows:** persist passwords in Windows Credential Manager through existing `SecretsManager` / java-keyring, still bound to `credentialFingerprint()`.

**macOS Keychain sharing with the signed desktop app is deferred.** An npm-launched `java` (or Node) process is a different code identity than `safe-db.app`. Silent, stable reuse of desktop Keychain items needs a Developer ID–signed MCP helper with the same Team ID; that work is later. Until then, Mac MCP connections are a separate store (MCP-only `connections.json` and/or the file password source below). Do not document “the agent uses your app passwords” on Mac in v1.

**Without the desktop app:** `safe-db-mcp setup` or `connections add` prompts for host/user/database and a no-echo password (or `--password-file`), tests the connection, then calls `createConnection`. After that, `mcp.json` stays command-only.

**Password file fallback** (Linux, and Mac until Keychain sharing exists): MCP `--password-file` is one line, an absolute path, and owner-only (`0600`). Launch profiles use the same one-line absolute format; `0600` is guidance there, not enforcement. Setup copies the secret into `{mcpDataDir}/credentials/` (owner-only files). `connections.json` stays non-secret. A live `PasswordSource` pointer overlay is later.

**Do not** add MCP tools that accept a raw `password` or a URL with embedded credentials. List/delete connections is fine; add/update takes a password *source*, not the password.

`SAFEDB_KEYCHAIN_BACKEND` remains a store-selection switch (including `disabled` for tests). It is not how users pass connection details.

## Agent tools

Progressive catalog, then a gated query. Do not return the full `Schema` as the main payload.

| Tool | Behavior |
| --- | --- |
| `list_connections` | id, name, dialect, database. No secrets. |
| `delete_connection` | Delete a saved connection by id. No secrets. |
| `list_tables` | `schema`, `name`, `qualified_name`, `size_class`, `column_count`. Cached `introspect()` (5 min TTL, optional `refresh`). Honor `blocked_schemas` (and built-in system catalogs). |
| `describe_table` | `connection_id` + `schema` + `table` from `list_tables`. Columns (`name`, `data_type`, `nullable`), indexes, FKs. Same cache. Unknown or blocked → `Table not found`. |
| `run_query` | Parse SQL to `QuerySpec` (or accept a spec), `SafeDbService.runQuery` / `runQueryCore`, same `query_risk_gate` and caps as the app. Fresh introspect on execute. Returns a receipt: columns, row_count, truncated, preview_truncated, warnings, slim risk, ~10 flattened rows. Full sample is not in this payload. On confirmation_required, show reasons then retry with the returned confirmation object; do not auto-confirm; do not invent the confirmation object. `result_id`, JSONL, and paging are item 6. |

`search_schema` and saved-query listing can wait. MCP resources / resource links are out of scope for v1. `get_result_rows` and `summarize_result` are item 6 (not shipped).

## Query results

`run_query` returns a **receipt**, not the table:

- columns (`name`, `data_type`), `row_count`, `truncated`, `preview_truncated`, warnings, slim risk summary
- A short **preview** (about 10 rows) of flattened JSON primitives (`null` / bool / number / string). Do not serialize `ResultCell` tagged objects into the tool payload.
- `truncated` is the engine byte/limit cap. `preview_truncated` is true when the preview is shorter than `row_count`.

The engine can still fetch up to `DEFAULT_LIMIT` / `MAX_LIMIT`. What the model currently sees is the ~10-row preview on the receipt. Full sample is not in the payload.

**Item 6:** keep the fetched `QueryResult` in process under a `result_id` (TTL, last-N, max bytes). That is the MCP analogue of `ExploreSession.sample`. Write a **JSONL artifact** on disk (app data dir or a wiped temp dir): one object per row, same flattened cells. Use existing export ideas; CSV can wait. Path and byte size go on the receipt. Paging tools (`get_result_rows`, `summarize_result`) are that same item — not current catalog.

## Implementation sketch

Order is the intended dependency, not a commitment to one PR.

1. **`:mcp` module** — stdio server with the official Kotlin MCP SDK. Shadow JAR. `main` that wires `SafeDbServiceImpl` to `McpDataDirectory` (done — see Architecture).
2. **Platform gate** — `DesktopPlatform` includes Linux. `DataDirectory` and OS credential `auto`/`protected` throw `DesktopStoreUnavailableException` on Linux. Compose UI still exits in `Main.kt` with the macOS/Windows-only message. MCP uses `DesktopPlatform` (no parallel `McpPlatform`). (done — see Architecture.)
3. **Connection bootstrap** — CLI `setup` / `connections add` / `list` / `delete`; `list_connections` / `delete_connection` tools. Windows: `SecretsManager` / Credential Manager. Mac/Linux: `initFileStore`. No password fields on tools. (done — see Connections.)
4. **Schema tools** — `getSchema` / `introspect` once, cache 5 minutes in the MCP process (`refresh` bypasses), slice into `list_tables` and `describe_table`. Filter with `isSchemaBlocked`. Do not cache inside `SafeDbService`. (done — see Agent tools.)
5. **`run_query`** — reuse `runQueryCore` via `SafeDbService.runQuery`; map `QueryError.RiskGate` and confirmation-required into tool errors the client can show. Fresh introspect on execute. (done — see Agent tools / Query results.)
6. **Result store** — `result_id` map, JSONL write, `get_result_rows` (page the in-memory sample; hard cap e.g. 50 rows per call), `summarize_result` (per-column null count, min/max, a few distinct values on the sample). Tool descriptions should then tell the model to use those tools, not to `Read` the whole artifact file into the chat. Preview on the receipt is already item 5.
7. **npm wrapper** — `bin` that downloads the GitHub Release artifact, caches by version, execs with stdio. CI publishes the JAR (and later OS bundles) then the npm package.
8. **Docs and install snippet** — user-facing install on the site or README; this file stays the engineering outline until behavior is real.
9. **Later** — signed Mac helper and Keychain sharing with the desktop app; optional `PasswordSource` config import; MCP Registry; Docker; `search_schema`.

## Out of scope for v1

- Sharing macOS Keychain items with the signed desktop app
- Conveyor / MSI / DMG as the MCP install path
- GraalVM native image
- Agents passing connection strings or passwords
- Environment variables for host, user, database, or password
- Dumping full `Schema` or full `QueryResult` into tool JSON
- MCP resources, elicitation, and catalog search
