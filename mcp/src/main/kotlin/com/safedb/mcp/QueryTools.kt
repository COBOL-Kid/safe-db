package com.safedb.mcp

import com.safedb.service.SafeDbService
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun registerQueryTools(server: Server, service: SafeDbService, resultStore: ResultStore) {
    val resultTtlMinutes = RESULT_STORE_TTL_MS / 60_000
    server.addTool(
        name = "run_query",
        description =
            "Run one SELECT on a saved connection. Accepts filters, DISTINCT, GROUP BY of " +
                "selected columns, ORDER BY, LIMIT, and INNER JOIN on indexed or FK columns. " +
                "Rejects aggregates and functions, OFFSET, subqueries, CTEs, UNION, " +
                "LEFT/CROSS or comma joins, AS aliases, and leading NOT. Result columns are " +
                "named table__column. Pass connection_id from list_connections and sql. Do " +
                "not pass a password or URL. Returns a receipt: dialect, columns, row_count, " +
                "sample_capped (omitted when false), sample_cap_reason (sql_limit or " +
                "byte_budget) when capped, sample_unbounded when the SQL omitted LIMIT " +
                "(omitted when false), preview_truncated, warnings, a slim " +
                "risk summary, a short preview (~10 flattened rows), and result_id. " +
                "Page with get_result_rows or summarize_result using result_id. Preview " +
                "and pages are capped at $MCP_PAYLOAD_MAX_BYTES bytes; long cell values " +
                "are truncated with ${MCP_CELL_TRUNCATION_MARKER}. Results expire after " +
                "$resultTtlMinutes minutes ($RESULT_STORE_MAX_ENTRIES entries, " +
                "32 MiB). On error risk_gate, rewrite the query; do not retry the same " +
                "query. On error confirmation_required, " +
                "show the reasons to the user, then retry with the returned confirmation " +
                "object; do not auto-confirm. Unknown extra arguments are invalid_arguments. " +
                "The result includes JSON text and structuredContent with the same object.",
        inputSchema =
            ToolSchema(
                properties =
                    buildJsonObject {
                        put(
                            "connection_id",
                            buildJsonObject {
                                put("type", "string")
                                put("description", "Connection id from list_connections")
                            },
                        )
                        put(
                            "sql",
                            buildJsonObject {
                                put("type", "string")
                                put(
                                    "description",
                                    "A single SELECT. Result columns are named table__column. " +
                                        "No aggregates or functions, OFFSET, subqueries, CTEs, " +
                                        "UNION, LEFT/CROSS or comma joins, AS aliases, or " +
                                        "leading NOT. Joins need indexed or FK columns.",
                                )
                            },
                        )
                        put(
                            "default_schema",
                            buildJsonObject {
                                put("type", "string")
                                put(
                                    "description",
                                    "Schema used when a table name has no schema prefix. Copy " +
                                        "list_tables.schema for this connection (MySQL: usually " +
                                        "the database name). Omit when every table is already " +
                                        "schema.table.",
                                )
                            },
                        )
                        put(
                            "confirmation",
                            buildJsonObject {
                                put("type", "object")
                                put(
                                    "description",
                                    "Echo the confirmation object from a confirmation_required " +
                                        "error after showing the reasons to the user. Do not " +
                                        "invent this object.",
                                )
                            },
                        )
                    },
                required = listOf("connection_id", "sql"),
            ),
        outputSchema =
            ToolSchema(
                properties =
                    buildJsonObject {
                        put("dialect", buildJsonObject { put("type", "string") })
                        put("columns", buildJsonObject { put("type", "array") })
                        put("row_count", buildJsonObject { put("type", "integer") })
                        put("warnings", buildJsonObject { put("type", "array") })
                        put("risk", buildJsonObject { put("type", "object") })
                        put("preview", buildJsonObject { put("type", "array") })
                        put("preview_truncated", buildJsonObject { put("type", "boolean") })
                        put("result_id", buildJsonObject { put("type", "string") })
                        put("sample_capped", buildJsonObject { put("type", "boolean") })
                        put("sample_cap_reason", buildJsonObject { put("type", "string") })
                        put("sample_unbounded", buildJsonObject { put("type", "boolean") })
                    },
                required =
                    listOf(
                        "dialect",
                        "columns",
                        "row_count",
                        "warnings",
                        "risk",
                        "preview",
                        "preview_truncated",
                        "result_id",
                    ),
            ),
    ) { request ->
        runMcpTool("run_query failed") { handleRunQuery(service, resultStore, request) }
    }

    registerAssessQueryTools(server, service)
    registerResultQueryTools(server, resultStore)
}

private suspend fun handleRunQuery(
    service: SafeDbService,
    resultStore: ResultStore,
    request: CallToolRequest,
): CallToolResult {
    requireKnownArguments(request, "connection_id", "sql", "default_schema", "confirmation")
        .getOrElse {
            return it
        }
    val resolved =
        resolveSqlSpec(service, request).getOrElse {
            return it
        }
    return executeConfirmedQuery(service, resultStore, resolved.connection, resolved.spec, request)
}
