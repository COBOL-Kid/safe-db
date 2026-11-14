package com.safedb.mcp

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.buildJsonObject

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
                        put("result_id", resultIdProperty())
                        put(
                            "offset",
                            intProp("Row offset into the in-memory sample. Default 0."),
                        )
                        put(
                            "limit",
                            boundedLimitProperty(
                                defaultLimit = GET_RESULT_ROWS_MAX,
                                maxLimit = GET_RESULT_ROWS_MAX,
                                noun = "rows",
                                extraNote =
                                    " A page may contain fewer rows when it hits the " +
                                        "$MCP_PAYLOAD_MAX_BYTES byte budget.",
                            ),
                        )
                    },
                required = listOf("result_id"),
            ),
        outputSchema =
            objectOutputSchema(
                listOf("result_id", "offset", "row_count", "page_truncated", "rows"),
            ) {
                put("result_id", stringProp())
                put("offset", intProp())
                put("row_count", intProp())
                put("page_truncated", boolProp())
                put("next_offset", intProp())
                put("rows", arrayProp())
            },
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
                properties = buildJsonObject { put("result_id", resultIdProperty()) },
                required = listOf("result_id"),
            ),
        outputSchema =
            objectOutputSchema(listOf("result_id", "row_count", "columns")) {
                put("result_id", stringProp())
                put("row_count", intProp())
                put("columns", arrayProp())
                put("sample_capped", boolProp())
            },
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
    return objectToolResult(buildResultSummary(resultId, result))
}
