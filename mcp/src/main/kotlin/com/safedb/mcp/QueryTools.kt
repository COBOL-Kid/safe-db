package com.safedb.mcp

import com.safedb.model.ConnectionDef
import com.safedb.model.QueryRiskGate
import com.safedb.model.QuerySpec
import com.safedb.model.ResultColumn
import com.safedb.model.Settings
import com.safedb.query.QueryConfirmationCondition
import com.safedb.query.QueryConfirmationReasonCode
import com.safedb.query.QueryError
import com.safedb.query.QueryExecutionConfirmation
import com.safedb.query.QueryRiskEvaluation
import com.safedb.query.sql.SqlIssue
import com.safedb.query.sql.SqlIssueCode
import com.safedb.query.sql.SqlParseResult
import com.safedb.query.sql.mySqlBackslashEscapes
import com.safedb.query.sql.parseSqlToSpec
import com.safedb.service.QueryFailureException
import com.safedb.service.QueryRunRequest
import com.safedb.service.QueryRunResult
import com.safedb.service.SafeDbService
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

internal fun registerQueryTools(server: Server, service: SafeDbService, resultStore: ResultStore) {
    val resultTtlMinutes = RESULT_STORE_TTL_MS / 60_000
    server.addTool(
        name = "run_query",
        description =
            "Run a single SELECT on a saved connection through the same parser, validator, " +
                "compiler, row/time caps, and query_risk_gate as the desktop app. Pass " +
                "connection_id from list_connections and sql. Do not pass a password or URL. " +
                "Returns a receipt: columns, row_count, sample_capped (omitted when false), " +
                "preview_truncated, warnings, a slim risk summary, a short preview " +
                "(~10 flattened rows), and result_id. preview_truncated is true when the " +
                "preview is shorter than row_count. sample_capped means this sample is the " +
                "full fetch; paging does not get more table rows — raise LIMIT or add filters. " +
                "Page with get_result_rows or summarize_result using result_id. Results expire " +
                "after $resultTtlMinutes minutes ($RESULT_STORE_MAX_ENTRIES entries, 32 MiB). " +
                "On error risk_gate, rewrite the query or change the gate in settings; do not " +
                "retry the same query. On error confirmation_required, show the reasons to " +
                "the user, then retry with the returned confirmation object; do not auto-confirm.",
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
                                    "A single SELECT. Writes and unsupported SQL are rejected.",
                                )
                            },
                        )
                        put(
                            "default_schema",
                            buildJsonObject {
                                put("type", "string")
                                put(
                                    "description",
                                    "Required for unqualified table names. Take schema from " +
                                        "list_tables (on MySQL often the connection database), " +
                                        "or qualify as schema.table.",
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
    ) { request ->
        handleRunQuery(service, resultStore, request)
    }

    server.addTool(
        name = "get_result_rows",
        description =
            "Page flattened rows from a previous run_query by result_id. offset defaults " +
                "to 0 (negative clamps to 0); limit defaults to 50 with a hard cap of 50. " +
                "page_truncated is true when this page is shorter than the remaining in-memory " +
                "sample. Results expire after $resultTtlMinutes minutes. Use summarize_result " +
                "for column stats. Unknown or expired result_id returns not_found.",
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
                                    "Max rows to return. Default 50, hard cap 50.",
                                )
                            },
                        )
                    },
                required = listOf("result_id"),
            ),
        toolAnnotations = ToolAnnotations(readOnlyHint = true),
    ) { request ->
        handleGetResultRows(resultStore, request)
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
        handleSummarizeResult(resultStore, request)
    }
}

private suspend fun handleRunQuery(
    service: SafeDbService,
    resultStore: ResultStore,
    request: CallToolRequest,
): CallToolResult {
    val connectionId =
        requiredText(request, "connection_id")
            ?: return toolError(ERROR_INVALID_ARGUMENTS, "connection_id is required")
    val connection =
        service.listConnections().find { it.id == connectionId }
            ?: return toolError(ERROR_NOT_FOUND, "Connection not found")

    val sql =
        requiredText(request, "sql") ?: return toolError(ERROR_INVALID_ARGUMENTS, "sql is required")

    val confirmation =
        when (val parsed = parseConfirmation(request)) {
            is ConfirmationParse.Missing -> null
            is ConfirmationParse.Invalid -> return parsed.result
            is ConfirmationParse.Ready -> parsed.confirmation
        }

    val prepared = prepareSql(service, connection, sql, request)
    val (spec, parseNotes) =
        when (prepared) {
            is PreparedQuery.Ok -> prepared.spec to prepared.notes
            is PreparedQuery.Err -> return prepared.result
        }

    return try {
        val completed = service.runQuery(QueryRunRequest(connectionId, spec, confirmation))
        successReceipt(completed, parseNotes, resultStore, spec.limit)
    } catch (error: CancellationException) {
        throw error
    } catch (error: QueryFailureException) {
        mapQueryFailure(error)
    } catch (error: Exception) {
        toolError(ERROR_INTERNAL, error.message ?: "run_query failed")
    }
}

