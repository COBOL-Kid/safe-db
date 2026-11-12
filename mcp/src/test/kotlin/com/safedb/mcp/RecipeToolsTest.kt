package com.safedb.mcp

import com.safedb.explore.ExploreConfig
import com.safedb.explore.ExploreMode
import com.safedb.explore.ExploreRecipe
import com.safedb.explore.PivotDimension
import com.safedb.explore.PivotMeasure
import com.safedb.explore.RecipeField
import com.safedb.explore.VisualizationConfig
import com.safedb.explore.WorksheetConfig
import com.safedb.model.QueryResult
import com.safedb.model.ResultCell
import com.safedb.model.ResultColumn
import com.safedb.service.QueryRunResult
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class RecipeToolsTest {
    @Test
    fun listRecipesMapsSummaryFields() = runBlocking {
        val service = recipeService()
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
                        )
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
            val body = client.callTool("list_recipes", emptyMap()).jsonArray()
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
        val service = recipeService()
        service.exploreRecipes +=
            sampleRecipe(
                pivot =
                    ExploreConfig(
                        rowDimensions = listOf(PivotDimension("region")),
                        measures = listOf(PivotMeasure.countRows()),
                    )
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
            assertEquals("4", body.string("row_count"))
            assertFalse(body.containsKey("sample_capped"))
            assertEquals("false", body.string("pivot_truncated"))
            assertTrue(body.getValue("rows").jsonArray.isNotEmpty())
        }
    }

    @Test
    fun applyRecipeSupportsWorksheetMode() = runBlocking {
        val service = recipeService()
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
    fun applyRecipeUsesPivotForVisualizationRecipeWithPivot() = runBlocking {
        val service = recipeService()
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
        val service = recipeService()
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
        val service = recipeService()
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
        val service = recipeService()
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
                                    fn = com.safedb.explore.MeasureFn.Sum,
                                    sourceColumn = "amount_total",
                                )
                            ),
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
            assertTrue(mapped.getValue("rows").jsonArray.isNotEmpty())

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
    fun applyRecipeHandlesUnknownRecordsAndInvalidMode() = runBlocking {
        val service = recipeService()
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
    fun recipeToolsAreReadOnly() = runBlocking {
        withTempMcpClient(recipeService()) { client ->
            val tools = client.listTools().tools.associateBy { it.name }
            assertEquals(true, tools.getValue("list_recipes").annotations?.readOnlyHint)
            assertEquals(true, tools.getValue("apply_recipe").annotations?.readOnlyHint)
        }
    }
}

private fun recipeService(): RecordingSafeDbService {
    val service = RecordingSafeDbService()
    service.connections += sampleMcpConnection()
    service.schemas["c1"] = sampleMcpSchema()
    service.runQueryResult = QueryRunResult(recipeQueryResult(), allowedMcpRisk())
    return service
}

private fun recipeQueryResult(): QueryResult =
    QueryResult(
        columns =
            listOf(
                ResultColumn("region", "text"),
                ResultColumn("amount", "int"),
                ResultColumn("created", "text"),
            ),
        rows =
            listOf(
                listOf(
                    ResultCell.text("east"),
                    ResultCell.integer(10),
                    ResultCell.text("2024-01-05"),
                ),
                listOf(
                    ResultCell.text("east"),
                    ResultCell.integer(20),
                    ResultCell.text("2024-02-15"),
                ),
                listOf(
                    ResultCell.text("west"),
                    ResultCell.integer(30),
                    ResultCell.text("2024-03-10"),
                ),
                listOf(
                    ResultCell.text("west"),
                    ResultCell.integer(40),
                    ResultCell.text("2024-04-20"),
                ),
                listOf(
                    ResultCell.text("north"),
                    ResultCell.integer(50),
                    ResultCell.text("2025-01-01"),
                ),
            ),
        rowCount = 5,
        truncated = false,
        warnings = emptyList(),
    )

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

private fun CallToolResult.json(): JsonObject =
    Json.parseToJsonElement((content.single() as TextContent).text).jsonObject

private fun CallToolResult.jsonArray(): kotlinx.serialization.json.JsonArray =
    Json.parseToJsonElement((content.single() as TextContent).text).jsonArray

private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content

private fun assertError(result: CallToolResult, code: String, message: String) {
    assertEquals(true, result.isError)
    assertEquals(code, result.json().string("error"))
    assertEquals(message, result.json().string("message"))
}
