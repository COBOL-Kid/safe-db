package com.safedb.mcp

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun registerResultQueryTools(server: Server, resultStore: ResultStore) {
    val resultTtlMinutes = RESULT_STORE_TTL_MS / 60_000
    server.addTool(
        name = "get_result_rows",
        description =
            "Page flattened rows from a previous run_query by result_id. offset defaults " +
                "to 0 (negative clamps to 0). limit defaults to 50 and caps at 50; 0 or " +
                "negative is invalid_arguments. Pages also stop around " +
                "$MCP_PAYLOAD_MAX_BYTES bytes; long cells are truncated with " +
                "${MCP_CELL_TRUNCATION_MARKER}. page_truncated is true when more sample " +
                "rows remain; next_offset is then the offset to request next. Results " +
                "expire after $resultTtlMinutes minutes. Use summarize_result for column " +
                "stats. Unknown or expired result_id returns not_found.",
        inputSchema =
            ToolSchema(
                properties =
                    buildJsonObject {
                        put(
                            "result_id",
                            buildJsonObject {
                                put("type", "string")
                                put("description", "result_id from a successful run_query receipt")
                            },
                        )
                        put(
                            "offset",
                            buildJsonObject {
                                put("type", "integer")
                                put(
                                    "description",
                                    "Row offset into the in-memory sample. Default 0.",
                                )
                            },
                        )
                        put(
                            "limit",
                            buildJsonObject {
                                put("type", "integer")
                                put(
                                    "description",
                                    "Max rows to return (1–50). Default 50. Values over 50 " +
                                        "cap at 50. 0 or negative is invalid_arguments. " +
                                        "A page may contain fewer rows when it hits the " +
                                        "$MCP_PAYLOAD_MAX_BYTES byte budget.",
                                )
                            },
                        )
                    },
                required = listOf("result_id"),
            ),
        toolAnnotations = ToolAnnotations(readOnlyHint = true),
    ) { request ->
        runMcpTool("get_result_rows failed") { handleGetResultRows(resultStore, request) }
    }

    server.addTool(
        name = "summarize_result",
        description =
            "Summarize a previous run_query by result_id using the in-memory sample: " +
                "per-column null_count, min/max (numeric, text, bool; omitted for binary), " +
                "and up to 8 distinct flattened values. sample_capped (omitted when false) " +
                "echoes the original fetch cap. Results expire after $resultTtlMinutes " +
                "minutes. Unknown or expired result_id returns not_found.",
        inputSchema =
            ToolSchema(
                properties =
                    buildJsonObject {
                        put(
                            "result_id",
                            buildJsonObject {
                                put("type", "string")
                                put("description", "result_id from a successful run_query receipt")
                            },
                        )
                    },
                required = listOf("result_id"),
            ),
        toolAnnotations = ToolAnnotations(readOnlyHint = true),
    ) { request ->
        runMcpTool("summarize_result failed") { handleSummarizeResult(resultStore, request) }
    }
}

private suspend fun handleGetResultRows(
    resultStore: ResultStore,
    request: CallToolRequest,
): CallToolResult {
    requireKnownArguments(request, "result_id", "offset", "limit").getOrElse {
        return it
    }
    val resultId =
        requiredString(request, "result_id").getOrElse {
            return it
        }
    val offsetRaw =
        optionalInt(request, "offset").getOrElse {
            return it
        } ?: 0
    val limitRaw =
        optionalInt(request, "limit").getOrElse {
            return it
        }
    if (limitRaw != null && limitRaw <= 0) {
        return toolError(ERROR_INVALID_ARGUMENTS, "limit must be 1 or more")
    }
    val result = resultStore.get(resultId) ?: return toolError(ERROR_NOT_FOUND, "Result not found")
    val offset = offsetRaw.coerceAtLeast(0)
    val limit = (limitRaw ?: GET_RESULT_ROWS_MAX).coerceAtMost(GET_RESULT_ROWS_MAX)
    val slice = sliceRows(result, offset, limit, MCP_PAYLOAD_MAX_BYTES)
    val page =
        ResultRowsPage(
            resultId = resultId,
            offset = offset,
            rowCount = result.rowCount,
            pageTruncated = slice.moreRowsRemain,
            nextOffset = slice.nextOffset,
            rows = slice.rows,
        )
    return objectToolResult(page)
}

private suspend fun handleSummarizeResult(
    resultStore: ResultStore,
    request: CallToolRequest,
): CallToolResult {
    requireKnownArguments(request, "result_id").getOrElse {
        return it
    }
    val resultId =
        requiredString(request, "result_id").getOrElse {
            return it
        }
    val result = resultStore.get(resultId) ?: return toolError(ERROR_NOT_FOUND, "Result not found")
    val summary =
        ResultSummary(
            resultId = resultId,
            rowCount = result.rowCount,
            sampleCapped = result.truncated,
            columns = summarizeColumns(result),
        )
    return objectToolResult(summary)
}
