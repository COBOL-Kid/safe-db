package com.safedb.mcp

import com.safedb.model.ConnectionDef
import com.safedb.model.MAX_RESULT_BYTES
import com.safedb.model.QueryRiskGate
import com.safedb.model.QuerySpec
import com.safedb.model.ResultColumn
import com.safedb.model.Settings
import com.safedb.model.byteBudgetTruncationWarning
import com.safedb.query.QueryConfirmationCondition
import com.safedb.query.QueryConfirmationReasonCode
import com.safedb.query.QueryError
import com.safedb.query.QueryExecutionConfirmation
import com.safedb.query.QueryRiskEvaluation
import com.safedb.query.RiskGateState
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
            "Run one SELECT on a saved connection. Accepts filters, DISTINCT, GROUP BY of " +
                "selected columns, ORDER BY, LIMIT, and INNER JOIN on indexed or FK columns. " +
                "Rejects aggregates and functions, OFFSET, subqueries, CTEs, UNION, " +
                "LEFT/CROSS or comma joins, AS aliases, and leading NOT. Result columns are " +
                "named table__column. Pass connection_id from list_connections and sql. Do " +
                "not pass a password or URL. Returns a receipt: columns, row_count, " +
                "sample_capped (omitted when false), preview_truncated, warnings, a slim " +
                "risk summary, a short preview (~10 flattened rows), and result_id. " +
                "Page with get_result_rows or summarize_result using result_id. Preview " +
                "and pages are capped at $MCP_PAYLOAD_MAX_BYTES bytes; long cell values " +
                "are truncated with ${MCP_CELL_TRUNCATION_MARKER}. Results expire after " +
                "$resultTtlMinutes minutes ($RESULT_STORE_MAX_ENTRIES entries, " +
                "32 MiB). On error risk_gate, rewrite the query; do not retry the same " +
                "query. On error confirmation_required, " +
                "show the reasons to the user, then retry with the returned confirmation " +
                "object; do not auto-confirm. Unknown extra arguments are invalid_arguments.",
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
    ) { request ->
        runMcpTool("run_query failed") { handleRunQuery(service, resultStore, request) }
    }

    server.addTool(
        name = "assess_query",
        description =
            "Assess one SELECT on a saved connection without running it. Parses the SQL exactly " +
                "like run_query, resolves result columns, compiles the statement, and returns " +
                "columns, compiled_sql, parameter_count, warnings, a slim risk summary, and " +
                "confirmation_required with reasons when the risk gate would require " +
                "confirmation. Unknown extra arguments are invalid_arguments.",
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
                                put("description", "A single SELECT to assess without executing.")
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
                                        "the database name).",
                                )
                            },
                        )
                    },
                required = listOf("connection_id", "sql"),
            ),
        toolAnnotations = ToolAnnotations(readOnlyHint = true),
    ) { request ->
        runMcpTool("assess_query failed") { handleAssessQuery(service, request) }
    }

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

    val confirmation =
        when (val parsed = parseConfirmation(request)) {
            is ConfirmationParse.Missing -> null
            is ConfirmationParse.Invalid -> return parsed.result
            is ConfirmationParse.Ready -> parsed.confirmation
        }

    return try {
        val completed =
            service.runQuery(QueryRunRequest(resolved.connectionId, resolved.spec, confirmation))
        successReceipt(completed, resultStore, resolved.spec, resolved.connectionId)
    } catch (error: CancellationException) {
        throw error
    } catch (error: QueryFailureException) {
        mapQueryFailure(error)
    }
}

private suspend fun handleAssessQuery(
    service: SafeDbService,
    request: CallToolRequest,
): CallToolResult {
    requireKnownArguments(request, "connection_id", "sql", "default_schema").getOrElse {
        return it
    }
    val resolved =
        resolveSqlSpec(service, request).getOrElse {
            return it
        }

    return try {
        val assessment = service.assessQuery(QueryRunRequest(resolved.connectionId, resolved.spec))
        val risk = assessment.riskEvaluation
        val receipt =
            QueryAssessmentReceipt(
                columns = assessment.columns,
                compiledSql = assessment.compiledSql,
                parameterCount = assessment.parameterCount,
                warnings = assessment.warnings,
                risk = riskSummary(risk),
                confirmationRequired = risk.decision.state == RiskGateState.ConfirmationRequired,
                reasons =
                    risk.confirmationRequirement
                        ?.reasons
                        ?.map { it.message }
                        ?.takeIf { it.isNotEmpty() },
            )
        CallToolResult(content = listOf(TextContent(text = toolJson.encodeToString(receipt))))
    } catch (error: CancellationException) {
        throw error
    } catch (error: QueryFailureException) {
        mapQueryFailure(error)
    }
}

internal data class ResolvedSql(
    val connectionId: String,
    val connection: ConnectionDef,
    val spec: QuerySpec,
)

