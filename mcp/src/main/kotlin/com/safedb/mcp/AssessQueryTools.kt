package com.safedb.mcp

import com.safedb.query.RiskGateState
import com.safedb.service.QueryFailureException
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
            "Assess a SELECT on a saved connection without running it. Parses the SQL exactly " +
                "like run_query, resolves result columns, compiles the statement, and returns " +
                "dialect, columns, compiled_sql, parameter_count, warnings, a slim risk " +
                "summary, and confirmation_required with reasons when the risk gate would " +
                "require confirmation. Note: unless the risk gate is Disabled, Oracle and Db2 " +
                "EXPLAIN write and delete plan-table rows during assessment. Unknown extra " +
                "arguments are invalid_arguments. The result includes JSON text and " +
                "structuredContent with the same object.",
        inputSchema =
            ToolSchema(
                properties =
                    buildJsonObject {
                        put("connection_id", connectionIdProperty())
                        put("sql", sqlProperty("A SELECT to assess without executing."))
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
                ),
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
        toolAnnotations = ToolAnnotations(readOnlyHint = false),
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
    val connectionId =
        requiredString(request, "connection_id").getOrElse {
            return it
        }
    val sql =
        requiredString(request, "sql").getOrElse {
            return it
        }
    val defaultSchemaArg =
        optionalString(request, "default_schema").getOrElse {
            return it
        }

    return try {
        val resolved =
            service.assessQueryWithSchema(connectionId) { connection, schema, settings ->
                parseSqlSpecOrThrow(sql, defaultSchemaArg, connection, schema, settings)
            }
        val assessment = resolved.assessment
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
    } catch (error: SqlSpecResolutionException) {
        toolError(error.errorCode, error.message)
    } catch (error: QueryFailureException) {
        mapQueryFailure(error)
    } catch (error: IllegalArgumentException) {
        if (error.message == "Connection not found") {
            toolError(ERROR_NOT_FOUND, "Connection not found")
        } else {
            throw error
        }
    }
}
