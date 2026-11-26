package com.safedb.mcp

import com.safedb.explore.DimensionSortMode
import com.safedb.explore.ExploreConfig
import com.safedb.explore.ExploreMode
import com.safedb.explore.ExploreRecipe
import com.safedb.explore.ExploreSort
import com.safedb.explore.ExploreSortTarget
import com.safedb.explore.MeasureFn
import com.safedb.explore.PivotDimension
import com.safedb.explore.PivotFilter
import com.safedb.explore.PivotMeasure
import com.safedb.explore.RecipeField
import com.safedb.explore.SortDir
import com.safedb.explore.ValueFilterOp
import com.safedb.explore.VisualizationConfig
import com.safedb.explore.WorksheetCalculation
import com.safedb.explore.WorksheetConfig
import com.safedb.model.QueryResult
import com.safedb.model.ResultCell
import com.safedb.model.ResultColumn
import com.safedb.service.QueryRunResult
import io.modelcontextprotocol.kotlin.sdk.client.Client
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class RecipeToolsTest {
    @Test
    fun listRecipesMapsSummaryFields() = runBlocking {
        val service = exploreSampleService()
        service.exploreRecipes +=
            sampleRecipe(
                id = "r1",
                description = "",
                defaultMode = ExploreMode.Pivot,
                pivot = ExploreConfig(rowDimensions = listOf(PivotDimension("region"))),
                requiredFields =
                    listOf(
                        RecipeField(
                            column = "region",
                            label = "Region",
                            dataType = "text",
                            sourceTable = "customers",
                        ),
                    ),
            )
        service.exploreRecipes +=
            sampleRecipe(
                id = "r2",
                description = "Worksheet layout",
                defaultMode = ExploreMode.Worksheet,
                pivot = null,
                worksheet = WorksheetConfig(),
                requiredFields = emptyList(),
            )
        withTempMcpClient(service) { client ->
            val response = client.callJson("list_recipes", emptyMap())
            assertEquals("2", response.string("total_count"))
            assertEquals("false", response.string("truncated"))
            assertFalse(response.containsKey("next_offset"))
            val body = response.getValue("recipes").jsonArray
            assertEquals(2, body.size)
            val first = body[0].jsonObject
            assertEquals("r1", first.string("id"))
            assertEquals("By region", first.string("name"))
            assertFalse(first.containsKey("description"))
            assertEquals("pivot", first.string("default_mode"))
            assertEquals("2", first.string("updated_at"))
            val field = first.getValue("required_fields").jsonArray.single().jsonObject
            assertEquals("region", field.string("column"))
            assertEquals("Region", field.string("label"))
            assertEquals("text", field.string("data_type"))
            assertEquals("customers", field.string("source_table"))

            val second = body[1].jsonObject
            assertEquals("Worksheet layout", second.string("description"))
            assertEquals("worksheet", second.string("default_mode"))

            assertError(
                client.callTool("list_recipes", mapOf("bogus" to 1)),
                "invalid_arguments",
                "unknown argument 'bogus'",
            )
        }
    }

    @Test
    fun applyRecipePivotsTheStoredResult() = runBlocking {
        val service = exploreSampleService()
        service.exploreRecipes +=
            sampleRecipe(
                pivot =
                    ExploreConfig(
                        rowDimensions = listOf(PivotDimension("region")),
                        measures = emptyList(),
                        filters =
                            listOf(
                                PivotFilter.Value(
                                    id = "size",
                                    column = "region",
                                    label = "Region",
                                    measureAlias = "count",
                                    op = ValueFilterOp.Between,
                                    value = " 2 ",
                                    secondValue = " 2.0 ",
                                ),
                            ),
                    ),
            )
        withTempMcpClient(service) { client ->
            val resultId = storeRecipeResult(client)
            val result =
                client.callTool(
                    "apply_recipe",
                    mapOf("result_id" to resultId, "recipe_id" to "r1"),
                )
            val body = result.json()
            assertNotNull(result.structuredContent)
            assertEquals(body, result.structuredContent)
            assertEquals(resultId, body.string("result_id"))
            assertEquals(
                listOf("region", "Count"),
                body.getValue("columns").jsonArray.map { it.jsonObject.string("name") },
            )
            assertEquals("3", body.string("row_count"))
            assertFalse(body.containsKey("sample_capped"))
            assertEquals("false", body.string("pivot_truncated"))
            assertEquals(
                listOf("east", "west", "Total"),
                body.getValue("rows").jsonArray.map { it.jsonObject.string("region") },
            )
        }
    }

    @Test
    fun applyRecipeRejectsMalformedStoredPivotConfigs() = runBlocking {
        val service = exploreSampleService()
        val default = ExploreConfig(rowDimensions = listOf(PivotDimension("region")))
        val filter =
            PivotFilter.Value(
                id = "size",
                column = "region",
                label = "Region",
                measureAlias = "count",
                op = ValueFilterOp.Between,
                value = "1",
            )
        val sort = ExploreSort(ExploreSortTarget.Measure("count"), SortDir.Desc)
        val droppedMeasure = PivotMeasure("count", MeasureFn.Sum, "not_in_result")
        val cases =
            listOf(
                default.copy(
                    measures =
                        listOf(
                            PivotMeasure("count", MeasureFn.Sum, "amount"),
                            PivotMeasure.countRows(),
                        ),
                ) to "measures[1].alias reuses measure alias 'count'",
                default.copy(
                    filters = listOf(filter.copy(op = ValueFilterOp.Top, measureAlias = "typo")),
                ) to "filters[0].measure_alias references unknown measure alias 'typo'",
                default.copy(sort = sort.copy(target = ExploreSortTarget.Measure("typo"))) to
                    "sort.measure_alias references unknown measure alias 'typo'",
                default.copy(
                    columnDimensions =
                        listOf(
                            PivotDimension(
                                "created",
                                sortMode = DimensionSortMode.ValueAscending,
                                sortMeasureAlias = "typo",
                            ),
                        ),
                ) to
                    "column_dimensions[0].sort_measure_alias references unknown measure alias 'typo'",
                default.copy(filters = listOf(filter.copy(value = "10O", secondValue = "10"))) to
                    "filters[0].value must be a finite decimal",
                default.copy(filters = listOf(filter.copy(secondValue = "Infinity"))) to
                    "filters[0].second_value must be a finite decimal",
                default.copy(filters = listOf(filter)) to "filters[0].second_value is required",
                default.copy(measures = listOf(droppedMeasure), sort = sort) to
                    "sort.measure_alias references measure alias 'count' without a usable source column",
            )
        cases.forEachIndexed { index, (config, _) ->
            service.exploreRecipes += sampleRecipe(id = "bad-$index", pivot = config)
        }
        service.exploreRecipes +=
            sampleRecipe(id = "fallback", pivot = default.copy(measures = listOf(droppedMeasure)))
        withTempMcpClient(service) { client ->
            val resultId = storeRecipeResult(client)
            cases.forEachIndexed { index, (_, message) ->
                assertError(
                    client.callTool(
                        "apply_recipe",
                        mapOf("result_id" to resultId, "recipe_id" to "bad-$index"),
                    ),
                    "validation",
                    message,
                )
            }
            val fallback =
                client.callJson(
                    "apply_recipe",
                    mapOf("result_id" to resultId, "recipe_id" to "fallback"),
                )
            assertEquals("5", fallback.getValue("rows").jsonArray.last().jsonObject.string("Count"))
            assertTrue(
                fallback
                    .getValue("warnings")
                    .jsonArray
                    .single()
                    .jsonPrimitive
                    .content
                    .contains("unknown source column"),
            )
        }
    }

    @Test
    fun applyRecipeSupportsWorksheetMode() = runBlocking {
        val service = exploreSampleService()
        service.exploreRecipes +=
            sampleRecipe(
                id = "r2",
                defaultMode = ExploreMode.Worksheet,
                pivot = null,
                worksheet = WorksheetConfig(),
                requiredFields = emptyList(),
            )
        withTempMcpClient(service) { client ->
            val resultId = storeRecipeResult(client)
            val worksheetResult =
                client.callTool(
                    "apply_recipe",
                    mapOf("result_id" to resultId, "recipe_id" to "r2"),
                )
            val worksheet = worksheetResult.json()
            assertNotNull(worksheetResult.structuredContent)
            assertEquals(worksheet, worksheetResult.structuredContent)
            assertEquals(resultId, worksheet.string("result_id"))
            assertEquals(
                listOf("region", "amount", "created"),
                worksheet.getValue("columns").jsonArray.map { it.jsonObject.string("name") },
            )
            assertEquals("5", worksheet.string("row_count"))
            assertEquals("false", worksheet.string("worksheet_truncated"))
            assertEquals(5, worksheet.getValue("rows").jsonArray.size)

            val explicit =
                client.callJson(
                    "apply_recipe",
                    mapOf("result_id" to resultId, "recipe_id" to "r2", "mode" to "WORKSHEET"),
                )
            assertEquals("5", explicit.string("row_count"))
        }
    }

    @Test
    fun applyRecipeSummarizesWorksheetErrorsBeyondThePageAndKeepsValidAndNullCells() = runBlocking {
        val service = exploreSampleService()
        val amounts =
            listOf(ResultCell.integer(2), ResultCell.Null) +
                List(GET_RESULT_ROWS_MAX - 2) { ResultCell.integer(2) } +
                List(2) { ResultCell.integer(0) }
        service.runQueryResult =
            QueryRunResult(
                sampleExploreQueryResult()
                    .copy(
                        columns = listOf(ResultColumn("amount", "int")),
                        rows = amounts.map { listOf(it) },
                        rowCount = amounts.size,
                    ),
                allowedMcpRisk(),
            )
        service.exploreRecipes +=
            sampleRecipe(
                defaultMode = ExploreMode.Worksheet,
                pivot = null,
                worksheet =
                    WorksheetConfig(
                        calculations =
                            listOf(
                                WorksheetCalculation.RowFormula(
                                    id = "ratio",
                                    label = "Ratio",
                                    formula = "10 / [amount]",
                                ),
                                WorksheetCalculation.RowFormula(
                                    id = "missing",
                                    label = "Missing",
                                    formula = "[unknown]",
                                ),
                                WorksheetCalculation.RowFormula(
                                    id = "a",
                                    label = "A",
                                    formula = "[b]",
                                ),
                                WorksheetCalculation.RowFormula(
                                    id = "b",
                                    label = "B",
                                    formula = "[a]",
                                ),
                            ),
                    ),
                requiredFields = emptyList(),
            )
        withTempMcpClient(service) { client ->
            val body =
                client.callJson(
                    "apply_recipe",
                    mapOf("result_id" to storeRecipeResult(client), "recipe_id" to "r1"),
                )
            val rows = body.getValue("rows").jsonArray
            assertEquals("true", body.string("worksheet_truncated"))
            assertEquals(GET_RESULT_ROWS_MAX, rows.size)
            assertEquals(amounts.size.toString(), body.string("row_count"))
            assertEquals(5.0, rows[0].jsonObject.getValue("calculation:ratio").jsonPrimitive.double)
            assertEquals(JsonNull, rows[1].jsonObject["calculation:ratio"])
            assertEquals(JsonNull, rows[0].jsonObject["calculation:missing"])
            assertEquals(
                listOf(
                    "Calculated columns contain a circular reference",
                    "Calculation 'Missing' (missing): Unknown field 'unknown' (${amounts.size} cells)",
                    "Calculation 'A' (a): Unknown field 'b' (${amounts.size} cells)",
                    "Calculation 'Ratio' (ratio): Division by zero (2 cells)",
                ),
                body.getValue("warnings").jsonArray.map { it.jsonPrimitive.content },
            )
        }
    }

    @Test
    fun applyRecipeBoundsDistinctWorksheetErrorWarnings() = runBlocking {
        val service = exploreSampleService()
        service.exploreRecipes +=
            sampleRecipe(
                defaultMode = ExploreMode.Worksheet,
                pivot = null,
                worksheet =
                    WorksheetConfig(
                        calculations =
                            List(21) { index ->
                                WorksheetCalculation.RowFormula(
                                    id = "calc-$index",
                                    label = "calc-$index",
                                    formula = "1 / 0",
                                )
                            },
                    ),
                requiredFields = emptyList(),
            )
        withTempMcpClient(service) { client ->
            val body =
                client.callJson(
                    "apply_recipe",
                    mapOf("result_id" to storeRecipeResult(client), "recipe_id" to "r1"),
                )
            assertEquals(
                List(20) { "Calculation 'calc-$it': Division by zero (5 cells)" } +
                    "Additional worksheet calculation errors omitted: 5 cells",
                body.getValue("warnings").jsonArray.map { it.jsonPrimitive.content },
            )
        }
    }

    @Test
    fun applyRecipeUsesPivotForVisualizationRecipeWithPivot() = runBlocking {
        val service = exploreSampleService()
        service.exploreRecipes +=
            sampleRecipe(
                id = "r3",
                defaultMode = ExploreMode.Visualization,
                pivot = ExploreConfig(rowDimensions = listOf(PivotDimension("region"))),
                requiredFields = emptyList(),
            )
        withTempMcpClient(service) { client ->
            val resultId = storeRecipeResult(client)
            val visualizationWithPivot =
                client.callJson(
                    "apply_recipe",
                    mapOf("result_id" to resultId, "recipe_id" to "r3"),
                )
            assertEquals("4", visualizationWithPivot.string("row_count"))
            assertTrue(visualizationWithPivot.containsKey("pivot_truncated"))
        }
    }

    @Test
    fun applyRecipeRejectsVisualizationOnlyRecipe() = runBlocking {
        val service = exploreSampleService()
        service.exploreRecipes +=
            sampleRecipe(
                id = "r4",
                defaultMode = ExploreMode.Visualization,
                pivot = null,
                worksheet = null,
                visualization = VisualizationConfig(),
                requiredFields = emptyList(),
            )
        withTempMcpClient(service) { client ->
            val resultId = storeRecipeResult(client)
            assertError(
                client.callTool(
                    "apply_recipe",
                    mapOf("result_id" to resultId, "recipe_id" to "r4"),
                ),
                "validation",
                "Recipe 'r4' has no pivot or worksheet configuration",
            )
        }
    }

    @Test
    fun applyRecipeWorksheetReceiptUsesSourceColumnNames() = runBlocking {
        val service = exploreSampleService()
        service.runQueryHandler = { QueryRunResult(prefixedRecipeQueryResult(), allowedMcpRisk()) }
        service.exploreRecipes +=
            sampleRecipe(
                id = "r2",
                defaultMode = ExploreMode.Worksheet,
                pivot = null,
                worksheet = WorksheetConfig(),
                requiredFields = emptyList(),
            )
        withTempMcpClient(service) { client ->
            val resultId = storeRecipeResult(client)
            val worksheet =
                client.callJson(
                    "apply_recipe",
                    mapOf("result_id" to resultId, "recipe_id" to "r2"),
                )
            val names = worksheet.getValue("columns").jsonArray.map { it.jsonObject.string("name") }
            assertTrue(names.contains("t0__amount"))
            assertFalse(names.contains("amount"))
        }
    }

    @Test
    fun applyRecipeResolvesManualFieldMappingsAndRejectsUnresolvedFields() = runBlocking {
        val service = exploreSampleService()
        service.exploreRecipes +=
            sampleRecipe(
                requiredFields =
                    listOf(RecipeField(column = "amount_total", label = "Total", dataType = "int")),
                pivot =
                    ExploreConfig(
                        rowDimensions = listOf(PivotDimension("region")),
                        measures =
                            listOf(
                                PivotMeasure(
                                    alias = "total",
                                    fn = MeasureFn.Sum,
                                    sourceColumn = "amount_total",
                                ),
                            ),
                        sort = ExploreSort(ExploreSortTarget.Measure("total"), SortDir.Desc),
                    ),
            )
        withTempMcpClient(service) { client ->
            val resultId = storeRecipeResult(client)
            val unresolved =
                client.callTool(
                    "apply_recipe",
                    mapOf("result_id" to resultId, "recipe_id" to "r1"),
                )
            assertError(
                unresolved,
                "validation",
                "Recipe has unresolved fields: amount_total. " +
                    "Pass field_mapping to map them to result columns.",
            )

            val mapped =
                client.callJson(
                    "apply_recipe",
                    mapOf(
                        "result_id" to resultId,
                        "recipe_id" to "r1",
                        "field_mapping" to Json.parseToJsonElement("""{"amount_total":"amount"}"""),
                    ),
                )
            assertEquals("4", mapped.string("row_count"))
            assertEquals(
                "west",
                mapped.getValue("rows").jsonArray.first().jsonObject.string("region"),
            )

            val unknownKey =
                client.callTool(
                    "apply_recipe",
                    mapOf(
                        "result_id" to resultId,
                        "recipe_id" to "r1",
                        "field_mapping" to Json.parseToJsonElement("""{"nope":"amount"}"""),
                    ),
                )
            assertError(
                unknownKey,
                "invalid_arguments",
                "Unknown field_mapping key 'nope'. Recipe required fields: amount_total",
            )

            val unknownColumn =
                client.callTool(
                    "apply_recipe",
                    mapOf(
                        "result_id" to resultId,
                        "recipe_id" to "r1",
                        "field_mapping" to Json.parseToJsonElement("""{"amount_total":"nope"}"""),
                    ),
                )
            assertTrue(unknownColumn.isError == true)
            assertTrue(
                unknownColumn
                    .json()
                    .string("message")
                    .startsWith("Unknown column 'nope'. Known columns: "),
            )

            val invalidValue =
                client.callTool(
                    "apply_recipe",
                    mapOf(
                        "result_id" to resultId,
                        "recipe_id" to "r1",
                        "field_mapping" to Json.parseToJsonElement("""{"amount_total":5}"""),
                    ),
                )
            assertError(
                invalidValue,
                "invalid_arguments",
                "field_mapping values must be strings",
            )

            val blankValue =
                client.callTool(
                    "apply_recipe",
                    mapOf(
                        "result_id" to resultId,
                        "recipe_id" to "r1",
                        "field_mapping" to Json.parseToJsonElement("""{"amount_total":""}"""),
                    ),
                )
            assertError(
                blankValue,
                "invalid_arguments",
                "field_mapping values must be strings",
            )
        }
    }

    @Test
    fun applyRecipeRequiresConfiguredMode() = runBlocking {
        val service = exploreSampleService()
        service.exploreRecipes +=
            sampleRecipe(
                id = "pivot-only",
                defaultMode = ExploreMode.Pivot,
                pivot =
                    ExploreConfig(
                        rowDimensions = listOf(PivotDimension("region")),
                        measures = listOf(PivotMeasure.countRows()),
                    ),
                worksheet = null,
                requiredFields = emptyList(),
            )
        service.exploreRecipes +=
            sampleRecipe(
                id = "worksheet-only",
                defaultMode = ExploreMode.Worksheet,
                pivot = null,
                worksheet = WorksheetConfig(),
                requiredFields = emptyList(),
            )
        service.exploreRecipes +=
            sampleRecipe(
                id = "pivot-missing",
                defaultMode = ExploreMode.Pivot,
                pivot = null,
                worksheet = null,
                requiredFields = emptyList(),
            )
        withTempMcpClient(service) { client ->
            val resultId = storeRecipeResult(client)
            assertError(
                client.callTool(
                    "apply_recipe",
                    mapOf(
                        "result_id" to resultId,
                        "recipe_id" to "pivot-only",
                        "mode" to "worksheet",
                    ),
                ),
                "validation",
                "Recipe 'pivot-only' has no worksheet configuration",
            )
            assertError(
                client.callTool(
                    "apply_recipe",
                    mapOf(
                        "result_id" to resultId,
                        "recipe_id" to "worksheet-only",
                        "mode" to "pivot",
                    ),
                ),
                "validation",
                "Recipe 'worksheet-only' has no pivot configuration",
            )
            assertError(
                client.callTool(
                    "apply_recipe",
                    mapOf("result_id" to resultId, "recipe_id" to "pivot-missing"),
                ),
                "validation",
                "Recipe 'pivot-missing' has no pivot configuration",
            )
        }
    }

    @Test
    fun applyRecipeHandlesUnknownRecordsAndInvalidMode() = runBlocking {
        val service = exploreSampleService()
        service.exploreRecipes += sampleRecipe()
        withTempMcpClient(service) { client ->
            val unknownResult =
                client.callTool(
                    "apply_recipe",
                    mapOf("result_id" to "missing", "recipe_id" to "r1"),
                )
            assertError(unknownResult, "not_found", "Result not found")

            val resultId = storeRecipeResult(client)
            val unknownRecipe =
                client.callTool(
                    "apply_recipe",
                    mapOf("result_id" to resultId, "recipe_id" to "missing"),
                )
            assertError(unknownRecipe, "not_found", "Recipe not found")

            val invalidMode =
                client.callTool(
                    "apply_recipe",
                    mapOf("result_id" to resultId, "recipe_id" to "r1", "mode" to "chart"),
                )
            assertError(invalidMode, "invalid_arguments", "mode must be pivot or worksheet")

            assertError(
                client.callTool(
                    "apply_recipe",
                    mapOf("result_id" to resultId, "recipe_id" to "r1", "bogus" to 1),
                ),
                "invalid_arguments",
                "unknown argument 'bogus'",
            )
        }
    }

    @Test
    fun listRecipesPaginatesValidatesAndReflectsSharedStore() = runBlocking {
        val service = exploreSampleService()
        repeat(5) { index -> service.exploreRecipes += sampleRecipe(id = "r$index") }
        withTempMcpClient(service, sharesDesktopStore = true) { client ->
            val tool = client.listTools().tools.single { it.name == "list_recipes" }
            assertTrue(tool.description.orEmpty().contains("desktop app"))

            val page = client.callJson("list_recipes", mapOf("offset" to 1, "limit" to 2))
            assertEquals(
                listOf("r1", "r2"),
                page.getValue("recipes").jsonArray.map { it.jsonObject.string("id") },
            )
            assertEquals("5", page.string("total_count"))
            assertEquals("true", page.string("truncated"))
            assertEquals("3", page.string("next_offset"))

            assertError(
                client.callTool("list_recipes", mapOf("limit" to 0)),
                "invalid_arguments",
                "limit must be 1 or more",
            )
        }
    }
}

