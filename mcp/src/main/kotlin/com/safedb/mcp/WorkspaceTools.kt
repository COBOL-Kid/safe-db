package com.safedb.mcp

import com.safedb.model.ConnectionDef
import com.safedb.model.HistoryEntry
import com.safedb.model.Outcome
import com.safedb.model.QuerySpec
import com.safedb.model.SavedQuery
import com.safedb.query.specToSql
import com.safedb.query.validateQuery
import com.safedb.service.SafeDbService
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import java.util.UUID
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

internal const val HISTORY_DEFAULT_LIMIT = 20

internal const val HISTORY_MAX_LIMIT = 50

internal fun registerWorkspaceTools(
    server: Server,
    service: SafeDbService,
    resultStore: ResultStore,
    nowMs: () -> Long,
) {
    val deleteTokens = IssuedTokens()

    server.addTool(
        name = "list_saved_queries",
        description =
            "List saved queries from this MCP store as id, name, connection_id, sql, tables, " +
                "and created_at. Pass connection_id from list_connections to filter. Run one " +
                "with run_saved_query, or create one with save_query.",
        inputSchema =
            ToolSchema(
                properties =
                    buildJsonObject { put("connection_id", connectionIdProperty(optional = true)) }
            ),
        toolAnnotations = ToolAnnotations(readOnlyHint = true),
    ) { request ->
        runMcpTool("list_saved_queries failed") {
            requireKnownArguments(request, "connection_id").getOrElse {
                return@runMcpTool it
            }
            val connectionId =
                optionalString(request, "connection_id").getOrElse {
                    return@runMcpTool it
                }
            val connectionsById = service.listConnections().associateBy { it.id }
            val payload =
                toolJson.encodeToString(
                    service
                        .listSavedQueries()
                        .filter { connectionId == null || it.connectionId == connectionId }
                        .map { it.toSummary(connectionsById) }
                )
            CallToolResult(content = listOf(TextContent(text = payload)))
        }
    }

    server.addTool(
        name = "run_saved_query",
        description =
            "Run a saved query by query_id from list_saved_queries on its saved connection. " +
                "Accepts the same confirmation object as run_query. Returns a run_query-style " +
                "receipt with columns, row_count, sample_capped, preview_truncated, warnings, " +
                "risk summary, preview, and result_id. Page with get_result_rows or " +
                "summarize_result. Unknown query_id returns not_found.",
        inputSchema =
            ToolSchema(
                properties =
                    buildJsonObject {
                        put("query_id", stringProp("Saved query id from list_saved_queries"))
                        put("confirmation", confirmationProperty())
                    },
                required = listOf("query_id"),
            ),
        outputSchema = queryReceiptOutputSchema(),
    ) { request ->
        runMcpTool("run_saved_query failed") { handleRunSavedQuery(service, resultStore, request) }
    }

    server.addTool(
        name = "save_query",
        description =
            "Save a validated SELECT for later runs. Pass connection_id from list_connections, " +
                "a name, the sql, and optional default_schema when table names are unqualified. " +
                "Rejects the same SQL run_query rejects. Returns the saved query summary with " +
                "id, connection_id, sql, tables, and created_at. Run it with run_saved_query.",
        inputSchema =
            ToolSchema(
                properties =
                    buildJsonObject {
                        put("connection_id", connectionIdProperty())
                        put("name", stringProp("Display name for the saved query"))
                        put("sql", sqlProperty("A single SELECT accepted by run_query"))
                        put("default_schema", defaultSchemaProperty())
                    },
                required = listOf("connection_id", "name", "sql"),
            ),
        outputSchema =
            objectOutputSchema(
                listOf("id", "name", "connection_id", "sql", "tables", "created_at")
            ) {
                put("id", stringProp())
                put("name", stringProp())
                put("connection_id", stringProp())
                put("sql", stringProp())
                put("tables", arrayProp())
                put("created_at", stringProp())
            },
    ) { request ->
        runMcpTool("save_query failed") { handleSaveQuery(service, request, nowMs) }
    }

    server.addTool(
        name = "delete_saved_query",
        description =
            "Delete a saved query by query_id from list_saved_queries. First call returns " +
                "confirmation_required; show the reasons to the user, then retry with the " +
                "returned confirmation object. Do not auto-confirm or invent the object.",
        inputSchema =
            ToolSchema(
                properties =
                    buildJsonObject {
                        put("query_id", stringProp("Saved query id from list_saved_queries"))
                        put("confirmation", confirmationProperty())
                    },
                required = listOf("query_id"),
            ),
        outputSchema = objectOutputSchema(listOf("deleted")) { put("deleted", stringProp()) },
        toolAnnotations = ToolAnnotations(destructiveHint = true),
    ) { request ->
        handleDeleteSavedQuery(service, deleteTokens, request)
    }

    server.addTool(
        name = "list_query_history",
        description =
            "List recent query history from this MCP store as id, connection_id, " +
                "connection_name, sql, row_count, warnings, optional error, timestamp, and " +
                "optional risk fields. Pass connection_id to filter. limit defaults to " +
                "$HISTORY_DEFAULT_LIMIT and caps at $HISTORY_MAX_LIMIT; 0 or negative is " +
                "invalid_arguments.",
        inputSchema =
            ToolSchema(
                properties =
                    buildJsonObject {
                        put("connection_id", connectionIdProperty(optional = true))
                        put(
                            "limit",
                            boundedLimitProperty(
                                defaultLimit = HISTORY_DEFAULT_LIMIT,
                                maxLimit = HISTORY_MAX_LIMIT,
                                noun = "entries",
                            ),
                        )
                    }
            ),
        toolAnnotations = ToolAnnotations(readOnlyHint = true),
    ) { request ->
        runMcpTool("list_query_history failed") { handleListQueryHistory(service, request) }
    }
}

