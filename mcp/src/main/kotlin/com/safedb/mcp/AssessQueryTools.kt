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
import kotlinx.serialization.json.put

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
        outputSchema =
            ToolSchema(
                properties =
                    buildJsonObject {
                        put("dialect", buildJsonObject { put("type", "string") })
                        put("columns", buildJsonObject { put("type", "array") })
                        put("compiled_sql", buildJsonObject { put("type", "string") })
                        put("parameter_count", buildJsonObject { put("type", "integer") })
                        put("warnings", buildJsonObject { put("type", "array") })
                        put("risk", buildJsonObject { put("type", "object") })
                        put("confirmation_required", buildJsonObject { put("type", "boolean") })
                        put("reasons", buildJsonObject { put("type", "array") })
                    },
                required =
                    listOf(
                        "dialect",
                        "columns",
                        "compiled_sql",
                        "parameter_count",
                        "warnings",
                        "risk",
                        "confirmation_required",
                    ),
            ),
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