private suspend fun prepareSql(
    service: SafeDbService,
    connection: ConnectionDef,
    sql: String,
    request: CallToolRequest,
): PreparedQuery {
    val (schema, settings) =
        try {
            service.getSchema(connection.id) to service.getSettings()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return PreparedQuery.Err(toolError(ERROR_INTERNAL, error.message ?: "run_query failed"))
        }
    val defaultSchema =
        optionalText(request, "default_schema") ?: settingsDefaultSchema(settings, connection.id)
    return when (
        val parsed =
            parseSqlToSpec(
                sql,
                connection.dialect,
                schema,
                defaultSchema,
                mySqlBackslashEscapes(connection),
            )
    ) {
        is SqlParseResult.Failure ->
            PreparedQuery.Err(
                toolError(
                    ERROR_PARSE,
                    parsed.issues.joinToString(separator = " ") { mcpParseMessage(it) },
                )
            )
        is SqlParseResult.Success -> PreparedQuery.Ok(parsed.spec, parsed.notes)
    }
}

private fun mcpParseMessage(issue: SqlIssue): String =
    when {
        issue.code == SqlIssueCode.SchemaRequired ->
            "Pass default_schema from list_tables.schema, or qualify the table as schema.table."
        "Explore" in issue.message -> issue.message.replace("Explore", "summarize_result")
        else -> issue.message
    }

private fun settingsDefaultSchema(settings: Settings, connectionId: String): String? =
    settings.defaultSchema.takeIf { settings.defaultConnectionId == connectionId }

private fun parseConfirmation(request: CallToolRequest): ConfirmationParse {
    val raw =
        request.arguments?.get("confirmation").takeUnless { it == null || it is JsonNull }
            ?: return ConfirmationParse.Missing
    val obj =
        raw as? JsonObject
            ?: return ConfirmationParse.Invalid(toolError(ERROR_PARSE, "confirmation is invalid"))
    return try {
        val payload = toolJson.decodeFromJsonElement(McpQueryConfirmation.serializer(), obj)
        val conditions =
            payload.conditions
                .map {
                    QueryConfirmationCondition(
                        QueryConfirmationReasonCode.valueOf(it.reasonCode),
                        it.conditionKey,
                    )
                }
                .toSet()
        ConfirmationParse.Ready(
            QueryExecutionConfirmation(
                connectionId = payload.connectionId,
                connectionFingerprint = payload.connectionFingerprint,
                queryFingerprint = payload.queryFingerprint,
                conditions = conditions,
            )
        )
    } catch (_: SerializationException) {
        ConfirmationParse.Invalid(toolError(ERROR_PARSE, "confirmation is invalid"))
    } catch (_: IllegalArgumentException) {
        ConfirmationParse.Invalid(toolError(ERROR_PARSE, "confirmation is invalid"))
    }
}

private suspend fun successReceipt(
    completed: QueryRunResult,
    parseNotes: List<String>,
    resultStore: ResultStore,
    appliedLimit: Int,
): CallToolResult {
    val result = completed.queryResult
    val stored = resultStore.put(result)
    val preview = previewRows(result)
    val warnings = buildList {
        addAll(parseNotes)
        addAll(result.warnings)
        if (result.truncated) add(sampleCappedWarning(appliedLimit))
    }
    val receipt =
        QueryReceipt(
            columns = result.columns,
            rowCount = result.rowCount,
            sampleCapped = result.truncated,
            warnings = warnings,
            risk = riskSummary(completed.riskEvaluation),
            preview = preview,
            previewTruncated = preview.size < result.rowCount,
            resultId = stored.resultId,
        )
    return CallToolResult(content = listOf(TextContent(text = toolJson.encodeToString(receipt))))
}

private fun sampleCappedWarning(limit: Int): String =
    "This sample is the full fetch (LIMIT $limit); paging does not get more table rows. " +
        "Raise LIMIT or add filters."

private suspend fun handleGetResultRows(
    resultStore: ResultStore,
    request: CallToolRequest,
): CallToolResult {
    val resultId =
        requiredText(request, "result_id")
            ?: return toolError(ERROR_INVALID_ARGUMENTS, "result_id is required")
    val result = resultStore.get(resultId) ?: return toolError(ERROR_NOT_FOUND, "Result not found")
    val offset = (optionalInt(request, "offset") ?: 0).coerceAtLeast(0)
    val limit =
        (optionalInt(request, "limit") ?: GET_RESULT_ROWS_MAX).coerceIn(1, GET_RESULT_ROWS_MAX)
    val pageRows = flattenRows(result, offset, limit)
    val page =
        ResultRowsPage(
            resultId = resultId,
            offset = offset,
            rowCount = result.rowCount,
            pageTruncated = offset + pageRows.size < result.rowCount,
            rows = pageRows,
        )
    return CallToolResult(content = listOf(TextContent(text = toolJson.encodeToString(page))))
}

