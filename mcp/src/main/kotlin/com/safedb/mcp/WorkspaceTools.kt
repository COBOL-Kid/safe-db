package com.safedb.mcp

import com.safedb.explore.ExploreConfig
import com.safedb.explore.ExploreMode
import com.safedb.explore.ExploreRecipe
import com.safedb.explore.RecipeField
import com.safedb.explore.WorksheetConfig
import com.safedb.explore.WorksheetPreview
import com.safedb.explore.applyExplore
import com.safedb.explore.applyWorksheet
import com.safedb.explore.remapRecipe
import com.safedb.explore.resolveRecipeFields
import com.safedb.model.ConnectionDef
import com.safedb.model.Dialect
import com.safedb.model.FilterGroup
import com.safedb.model.HistoryEntry
import com.safedb.model.Outcome
import com.safedb.model.QueryResult
import com.safedb.model.QuerySpec
import com.safedb.model.ResultColumn
import com.safedb.model.SavedQuery
import com.safedb.query.specToSql
import com.safedb.query.sql.SqlParseResult
import com.safedb.query.sql.mySqlBackslashEscapes
import com.safedb.query.sql.parseSqlToSpec
import com.safedb.query.validateQuery
import com.safedb.service.QueryFailureException
import com.safedb.service.QueryRunRequest
import com.safedb.service.SafeDbService
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

internal const val HISTORY_DEFAULT_LIMIT = 20

internal const val HISTORY_MAX_LIMIT = 50

