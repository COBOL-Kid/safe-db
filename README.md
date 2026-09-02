# safe-db

Source for the [safe-db](https://www.safe-db.dev) desktop app and MCP server: a Compose Desktop UI, a stdio MCP CLI, and a `:shared` query engine that introspects PostgreSQL, MySQL, SQL Server, and Oracle, compiles a structured `QuerySpec` to bound-parameter SQL, and runs a time-boxed, row-capped `SELECT`.

Packaged desktop releases and the user guide live at [https://www.safe-db.dev](https://www.safe-db.dev). This repository is for building from source and changing the code.

The desktop app runs on macOS and Windows; [`Main.kt`](src/main/kotlin/com/safedb/Main.kt) rejects other operating systems before Compose, JDBC, or app data initialize. The MCP server also runs on Linux and is packaged as `@safe-db/mcp` (`npx -y @safe-db/mcp`). Connections, tools, and result receipts: [docs/mcp.md](docs/mcp.md).

## Layout

| Path | Role |
| --- | --- |
| Root (`src/`) | Compose Desktop UI, viewmodels, HTML/CSV/PNG export. Main class `com.safedb.MainKt`. |
| [`:shared`](shared/) | Query engine, JDBC adapters, SQL parser, stores, secrets, launch profiles. Not a published library. |
| [`:mcp`](mcp/) | Stdio MCP server CLI (`safe-db-mcp`), published as `@safe-db/mcp` with a bundled Temurin 25 jlink runtime. `setup` / `connections add` to save connections; tools for list/describe/query/page/summarize. See [docs/mcp.md](docs/mcp.md). |

## Contracts

Read [docs/query-engine.md](docs/query-engine.md) before changing the parser, validator, compiler, risk gate, or an adapter. The short version:

- Builder, SQL screen, and MCP `run_query` all produce a `QuerySpec`. Compilation binds parameters; typed SQL never reaches the database ([`SqlToSpec.kt`](shared/src/main/kotlin/com/safedb/query/sql/SqlToSpec.kt), [`Compile.kt`](shared/src/main/kotlin/com/safedb/query/Compile.kt)).
- Hikari pools are size 1 and `isReadOnly = true` ([`JdbcHelpers.kt`](shared/src/main/kotlin/com/safedb/adapter/JdbcHelpers.kt)).
- Row, `IN`-list, and timeout caps live in [`Validate.kt`](shared/src/main/kotlin/com/safedb/query/Validate.kt) and [`QueryCore.kt`](shared/src/main/kotlin/com/safedb/query/QueryCore.kt).

## Working in this repo

| Command | Use |
| --- | --- |
| `./gradlew run` | Start the desktop app. |
| `./gradlew check` | PR gate: unit tests, discovery, Docker harness orchestration, Kover ratchets. |
| `./gradlew packageDistributionForCurrentOS` | Unsigned DMG (macOS) or MSI (Windows). |

Integration tests, Docker fixtures, and headless renders: [docs/testing.md](docs/testing.md). MCP server: [docs/mcp.md](docs/mcp.md). Managed PKCS12 launch profiles: [docs/trust-stores.md](docs/trust-stores.md).

For local development or CI, `SAFEDB_KEYCHAIN_BACKEND=disabled` keeps connection credentials in memory instead of the OS store. Trust-store password lookup still uses the strict platform backend and never falls back to that in-memory store.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Run `./gradlew check` before opening a pull request.

## License

[Apache License 2.0](LICENSE.txt)