private suspend fun handleSummarizeResult(
    resultStore: ResultStore,
    request: CallToolRequest,
): CallToolResult {
    val resultId =
        requiredText(request, "result_id")
            ?: return toolError(ERROR_INVALID_ARGUMENTS, "result_id is required")
    val result = resultStore.get(resultId) ?: return toolError(ERROR_NOT_FOUND, "Result not found")
    val summary =
        ResultSummary(
            resultId = resultId,
            rowCount = result.rowCount,
            sampleCapped = result.truncated,
            columns = summarizeColumns(result),
        )
    return CallToolResult(content = listOf(TextContent(text = toolJson.encodeToString(summary))))
}

private fun mapQueryFailure(failure: QueryFailureException): CallToolResult {
    val warnings = failure.warnings
    return when (val error = failure.queryError) {
        is QueryError.Validation -> toolError(ERROR_VALIDATION, error.message, warnings)
        is QueryError.Compilation -> toolError(ERROR_COMPILATION, error.message, warnings)
        is QueryError.Execution -> toolError(ERROR_EXECUTION, error.message, warnings)
        is QueryError.RiskGate ->
            toolError(
                ERROR_RISK_GATE,
                error.message,
                warnings,
                risk = riskSummary(error.evaluation),
            )
        is QueryError.ConfirmationRequired ->
            toolError(
                ERROR_CONFIRMATION_REQUIRED,
                error.message,
                warnings,
                reasons = error.requirement.reasons.map { it.message },
                confirmation =
                    toolJson
                        .encodeToJsonElement(error.requirement.confirmation.toPayload())
                        .jsonObject,
            )
    }
}

internal fun riskSummary(evaluation: QueryRiskEvaluation): QueryRiskSummary {
    val assessment = evaluation.finalAssessment ?: evaluation.staticAssessment
    return QueryRiskSummary(
        state = evaluation.decision.state.name,
        severity = assessment?.severity?.name,
        score = assessment?.score,
        effectiveGate = evaluation.decision.effectiveGate,
        reasons = evaluation.decision.reasons.map { it.message },
        planStatus = evaluation.planStatus.name,
    )
}

private fun QueryExecutionConfirmation.toPayload(): McpQueryConfirmation =
    McpQueryConfirmation(
        connectionId = connectionId,
        connectionFingerprint = connectionFingerprint,
        queryFingerprint = queryFingerprint,
        conditions =
            conditions
                .map {
                    McpQueryConfirmationCondition(
                        reasonCode = it.reasonCode.name,
                        conditionKey = it.conditionKey,
                    )
                }
                .sortedWith(compareBy({ it.reasonCode }, { it.conditionKey })),
    )

private sealed interface PreparedQuery {
    data class Ok(val spec: QuerySpec, val notes: List<String> = emptyList()) : PreparedQuery

    data class Err(val result: CallToolResult) : PreparedQuery
}

private sealed interface ConfirmationParse {
    data object Missing : ConfirmationParse

    data class Ready(val confirmation: QueryExecutionConfirmation) : ConfirmationParse

    data class Invalid(val result: CallToolResult) : ConfirmationParse
}

@Serializable
internal data class QueryReceipt(
    val columns: List<ResultColumn>,
    @SerialName("row_count") val rowCount: Int,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("sample_capped")
    val sampleCapped: Boolean = false,
    val warnings: List<String>,
    val risk: QueryRiskSummary,
    val preview: List<JsonObject>,
    @SerialName("preview_truncated") val previewTruncated: Boolean,
    @SerialName("result_id") val resultId: String,
)

@Serializable
internal data class ResultRowsPage(
    @SerialName("result_id") val resultId: String,
    val offset: Int,
    @SerialName("row_count") val rowCount: Int,
    @SerialName("page_truncated") val pageTruncated: Boolean,
    val rows: List<JsonObject>,
)

@Serializable
internal data class QueryRiskSummary(
    val state: String,
    val severity: String? = null,
    val score: Int? = null,
    @SerialName("effective_gate") val effectiveGate: QueryRiskGate,
    val reasons: List<String>,
    @SerialName("plan_status") val planStatus: String,
)

@Serializable
internal data class McpQueryConfirmation(
    @SerialName("connection_id") val connectionId: String,
    @SerialName("connection_fingerprint") val connectionFingerprint: String,
    @SerialName("query_fingerprint") val queryFingerprint: String,
    val conditions: List<McpQueryConfirmationCondition> = emptyList(),
)

@Serializable
internal data class McpQueryConfirmationCondition(
    @SerialName("reason_code") val reasonCode: String,
    @SerialName("condition_key") val conditionKey: String,
)
