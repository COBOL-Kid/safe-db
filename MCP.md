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

`run_query` always introspects fresh schema before `runQueryCore`, matching `getSchemaAndRunQueryAlwaysIntrospectFreshSchema`. List/describe tools may cache a `Schema` per connection with a TTL; stale index/FK metadata must not be used for the risk gate.

Linux is a resolved `DesktopPlatform`. `DataDirectory` (`com.safedb.app`) and the OS credential store (`auto` / `protected`) stay desktop-app capabilities and throw `DesktopStoreUnavailableException` on Linux. The GUI still rejects Linux in `Main.kt` before Compose. MCP data paths live in `McpDataDirectory` (Windows shares the desktop app dir; Mac and Linux use `com.safedb.mcp`, Linux under XDG).

## Connections and credentials

Non-secrets stay in `connections.json` via `ConfigStore`. Passwords never go in that file, in `mcp.json`, in tool arguments, or in environment variables.

**Reuse desktop connections when they exist.** Default data directory is the same as the app (`APPDATA/com.safedb.app` on Windows). `list_connections` is then the same list the UI uses.

**Windows:** persist passwords in Windows Credential Manager through existing `SecretsManager` / java-keyring, still bound to `credentialFingerprint()`.

**macOS Keychain sharing with the signed desktop app is deferred.** An npm-launched `java` (or Node) process is a different code identity than `safe-db.app`. Silent, stable reuse of desktop Keychain items needs a Developer ID–signed MCP helper with the same Team ID; that work is later. Until then, Mac MCP connections are a separate store (MCP-only `connections.json` and/or the file password source below). Do not document “the agent uses your app passwords” on Mac in v1.

**Without the desktop app:** an interactive CLI (`npx @safedb/mcp setup` or `connections add`) prompts for host/user/database and a no-echo password, then calls `createConnection`. After that, `mcp.json` stays command-only.

**Password file fallback** (Linux, and Mac until Keychain sharing exists): same shape as launch profiles — config points at an absolute `0600` (or Windows ACL) file; the secret is not in JSON.

```json
{
  "source": "file",
  "path": "/absolute/path/to/prod-pg.password"
}
```

Optional later: importable overlay of `ConnectionDef` plus `PasswordSource` (`credentialStore` | `file`). Not required if CLI setup ships first.

**Do not** add MCP tools that accept a raw `password` or a URL with embedded credentials. List/delete connections is fine; add/update takes a password *source*, not the password.

`SAFEDB_KEYCHAIN_BACKEND` remains a store-selection switch (including `disabled` for tests). It is not how users pass connection details.

## Agent tools

Progressive catalog, then a gated query. Do not return the full `Schema` as the main payload.

| Tool | Behavior |
| --- | --- |
| `list_connections` | id, name, dialect, database. No secrets. |
| `list_tables` | schema-qualified names, size class, column count. From a cached `introspect()`. Honor `blocked_schemas`. |
| `describe_table` | columns, types, indexes, FKs for one table. |
| `run_query` | Parse SQL to `QuerySpec` (or accept a spec), `runQueryCore`, same `query_risk_gate` and caps as the app. |
| `get_result_rows` | Page an in-memory sample. Hard cap (e.g. 50 rows per call). |
| `summarize_result` | Per-column null count, min/max, a few distinct values on the sample. |

`search_schema` and saved-query listing can wait. MCP resources / resource links are out of scope for v1.

Tool descriptions should tell the model to use `get_result_rows` / `summarize_result`, not to `Read` the whole artifact file into the chat.

## Query results

Keep the fetched `QueryResult` in process under a `result_id` (TTL, last-N, max bytes). That is the MCP analogue of `ExploreSession.sample`.

`run_query` returns a **receipt**, not the table:

- `result_id`, columns (`name`, `data_type`), `row_count`, `truncated`, warnings, risk summary
- A short **preview** (about 10 rows) of flattened JSON primitives (`null` / bool / number / string). Do not serialize `ResultCell` tagged objects into the tool payload.
- **JSONL artifact** on disk (app data dir or a wiped temp dir): one object per row, same flattened cells. Use existing export ideas; CSV can wait. Path and byte size go on the receipt.

The engine can still fetch up to `DEFAULT_LIMIT` / `MAX_LIMIT`. What the model sees is the preview plus whatever `get_result_rows` asked for.

## Implementation sketch

Order is the intended dependency, not a commitment to one PR.

1. **`:mcp` module** — stdio server with the official Kotlin MCP SDK. Shadow JAR. `main` that wires `SafeDbServiceImpl` to `McpDataDirectory` (done — see Architecture). The module, stdio `main`, Shadow JAR, and `McpDataDirectory` exist; tool handlers are not registered yet.
2. **Platform gate** — `DesktopPlatform` includes Linux. `DataDirectory` and OS credential `auto`/`protected` throw `DesktopStoreUnavailableException` on Linux. Compose UI still exits in `Main.kt` with the macOS/Windows-only message. MCP uses `DesktopPlatform` (no parallel `McpPlatform`).
3. **Connection bootstrap** — CLI `setup` / `connections add`; `list_connections` / `delete_connection` tools. Windows: `SecretsManager` as today. Elsewhere: file password source. No password fields on tools.
4. **Schema tools** — `getSchema` / `introspect` once, cache, slice into `list_tables` and `describe_table`.
5. **`run_query`** — reuse `runQueryCore`; map `QueryError.RiskGate` and confirmation-required into tool errors the client can show. Fresh introspect on execute.
6. **Result store** — `result_id` map, JSONL write, preview in the tool result, `get_result_rows`, `summarize_result`.
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