internal suspend fun resolveSqlSpec(
    service: SafeDbService,
    request: CallToolRequest,
): Arg<ResolvedSql> {
    val connectionId =
        requiredString(request, "connection_id").getOrElse {
            return Arg.Err(it)
        }
    val sql =
        requiredString(request, "sql").getOrElse {
            return Arg.Err(it)
        }
    val connection =
        service.listConnections().find { it.id == connectionId }
            ?: return Arg.Err(toolError(ERROR_NOT_FOUND, "Connection not found"))
    return when (val prepared = prepareSql(service, connection, sql, request)) {
        is PreparedQuery.Ok -> Arg.Ok(ResolvedSql(connectionId, connection, prepared.spec))
        is PreparedQuery.Err -> Arg.Err(prepared.result)
    }
}

internal suspend fun prepareSql(
    service: SafeDbService,
    connection: ConnectionDef,
    sql: String,
    request: CallToolRequest,
): PreparedQuery {
    val (schema, settings) = service.getSchema(connection.id) to service.getSettings()
    val defaultSchema =
        optionalString(request, "default_schema").getOrElse {
            return PreparedQuery.Err(it)
        } ?: settingsDefaultSchema(settings, connection.id)
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
        is SqlParseResult.Success -> PreparedQuery.Ok(parsed.spec)
    }
}

internal fun mcpParseMessage(issue: SqlIssue): String =
    when {
        issue.code == SqlIssueCode.SchemaRequired ->
            "Pass default_schema from list_tables.schema, or qualify the table as schema.table."
        "Explore" in issue.message ->
            issue.message.replace("Explore", "pivot_result or summarize_result")
        else -> issue.message
    }

internal fun settingsDefaultSchema(settings: Settings, connectionId: String): String? =
    settings.defaultSchema.takeIf { settings.defaultConnectionId == connectionId }

internal fun parseConfirmation(request: CallToolRequest): ConfirmationParse {
    val obj =
        when (val parsed = optionalObject(request, "confirmation")) {
            is Arg.Err -> return ConfirmationParse.Invalid(parsed.result)
            is Arg.Ok -> parsed.value ?: return ConfirmationParse.Missing
        }
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
        ConfirmationParse.Invalid(toolError(ERROR_INVALID_ARGUMENTS, "confirmation is invalid"))
    } catch (_: IllegalArgumentException) {
        ConfirmationParse.Invalid(toolError(ERROR_INVALID_ARGUMENTS, "confirmation is invalid"))
    }
}

internal suspend fun successReceipt(
    completed: QueryRunResult,
    resultStore: ResultStore,
    spec: QuerySpec,
    connectionId: String,
): CallToolResult {
    val result = completed.queryResult
    val stored = resultStore.put(result, tables = spec.tables, connectionId = connectionId)
    val preview = previewRows(result)
    val warnings = buildList {
        addAll(result.warnings)
        if (result.truncated) add(sampleCappedWarning(spec, result.warnings))
    }
    val receipt =
        QueryReceipt(
            columns = result.columns,
            rowCount = result.rowCount,
            sampleCapped = result.truncated,
            warnings = warnings,
            risk = riskSummary(completed.riskEvaluation),
            preview = preview.rows,
            previewTruncated = preview.moreRowsRemain || preview.cellsTruncated,
            resultId = stored.resultId,
        )
    return CallToolResult(content = listOf(TextContent(text = toolJson.encodeToString(receipt))))
}

private fun sampleCappedWarning(spec: QuerySpec, resultWarnings: List<String>): String {
    // Uncapped SQL stores DEFAULT_LIMIT and ignores it. A byte-budget stop on a real LIMIT
    // has the same shape: raising that LIMIT does not read another row.
    val stoppedAtByteBudget =
        spec.uncapped || resultWarnings.any { it == byteBudgetTruncationWarning() }
    if (stoppedAtByteBudget) {
        return "This sample stopped at the $MAX_RESULT_BYTES encoded-byte budget; paging does not get more table rows. " +
            "Add filters or select fewer columns. Raising LIMIT does not read past this budget."
    }
    return "This sample is the full fetch (LIMIT ${spec.limit}); paging does not get more table rows. " +
        "Raise LIMIT or add filters."
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
    return CallToolResult(content = listOf(TextContent(text = toolJson.encodeToString(page))))
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
    return CallToolResult(content = listOf(TextContent(text = toolJson.encodeToString(summary))))
}

internal fun mapQueryFailure(failure: QueryFailureException): CallToolResult {
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

internal sealed interface PreparedQuery {
    data class Ok(val spec: QuerySpec) : PreparedQuery

    data class Err(val result: CallToolResult) : PreparedQuery
}

internal sealed interface ConfirmationParse {
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
    @EncodeDefault(EncodeDefault.Mode.NEVER) @SerialName("next_offset") val nextOffset: Int? = null,
    val rows: List<JsonObject>,
)

@Serializable
internal data class QueryAssessmentReceipt(
    val columns: List<ResultColumn>,
    @SerialName("compiled_sql") val compiledSql: String,
    @SerialName("parameter_count") val parameterCount: Int,
    val warnings: List<String>,
    val risk: QueryRiskSummary,
    @SerialName("confirmation_required") val confirmationRequired: Boolean,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val reasons: List<String>? = null,
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