private fun prefixedRecipeQueryResult(): QueryResult =
    QueryResult(
        columns = listOf(ResultColumn("region", "text"), ResultColumn("t0__amount", "int")),
        rows =
            listOf(
                listOf(ResultCell.text("east"), ResultCell.integer(10)),
                listOf(ResultCell.text("west"), ResultCell.integer(20)),
            ),
        rowCount = 2,
        truncated = false,
        warnings = emptyList(),
    )

private fun sampleRecipe(
    id: String = "r1",
    description: String = "",
    defaultMode: ExploreMode = ExploreMode.Pivot,
    pivot: ExploreConfig? =
        ExploreConfig(
            rowDimensions = listOf(PivotDimension("region")),
            measures = listOf(PivotMeasure.countRows()),
        ),
    worksheet: WorksheetConfig? = null,
    visualization: VisualizationConfig? = null,
    requiredFields: List<RecipeField> =
        listOf(RecipeField(column = "region", label = "Region", dataType = "text")),
): ExploreRecipe =
    ExploreRecipe(
        id = id,
        name = "By region",
        description = description,
        createdAt = "1",
        updatedAt = "2",
        defaultMode = defaultMode,
        pivot = pivot,
        worksheet = worksheet,
        visualization = visualization,
        requiredFields = requiredFields,
    )

private suspend fun storeRecipeResult(client: Client): String {
    val receipt =
        client
            .callTool(
                "run_query",
                mapOf(
                    "connection_id" to "c1",
                    "sql" to "SELECT id, email FROM public.customers",
                ),
            )
            .json()
    return receipt.string("result_id")
}

private suspend fun Client.callJson(name: String, arguments: Map<String, Any?>): JsonObject =
    callTool(name, arguments).json()

private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content
