package com.safedb.mcp

import com.safedb.model.ConnectionDef
import com.safedb.model.Dialect
import com.safedb.model.MAX_RESULT_BYTES
import com.safedb.model.QueryRiskGate
import com.safedb.model.QuerySpec
import com.safedb.model.ResultColumn
import com.safedb.model.Schema
import com.safedb.model.Settings
import com.safedb.model.byteBudgetTruncationWarning
import com.safedb.query.LARGE_LIMIT_WARNING
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
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

internal suspend fun executeConfirmedQuery(
    service: SafeDbService,
    resultStore: ResultStore,
    connection: ConnectionDef,
    spec: QuerySpec,
    request: CallToolRequest,
): CallToolResult {
    val confirmation =
        parseConfirmation(request).getOrElse {
            return it
        }

    return try {
        val completed = service.runQuery(QueryRunRequest(connection.id, spec, confirmation))
        successReceipt(completed, resultStore, spec, connection)
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
    val schema: Schema,
    val settings: Settings,
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
    val schema = service.getSchema(connection.id)
    val settings = service.getSettings()
    val defaultSchema =
        optionalString(request, "default_schema").getOrElse {
            return Arg.Err(it)
        } ?: settingsDefaultSchema(settings, connection.id)
    val spec =
        when (
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
                return Arg.Err(
                    toolError(
                        ERROR_PARSE,
                        parsed.issues.joinToString(separator = " ") { mcpParseMessage(it) },
                    ),
                )
            is SqlParseResult.Success -> parsed.spec
        }
    return Arg.Ok(ResolvedSql(connectionId, connection, spec, schema, settings))
}

internal fun mcpParseMessage(issue: SqlIssue): String =
    when {
        issue.code == SqlIssueCode.SchemaRequired ->
            "Pass default_schema from list_tables.tables[].schema, or qualify the table as schema.table."
        "Explore" in issue.message ->
            issue.message.replace("Explore", "pivot_result or summarize_result")
        else -> issue.message
    }

internal fun settingsDefaultSchema(settings: Settings, connectionId: String): String? =
    settings.defaultSchema.takeIf { settings.defaultConnectionId == connectionId }

internal fun parseConfirmation(request: CallToolRequest): Arg<QueryExecutionConfirmation?> {
    val obj =
        optionalObject(request, "confirmation").getOrElse {
            return Arg.Err(it)
        } ?: return Arg.Ok(null)
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
        Arg.Ok(
            QueryExecutionConfirmation(
                connectionId = payload.connectionId,
                connectionFingerprint = payload.connectionFingerprint,
                queryFingerprint = payload.queryFingerprint,
                conditions = conditions,
            ),
        )
    } catch (_: IllegalArgumentException) {
        Arg.Err(toolError(ERROR_INVALID_ARGUMENTS, "confirmation is invalid"))
    }
}

internal suspend fun successReceipt(
    completed: QueryRunResult,
    resultStore: ResultStore,
    spec: QuerySpec,
    connection: ConnectionDef,
): CallToolResult {
    val result = completed.queryResult
    val stored = resultStore.put(result, tables = spec.tables)
    val preview = previewRows(result)
    val emptySample = result.rowCount == 0
    val warnings = buildList {
        addAll(result.warnings.filterNot { emptySample && it == LARGE_LIMIT_WARNING })
        if (result.truncated) add(sampleCappedWarning(spec, result.warnings))
    }
    val receipt =
        QueryReceipt(
            dialect = connection.dialect,
            columns = result.columns,
            rowCount = result.rowCount,
            sampleCapped = result.truncated,
            sampleCapReason =
                if (result.truncated) sampleCapReason(spec, result.warnings) else null,
            sampleUnbounded = spec.uncapped && !emptySample,
            warnings = warnings,
            risk = riskSummary(completed.riskEvaluation),
            preview = preview.rows,
            previewTruncated = preview.moreRowsRemain || preview.cellsTruncated,
            resultId = stored.resultId,
        )
    return objectToolResult(receipt)
}

private fun stoppedAtByteBudget(spec: QuerySpec, resultWarnings: List<String>): Boolean =
    // Uncapped SQL stores DEFAULT_LIMIT and ignores it. A byte-budget stop on a real LIMIT
    // has the same shape: raising that LIMIT does not read another row.
    spec.uncapped || resultWarnings.any { it == byteBudgetTruncationWarning() }

private fun sampleCapReason(spec: QuerySpec, resultWarnings: List<String>): String =
    if (stoppedAtByteBudget(spec, resultWarnings)) "byte_budget" else "sql_limit"

private fun sampleCappedWarning(spec: QuerySpec, resultWarnings: List<String>): String {
    if (stoppedAtByteBudget(spec, resultWarnings)) {
        return "This sample stopped at the $MAX_RESULT_BYTES encoded-byte budget; paging does not get more table rows. " +
            "Add filters or select fewer columns. Raising LIMIT does not read past this budget."
    }
    return "This sample is the full fetch (LIMIT ${spec.limit}); paging does not get more table rows. " +
        "Raise LIMIT or add filters."
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

@Serializable
internal data class QueryReceipt(
    val dialect: Dialect,
    val columns: List<ResultColumn>,
    @SerialName("row_count") val rowCount: Int,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("sample_capped")
    val sampleCapped: Boolean = false,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("sample_cap_reason")
    val sampleCapReason: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("sample_unbounded")
    val sampleUnbounded: Boolean = false,
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
    val dialect: Dialect,
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
