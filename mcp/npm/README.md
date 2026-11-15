# @safe-db/mcp

Read-only MCP server for PostgreSQL, MySQL, SQL Server, Oracle, and Db2. Inspect schema and run one gated `SELECT` through the [safe-db](https://safe-db.dev) query engine. It bundles a Temurin jlink runtime, so the machine does not need Java.

```bash
npx -y @safe-db/mcp setup --dialect mysql --database app --username readonly --password-file /absolute/path
```

Client config, connection CLI, tools, receipts, and errors: [safe-db.dev/docs/mcp](https://safe-db.dev/docs/mcp).

Runs on macOS arm64, Windows x64, and glibc Linux (x64 and arm64). Alpine/musl is not supported.
