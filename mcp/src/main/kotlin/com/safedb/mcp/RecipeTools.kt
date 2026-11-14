package com.safedb.mcp

import com.safedb.explore.ExploreMode
import com.safedb.explore.ExploreRecipe
import com.safedb.explore.RecipeField
import com.safedb.explore.WorksheetPreview
import com.safedb.explore.applyExplore
import com.safedb.explore.applyWorksheet
import com.safedb.explore.remapRecipe
import com.safedb.explore.resolveRecipeFields
import com.safedb.explore.withTableAwareLabels
import com.safedb.model.FilterGroup
import com.safedb.model.QueryResult
import com.safedb.model.QuerySpec
import com.safedb.model.ResultColumn
import com.safedb.service.SafeDbService
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

internal fun registerRecipeTools(
    server: Server,
    service: SafeDbService,
    resultStore: ResultStore,
) {
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
                        put("result_id", resultIdProperty())
                        put("recipe_id", stringProp("Recipe id from list_recipes"))
                        put(
                            "mode",
                            stringProp("Optional pivot or worksheet. Defaults to the recipe."),
                        )
                        put(
                            "field_mapping",
                            objectProp("Map required recipe column names to result column names."),
                        )
                    },
                required = listOf("result_id", "recipe_id"),
            ),
        outputSchema =
            objectOutputSchema(listOf("result_id", "columns", "row_count", "warnings", "rows")) {
                put("result_id", stringProp())
                put("columns", arrayProp())
                put("row_count", intProp())
                put("sample_capped", boolProp())
                put("pivot_truncated", boolProp())
                put("worksheet_truncated", boolProp())
                put("warnings", arrayProp())
                put("rows", arrayProp())
            },
        toolAnnotations = ToolAnnotations(readOnlyHint = true),
    ) { request ->
        runMcpTool("apply_recipe failed") { handleApplyRecipe(service, resultStore, request) }
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
        RecipeTargetMode.Pivot -> {
            val config =
                remapped.pivot
                    ?: return toolError(
                        ERROR_VALIDATION,
                        "Recipe '${recipe.id}' has no pivot configuration",
                    )
            val preview =
                applyExplore(
                    entry.result,
                    config.withTableAwareLabels(entry.result.columns, entry.tables),
                )
            pivotResultReceipt(resultId, entry, preview)
        }
        RecipeTargetMode.Worksheet -> {
            val config =
                remapped.worksheet
                    ?: return toolError(
                        ERROR_VALIDATION,
                        "Recipe '${recipe.id}' has no worksheet configuration",
                    )
            val worksheet = applyWorksheet(entry.result, config, entry.tables)
            worksheetResultReceipt(resultId, entry, worksheet)
        }
    }
}

private fun worksheetResultReceipt(
    resultId: String,
    entry: StoredResultEntry,
    worksheet: WorksheetPreview,
): CallToolResult {
    val columns = worksheet.columns.map { ResultColumn(it.sourceColumn ?: it.id, it.dataType) }
    val converted =
        QueryResult(
            columns = columns,
            rows = worksheet.rows.map { row -> row.cells.map { it.value } },
            rowCount = worksheet.rows.size,
            truncated = false,
            warnings = worksheet.warnings,
        )
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
            columns = columns,
            rowCount = worksheet.rows.size,
            sampleCapped = entry.result.truncated,
            worksheetTruncated = slice.moreRowsRemain || slice.cellsTruncated,
            warnings = worksheet.warnings,
            rows = slice.rows,
        )
    return objectToolResult(receipt)
}

private fun parseFieldMapping(objectArg: JsonObject?): Arg<Map<String, String>> {
    if (objectArg == null) return Arg.Ok(emptyMap())
    val mapping = linkedMapOf<String, String>()
    for ((key, element) in objectArg) {
        val primitive = element as? JsonPrimitive
        if (primitive == null || !primitive.isString || primitive.content.isBlank()) {
            return Arg.Err(
                toolError(ERROR_INVALID_ARGUMENTS, "field_mapping values must be strings"),
            )
        }
        mapping[key] = primitive.content
    }
    return Arg.Ok(mapping)
}

private enum class RecipeTargetMode {
    Pivot,
    Worksheet,
}

private fun resolveRecipeMode(modeArg: String?, recipe: ExploreRecipe): Arg<RecipeTargetMode> {
    if (modeArg != null) {
        return when (modeArg.lowercase()) {
            "pivot" -> Arg.Ok(RecipeTargetMode.Pivot)
            "worksheet" -> Arg.Ok(RecipeTargetMode.Worksheet)
            else -> Arg.Err(toolError(ERROR_INVALID_ARGUMENTS, "mode must be pivot or worksheet"))
        }
    }
    return when (recipe.defaultMode) {
        ExploreMode.Pivot -> Arg.Ok(RecipeTargetMode.Pivot)
        ExploreMode.Worksheet -> Arg.Ok(RecipeTargetMode.Worksheet)
        ExploreMode.Visualization ->
            when {
                recipe.pivot != null -> Arg.Ok(RecipeTargetMode.Pivot)
                recipe.worksheet != null -> Arg.Ok(RecipeTargetMode.Worksheet)
                else ->
                    Arg.Err(
                        toolError(
                            ERROR_VALIDATION,
                            "Recipe '${recipe.id}' has no pivot or worksheet configuration",
                        ),
                    )
            }
    }
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