internal fun registerWorkspaceTools(
    server: Server,
    service: SafeDbService,
    resultStore: ResultStore,
    nowMs: () -> Long,
) {
    val deleteTokens = SavedQueryConfirmationTokens()

    server.addTool(
        name = "list_saved_queries",
        description =
            "List saved queries from this MCP store as id, name, connection_id, sql, tables, " +
                "and created_at. Pass connection_id from list_connections to filter. Run one " +
                "with run_saved_query, or create one with save_query.",
        inputSchema =
            ToolSchema(
                properties =
                    buildJsonObject {
                        put(
                            "connection_id",
                            buildJsonObject {
                                put("type", "string")
                                put("description", "Optional connection id from list_connections")
                            },
                        )
                    }
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
                        put(
                            "query_id",
                            buildJsonObject {
                                put("type", "string")
                                put("description", "Saved query id from list_saved_queries")
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
                required = listOf("query_id"),
            ),
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
                        put(
                            "connection_id",
                            buildJsonObject {
                                put("type", "string")
                                put("description", "Connection id from list_connections")
                            },
                        )
                        put(
                            "name",
                            buildJsonObject {
                                put("type", "string")
                                put("description", "Display name for the saved query")
                            },
                        )
                        put(
                            "sql",
                            buildJsonObject {
                                put("type", "string")
                                put("description", "A single SELECT accepted by run_query")
                            },
                        )
                        put(
                            "default_schema",
                            buildJsonObject {
                                put("type", "string")
                                put(
                                    "description",
                                    "Schema used when a table name has no schema prefix. Copy " +
                                        "list_tables.schema for this connection.",
                                )
                            },
                        )
                    },
                required = listOf("connection_id", "name", "sql"),
            ),
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
                        put(
                            "query_id",
                            buildJsonObject {
                                put("type", "string")
                                put("description", "Saved query id from list_saved_queries")
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
                required = listOf("query_id"),
            ),
        toolAnnotations = ToolAnnotations(destructiveHint = true),
    ) { request ->
        handleDeleteSavedQuery(service, deleteTokens, request)
    }

    server.addTool(
        name = "list_recipes",
        description =
            "List Explore recipes saved in this MCP store as id, name, description, " +
                "default_mode, required_fields, and updated_at. Apply one to a fetched result " +
                "with apply_recipe.",
        inputSchema = ToolSchema(properties = buildJsonObject {}),
        toolAnnotations = ToolAnnotations(readOnlyHint = true),
    ) { request ->
        runMcpTool("list_recipes failed") {
            requireKnownArguments(request).getOrElse {
                return@runMcpTool it
            }
            val payload =
                toolJson.encodeToString(service.listExploreRecipes().map { it.toSummary() })
            CallToolResult(content = listOf(TextContent(text = payload)))
        }
    }

    server.addTool(
        name = "apply_recipe",
        description =
            "Apply a saved Explore recipe to the in-memory sample of a previous run_query by " +
                "result_id and recipe_id from list_recipes. mode is pivot or worksheet; omit it " +
                "to use the recipe default. field_mapping maps unresolved required fields to " +
                "result columns. Pivot mode returns pivoted columns, row_count, " +
                "pivot_truncated, warnings, and rows. Worksheet mode returns worksheet columns, " +
                "row_count, worksheet_truncated, warnings, and rows. Unknown result_id or " +
                "recipe_id returns not_found.",
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
                            "recipe_id",
                            buildJsonObject {
                                put("type", "string")
                                put("description", "Recipe id from list_recipes")
                            },
                        )
                        put(
                            "mode",
                            buildJsonObject {
                                put("type", "string")
                                put(
                                    "description",
                                    "Optional pivot or worksheet. Defaults to the recipe.",
                                )
                            },
                        )
                        put(
                            "field_mapping",
                            buildJsonObject {
                                put("type", "object")
                                put(
                                    "description",
                                    "Map required recipe column names to result column names.",
                                )
                            },
                        )
                    },
                required = listOf("result_id", "recipe_id"),
            ),
        toolAnnotations = ToolAnnotations(readOnlyHint = true),
    ) { request ->
        runMcpTool("apply_recipe failed") { handleApplyRecipe(service, resultStore, request) }
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
                        put(
                            "connection_id",
                            buildJsonObject {
                                put("type", "string")
                                put("description", "Optional connection id from list_connections")
                            },
                        )
                        put(
                            "limit",
                            buildJsonObject {
                                put("type", "integer")
                                put(
                                    "description",
                                    "Max entries to return (1–$HISTORY_MAX_LIMIT). Default " +
                                        "$HISTORY_DEFAULT_LIMIT. Values over $HISTORY_MAX_LIMIT " +
                                        "cap at $HISTORY_MAX_LIMIT. 0 or negative is " +
                                        "invalid_arguments.",
                                )
                            },
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
    service.listConnections().find { it.id == saved.connectionId }
        ?: return toolError(ERROR_NOT_FOUND, "Connection not found")

    val confirmation =
        when (val parsed = parseConfirmation(request)) {
            is ConfirmationParse.Missing -> null
            is ConfirmationParse.Invalid -> return parsed.result
            is ConfirmationParse.Ready -> parsed.confirmation
        }

    return try {
        val completed =
            service.runQuery(QueryRunRequest(saved.connectionId, saved.spec, confirmation))
        successReceipt(completed, resultStore, saved.spec, saved.connectionId)
    } catch (error: CancellationException) {
        throw error
    } catch (error: QueryFailureException) {
        mapQueryFailure(error)
    }
}

private suspend fun handleSaveQuery(
    service: SafeDbService,
    request: CallToolRequest,
    nowMs: () -> Long,
): CallToolResult {
    requireKnownArguments(request, "connection_id", "name", "sql", "default_schema").getOrElse {
        return it
    }
    val connectionId =
        requiredString(request, "connection_id").getOrElse {
            return it
        }
    val name =
        requiredString(request, "name").getOrElse {
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
    val connection =
        service.listConnections().find { it.id == connectionId }
            ?: return toolError(ERROR_NOT_FOUND, "Connection not found")

    val schema = service.getSchema(connectionId)
    val settings = service.getSettings()
    val defaultSchema = defaultSchemaArg ?: settingsDefaultSchema(settings, connectionId)
    val parsed =
        when (
            val result =
                parseSqlToSpec(
                    sql,
                    connection.dialect,
                    schema,
                    defaultSchema,
                    mySqlBackslashEscapes(connection),
                )
        ) {
            is SqlParseResult.Failure ->
                return toolError(
                    ERROR_PARSE,
                    result.issues.joinToString(separator = " ") { mcpParseMessage(it) },
                )
            is SqlParseResult.Success -> result.spec
        }
    val spec =
        when (
            val validated =
                validateQuery(parsed, schema, settings.blockedSchemas, connection.dialect)
        ) {
            is Outcome.Ok -> validated.value.first.spec()
            is Outcome.Err -> return toolError(ERROR_VALIDATION, validated.message)
        }

    val saved =
        SavedQuery(
            id = UUID.randomUUID().toString(),
            name = name,
            connectionId = connectionId,
            spec = spec,
            createdAt = (nowMs() / 1000).toString(),
        )
    service.saveSavedQuery(saved)
    val payload = toolJson.encodeToString(saved.toSummary(mapOf(connection.id to connection)))
    return CallToolResult(content = listOf(TextContent(text = payload)))
}

private suspend fun handleDeleteSavedQuery(
    service: SafeDbService,
    tokens: SavedQueryConfirmationTokens,
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
    when (val parsed = parseSavedQueryConfirmation(request)) {
        SavedQueryConfirmationParse.Missing -> return savedQueryConfirmationRequired(saved, tokens)
        is SavedQueryConfirmationParse.Invalid -> return parsed.result
        is SavedQueryConfirmationParse.Ready -> {
            if (
                parsed.confirmation.queryId != queryId ||
                    !tokens.matches(queryId, parsed.confirmation.token)
            ) {
                return savedQueryConfirmationRequired(saved, tokens)
            }
        }
    }
    return runMcpTool("delete_saved_query failed") {
        service.deleteSavedQuery(queryId)
        tokens.consume(queryId)
        CallToolResult(
            content =
                listOf(TextContent(text = toolJson.encodeToString(DeletedSavedQuery(queryId))))
        )
    }
}

private suspend fun handleApplyRecipe(
    service: SafeDbService,
    resultStore: ResultStore,
    request: CallToolRequest,
): CallToolResult {
    requireKnownArguments(request, "result_id", "recipe_id", "mode", "field_mapping").getOrElse {
        return it
    }
    val resultId =
        requiredString(request, "result_id").getOrElse {
            return it
        }
    val recipeId =
        requiredString(request, "recipe_id").getOrElse {
            return it
        }
    val modeArg =
        optionalString(request, "mode").getOrElse {
            return it
        }
    val fieldMappingArg =
        optionalObject(request, "field_mapping").getOrElse {
            return it
        }
    val entry =
        resultStore.getEntry(resultId) ?: return toolError(ERROR_NOT_FOUND, "Result not found")
    val recipe =
        service.listExploreRecipes().find { it.id == recipeId }
            ?: return toolError(ERROR_NOT_FOUND, "Recipe not found")

    val manualMapping =
        when (val parsed = parseFieldMapping(fieldMappingArg)) {
            is Arg.Ok -> parsed.value
            is Arg.Err -> return parsed.result
        }
    val mapping =
        resolveRecipeFields(
            recipe,
            entry.result,
            QuerySpec(
                tables = entry.tables,
                filters = FilterGroup(id = "recipe"),
                limit = entry.result.rowCount,
            ),
            manualMapping,
        )
    if (mapping.unresolved.isNotEmpty()) {
        return toolError(
            ERROR_VALIDATION,
            "Recipe has unresolved fields: " +
                "${mapping.unresolved.joinToString { it.column }}. " +
                "Pass field_mapping to map them to result columns.",
        )
    }
    val remapped = remapRecipe(recipe, mapping.resolved)
    val targetMode =
        when (val resolved = resolveRecipeMode(modeArg, remapped)) {
            is Arg.Ok -> resolved.value
            is Arg.Err -> return resolved.result
        }

    return when (targetMode) {
        ExploreMode.Pivot -> {
            val config = remapped.pivot ?: ExploreConfig.defaultFor(entry.result, entry.tables)
            val preview = applyExplore(entry.result, config)
            val slice =
                sliceRows(
                    preview.result,
                    offset = 0,
                    limit = GET_RESULT_ROWS_MAX,
                    maxBytes = MCP_PAYLOAD_MAX_BYTES,
                )
            val receipt =
                PivotResultReceipt(
                    resultId = resultId,
                    columns = preview.result.columns,
                    rowCount = preview.result.rowCount,
                    sampleCapped = entry.result.truncated,
                    pivotTruncated =
                        slice.moreRowsRemain ||
                            slice.cellsTruncated ||
                            preview.layout.overflowMessage != null,
                    warnings = preview.warnings,
                    rows = slice.rows,
                )
            CallToolResult(content = listOf(TextContent(text = toolJson.encodeToString(receipt))))
        }
        ExploreMode.Worksheet -> {
            val config = remapped.worksheet ?: WorksheetConfig()
            val worksheet = applyWorksheet(entry.result, config, entry.tables)
            val converted = worksheet.toQueryResult()
            val slice =
                sliceRows(
                    converted,
                    offset = 0,
                    limit = GET_RESULT_ROWS_MAX,
                    maxBytes = MCP_PAYLOAD_MAX_BYTES,
                )
            val receipt =
                WorksheetResultReceipt(
                    resultId = resultId,
                    columns = converted.columns,
                    rowCount = converted.rowCount,
                    sampleCapped = entry.result.truncated,
                    worksheetTruncated = slice.moreRowsRemain || slice.cellsTruncated,
                    warnings = worksheet.warnings,
                    rows = slice.rows,
                )
            CallToolResult(content = listOf(TextContent(text = toolJson.encodeToString(receipt))))
        }
        ExploreMode.Visualization ->
            // The recipe default can only be Visualization when it also has an explicit mode;
            // resolveRecipeMode never returns Visualization.
            error("Visualization is not a supported recipe target")
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

private fun parseFieldMapping(objectArg: JsonObject?): Arg<Map<String, String>> {
    if (objectArg == null) return Arg.Ok(emptyMap())
    val mapping = linkedMapOf<String, String>()
    for ((key, element) in objectArg) {
        val primitive = element as? JsonPrimitive
        if (primitive == null || !primitive.isString || primitive.content.isBlank()) {
            return Arg.Err(
                toolError(ERROR_INVALID_ARGUMENTS, "field_mapping values must be strings")
            )
        }
        mapping[key] = primitive.content
    }
    return Arg.Ok(mapping)
}

private fun resolveRecipeMode(modeArg: String?, recipe: ExploreRecipe): Arg<ExploreMode> {
    if (modeArg != null) {
        return when (modeArg.lowercase()) {
            "pivot" -> Arg.Ok(ExploreMode.Pivot)
            "worksheet" -> Arg.Ok(ExploreMode.Worksheet)
            else -> Arg.Err(toolError(ERROR_INVALID_ARGUMENTS, "mode must be pivot or worksheet"))
        }
    }
    return Arg.Ok(
        when {
            recipe.defaultMode == ExploreMode.Pivot ||
                (recipe.defaultMode == ExploreMode.Visualization && recipe.pivot != null) ->
                ExploreMode.Pivot
            recipe.worksheet != null -> ExploreMode.Worksheet
            else -> ExploreMode.Pivot
        }
    )
}

private fun WorksheetPreview.toQueryResult(): QueryResult =
    QueryResult(
        columns = columns.map { ResultColumn(it.label, it.dataType) },
        rows = rows.map { row -> row.cells.map { it.value } },
        rowCount = rows.size,
        truncated = false,
        warnings = warnings,
    )

private fun SavedQuery.toSummary(connectionsById: Map<String, ConnectionDef>): SavedQuerySummary {
    val dialect = connectionsById[connectionId]?.dialect ?: Dialect.Postgres
    return SavedQuerySummary(
        id = id,
        name = name,
        connectionId = connectionId,
        sql = specToSql(spec, dialect),
        tables = spec.tables.map { "${it.schema}.${it.name}" },
        createdAt = createdAt,
    )
}

private fun ExploreRecipe.toSummary(): RecipeSummary =
    RecipeSummary(
        id = id,
        name = name,
        description = description.takeIf { it.isNotBlank() },
        defaultMode = defaultMode.name.lowercase(),
        requiredFields = requiredFields.map { it.toSummary() },
        updatedAt = updatedAt,
    )

private fun RecipeField.toSummary(): RecipeFieldSummary =
    RecipeFieldSummary(
        column = column,
        label = label,
        dataType = dataType,
        sourceTable = sourceTable,
    )

private fun HistoryEntry.toSummary(
    connectionsById: Map<String, ConnectionDef>
): HistoryEntrySummary {
    val dialect = connectionsById[connectionId]?.dialect ?: Dialect.Postgres
    return HistoryEntrySummary(
        id = id,
        connectionId = connectionId,
        connectionName = connectionName,
        sql = specToSql(spec, dialect),
        rowCount = rowCount,
        warnings = warnings,
        error = error,
        timestamp = timestamp,
        riskSeverity = riskSeverity,
        riskFinalScore = riskFinalScore,
        riskGateState = riskGateState,
    )
}

private fun savedQueryConfirmationRequired(
    saved: SavedQuery,
    tokens: SavedQueryConfirmationTokens,
): CallToolResult {
    val confirmation = tokens.issue(saved.id)
    return toolError(
        ERROR_CONFIRMATION_REQUIRED,
        "Deleting saved query '${saved.name}' (${saved.id}) removes it from this MCP store. " +
            "Show the user, then retry with the returned confirmation object; do not " +
            "auto-confirm.",
        reasons = listOf("This deletes saved query ${saved.name} (${saved.id})."),
        confirmation = toolJson.encodeToJsonElement(confirmation).jsonObject,
    )
}

private fun parseSavedQueryConfirmation(request: CallToolRequest): SavedQueryConfirmationParse {
    val obj =
        when (val parsed = optionalObject(request, "confirmation")) {
            is Arg.Err -> return SavedQueryConfirmationParse.Invalid(parsed.result)
            is Arg.Ok -> parsed.value ?: return SavedQueryConfirmationParse.Missing
        }
    return try {
        SavedQueryConfirmationParse.Ready(
            toolJson.decodeFromJsonElement(McpDeleteSavedQueryConfirmation.serializer(), obj)
        )
    } catch (_: SerializationException) {
        SavedQueryConfirmationParse.Invalid(
            toolError(ERROR_INVALID_ARGUMENTS, "confirmation is invalid")
        )
    }
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
internal data class RecipeSummary(
    val id: String,
    val name: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val description: String? = null,
    @SerialName("default_mode") val defaultMode: String,
    @SerialName("required_fields") val requiredFields: List<RecipeFieldSummary>,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
internal data class RecipeFieldSummary(
    val column: String,
    val label: String,
    @SerialName("data_type") val dataType: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("source_table")
    val sourceTable: String? = null,
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
internal data class WorksheetResultReceipt(
    @SerialName("result_id") val resultId: String,
    val columns: List<ResultColumn>,
    @SerialName("row_count") val rowCount: Int,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("sample_capped")
    val sampleCapped: Boolean = false,
    @SerialName("worksheet_truncated") val worksheetTruncated: Boolean,
    val warnings: List<String>,
    val rows: List<JsonObject>,
)

@Serializable
internal data class McpDeleteSavedQueryConfirmation(
    @SerialName("query_id") val queryId: String,
    val token: String,
)

@Serializable private data class DeletedSavedQuery(val deleted: String)

private sealed interface SavedQueryConfirmationParse {
    data object Missing : SavedQueryConfirmationParse

    data class Ready(val confirmation: McpDeleteSavedQueryConfirmation) :
        SavedQueryConfirmationParse

    data class Invalid(val result: CallToolResult) : SavedQueryConfirmationParse
}

private class SavedQueryConfirmationTokens {
    private val tokens = ConcurrentHashMap<String, String>()

    fun issue(queryId: String): McpDeleteSavedQueryConfirmation {
        val token = UUID.randomUUID().toString()
        tokens[queryId] = token
        return McpDeleteSavedQueryConfirmation(queryId, token)
    }

    fun matches(queryId: String, token: String): Boolean = tokens[queryId] == token

    fun consume(queryId: String) {
        tokens.remove(queryId)
    }
}
