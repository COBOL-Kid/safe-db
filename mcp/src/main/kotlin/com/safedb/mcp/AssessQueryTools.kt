package com.safedb.mcp

import com.safedb.query.RiskGateState
import com.safedb.service.QueryFailureException
import com.safedb.service.QueryRunRequest
import com.safedb.service.SafeDbService
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonObject

internal fun registerAssessQueryTools(server: Server, service: SafeDbService) {
    server.addTool(
        name = "assess_query",
        description =
            "Assess one SELECT on a saved connection without running it. Parses the SQL exactly " +
                "like run_query, resolves result columns, compiles the statement, and returns " +
                "dialect, columns, compiled_sql, parameter_count, warnings, a slim risk " +
                "summary, and confirmation_required with reasons when the risk gate would " +
                "require confirmation. Unknown extra arguments are invalid_arguments. The " +
                "result includes JSON text and structuredContent with the same object.",
        inputSchema =
            ToolSchema(
                properties =
                    buildJsonObject {
                        put("connection_id", connectionIdProperty())
                        put("sql", sqlProperty("A single SELECT to assess without executing."))
                        put("default_schema", defaultSchemaProperty())
                    },
                required = listOf("connection_id", "sql"),
            ),
        outputSchema =
            objectOutputSchema(
                listOf(
                    "dialect",
                    "columns",
                    "compiled_sql",
                    "parameter_count",
                    "warnings",
                    "risk",
                    "confirmation_required",
                )
            ) {
                put("dialect", stringProp())
                put("columns", arrayProp())
                put("compiled_sql", stringProp())
                put("parameter_count", intProp())
                put("warnings", arrayProp())
                put("risk", objectProp())
                put("confirmation_required", boolProp())
                put("reasons", arrayProp())
            },
        toolAnnotations = ToolAnnotations(readOnlyHint = true),
    ) { request ->
        runMcpTool("assess_query failed") { handleAssessQuery(service, request) }
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
                dialect = resolved.connection.dialect,
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
        objectToolResult(receipt)
    } catch (error: CancellationException) {
        throw error
    } catch (error: QueryFailureException) {
        mapQueryFailure(error)
    }
}