private suspend fun handleRunSavedQuery(
    service: SafeDbService,
    resultStore: ResultStore,
    request: CallToolRequest,
): CallToolResult {
    requireKnownArguments(request, "query_id", "confirmation").getOrElse {
        return it
    }
    val queryId =
        requiredString(request, "query_id").getOrElse {
            return it
        }
    val saved =
        service.listSavedQueries().find { it.id == queryId }
            ?: return toolError(ERROR_NOT_FOUND, "Saved query not found")
    val connection =
        service.listConnections().find { it.id == saved.connectionId }
            ?: return toolError(ERROR_NOT_FOUND, "Connection not found")
    return executeConfirmedQuery(service, resultStore, connection, saved.spec, request)
}

private suspend fun handleSaveQuery(
    service: SafeDbService,
    request: CallToolRequest,
    nowMs: () -> Long,
): CallToolResult {
    requireKnownArguments(request, "connection_id", "name", "sql", "default_schema").getOrElse {
        return it
    }
    val name =
        requiredString(request, "name").getOrElse {
            return it
        }
    val resolved =
        resolveSqlSpec(service, request).getOrElse {
            return it
        }
    val validatedSpec =
        when (
            val validated =
                validateQuery(
                    resolved.spec,
                    resolved.schema,
                    resolved.settings.blockedSchemas,
                    resolved.connection.dialect,
                )
        ) {
            is Outcome.Ok -> validated.value.first.spec()
            is Outcome.Err -> return toolError(ERROR_VALIDATION, validated.message)
        }

    val saved =
        SavedQuery(
            id = UUID.randomUUID().toString(),
            name = name,
            connectionId = resolved.connectionId,
            spec = validatedSpec,
            createdAt = (nowMs() / 1000).toString(),
        )
    service.saveSavedQuery(saved)
    return objectToolResult(saved.toSummary(mapOf(resolved.connection.id to resolved.connection)))
}

private suspend fun handleDeleteSavedQuery(
    service: SafeDbService,
    tokens: IssuedTokens,
    request: CallToolRequest,
): CallToolResult {
    requireKnownArguments(request, "query_id", "confirmation").getOrElse {
        return it
    }
    val queryId =
        requiredString(request, "query_id").getOrElse {
            return it
        }
    val saved =
        service.listSavedQueries().find { it.id == queryId }
            ?: return toolError(ERROR_NOT_FOUND, "Saved query not found")
    return runDestructiveConfirmedTool(
        request = request,
        tokens = tokens,
        targetId = queryId,
        serializer = McpDeleteSavedQueryConfirmation.serializer(),
        confirmedId = { it.queryId },
        tokenOf = { it.token },
        confirmationRequired = { token -> savedQueryConfirmationRequired(saved, token) },
        failureMessage = "delete_saved_query failed",
    ) {
        service.deleteSavedQuery(queryId)
        objectToolResult(DeletedSavedQuery(queryId))
    }
}

