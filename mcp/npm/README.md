# @safe-db/mcp

Stdio MCP server for [safe-db](https://www.safe-db.dev). Inspect schema and run a gated `SELECT` without installing Java. The matching platform package bundles a Temurin 25 jlink runtime.

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

Requires Apple Silicon / arm64 macOS (not Intel), Windows x64, or glibc Linux (x64 and arm64). Alpine/musl is not supported. Connections and tools: [docs/mcp.md](https://github.com/COBOL-Kid/safe-db/blob/main/docs/mcp.md).