private suspend fun handleListQueryHistory(
    service: SafeDbService,
    request: CallToolRequest,
): CallToolResult {
    requireKnownArguments(request, "connection_id", "limit").getOrElse {
        return it
    }
    val connectionId =
        optionalString(request, "connection_id").getOrElse {
            return it
        }
    val limitArg =
        optionalInt(request, "limit").getOrElse {
            return it
        }
    if (limitArg != null && limitArg <= 0) {
        return toolError(ERROR_INVALID_ARGUMENTS, "limit must be 1 or more")
    }
    val limit = (limitArg ?: HISTORY_DEFAULT_LIMIT).coerceAtMost(HISTORY_MAX_LIMIT)
    val connectionsById = service.listConnections().associateBy { it.id }
    val payload =
        toolJson.encodeToString(
            service
                .listHistory()
                .filter { connectionId == null || it.connectionId == connectionId }
                .take(limit)
                .map { it.toSummary(connectionsById) }
        )
    return CallToolResult(content = listOf(TextContent(text = payload)))
}

private const val MISSING_CONNECTION_SQL = "-- connection not found"

private fun sqlForConnection(spec: QuerySpec, connection: ConnectionDef?): String {
    if (connection == null) return MISSING_CONNECTION_SQL
    return when (val result = specToSql(spec, connection.dialect)) {
        is Outcome.Ok -> result.value
        is Outcome.Err -> "-- invalid query spec: ${result.message}"
    }
}

private fun SavedQuery.toSummary(connectionsById: Map<String, ConnectionDef>): SavedQuerySummary =
    SavedQuerySummary(
        id = id,
        name = name,
        connectionId = connectionId,
        sql = sqlForConnection(spec, connectionsById[connectionId]),
        tables = spec.tables.map { "${it.schema}.${it.name}" },
        createdAt = createdAt,
    )

private fun HistoryEntry.toSummary(
    connectionsById: Map<String, ConnectionDef>
): HistoryEntrySummary =
    HistoryEntrySummary(
        id = id,
        connectionId = connectionId,
        connectionName = connectionName,
        sql = sqlForConnection(spec, connectionsById[connectionId]),
        rowCount = rowCount,
        warnings = warnings,
        error = error,
        timestamp = timestamp,
        riskSeverity = riskSeverity,
        riskFinalScore = riskFinalScore,
        riskGateState = riskGateState,
    )

private fun savedQueryConfirmationRequired(saved: SavedQuery, token: String): CallToolResult {
    val confirmation = McpDeleteSavedQueryConfirmation(saved.id, token)
    return toolError(
        ERROR_CONFIRMATION_REQUIRED,
        "Deleting saved query '${saved.name}' (${saved.id}) removes it from this MCP store. " +
            "Show the user, then retry with the returned confirmation object; do not " +
            "auto-confirm.",
        reasons = listOf("This deletes saved query ${saved.name} (${saved.id})."),
        confirmation = toolJson.encodeToJsonElement(confirmation).jsonObject,
    )
}

@Serializable
internal data class SavedQuerySummary(
    val id: String,
    val name: String,
    @SerialName("connection_id") val connectionId: String,
    val sql: String,
    val tables: List<String>,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
internal data class HistoryEntrySummary(
    val id: String,
    @SerialName("connection_id") val connectionId: String,
    @SerialName("connection_name") val connectionName: String,
    val sql: String,
    @SerialName("row_count") val rowCount: Int,
    val warnings: List<String>,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val error: String? = null,
    val timestamp: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("risk_severity")
    val riskSeverity: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("risk_final_score")
    val riskFinalScore: Int? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("risk_gate_state")
    val riskGateState: String? = null,
)

@Serializable
internal data class McpDeleteSavedQueryConfirmation(
    @SerialName("query_id") val queryId: String,
    val token: String,
)

@Serializable private data class DeletedSavedQuery(val deleted: String)
