package com.safedb.mcp

import com.safedb.explore.ExploreConfig
import com.safedb.explore.ExploreMode
import com.safedb.explore.ExploreRecipe
import com.safedb.explore.PivotDimension
import com.safedb.explore.PivotMeasure
import com.safedb.explore.RecipeField
import com.safedb.explore.VisualizationConfig
import com.safedb.explore.WorksheetConfig
import com.safedb.model.HistoryEntry
import com.safedb.model.QueryResult
import com.safedb.model.ResultCell
import com.safedb.model.ResultColumn
import com.safedb.model.SavedQuery
import com.safedb.model.Settings
import com.safedb.query.QueryExecutionConfirmation
import com.safedb.query.specToSql
import com.safedb.service.QueryRunResult
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class WorkspaceToolsTest {
    @Test
    fun listSavedQueriesMapsSummariesAndFiltersByConnection() = runBlocking {
        val service = workspaceService()
        service.savedQueries += savedQuery("q1", "Customers", "c1", createdAt = "100")
        service.savedQueries += savedQuery("q2", "Remote", "missing", createdAt = "200")

        withTempMcpClient(service) { client ->
            val all = client.callTool("list_saved_queries", emptyMap()).jsonArray()
            assertEquals(2, all.size)
            val first = all[0].jsonObject
            assertEquals("q1", first.string("id"))
            assertEquals("Customers", first.string("name"))
            assertEquals("c1", first.string("connection_id"))
            assertEquals(
                specToSql(sampleMcpQuerySpec(), service.connections.single().dialect),
                first.string("sql"),
            )
            assertEquals(
                listOf("public.customers"),
                first.getValue("tables").jsonArray.map { it.jsonPrimitive.content },
            )
            assertEquals("100", first.string("created_at"))

            val filtered =
                client.callTool("list_saved_queries", mapOf("connection_id" to "c1")).jsonArray()
            assertEquals(1, filtered.size)
            assertEquals("q1", filtered.single().jsonObject.string("id"))

            val unknownArgument = client.callTool("list_saved_queries", mapOf("bogus" to true))
            assertError(unknownArgument, "invalid_arguments", "unknown argument 'bogus'")
        }
    }

    @Test
    fun runSavedQueryRunsTheStoredSpecAndParsesConfirmation() = runBlocking {
        val service = workspaceService()
        val spec = sampleMcpQuerySpec()
        service.savedQueries += savedQuery("q1", "Customers", "c1", spec = spec)
        var confirmation: QueryExecutionConfirmation? = null
        service.runQueryHandler = { request ->
            confirmation = request.confirmation
            QueryRunResult(workspaceQueryResult(), allowedMcpRisk())
        }

        withTempMcpClient(service) { client ->
            val receipt =
                client.callJson(
                    "run_saved_query",
                    mapOf(
                        "query_id" to "q1",
                        "confirmation" to
                            Json.parseToJsonElement(
                                """{"connection_id":"c1","connection_fingerprint":"cf","query_fingerprint":"qf","conditions":[]}"""
                            ),
                    ),
                )
            assertTrue(receipt.containsKey("result_id"))
            assertEquals("5", receipt.string("row_count"))
            assertEquals("c1", service.runQueryRequests.single().connectionId)
            assertEquals(spec, service.runQueryRequests.single().spec)
            assertEquals("c1", confirmation?.connectionId)
            assertEquals("cf", confirmation?.connectionFingerprint)
            assertEquals("qf", confirmation?.queryFingerprint)
        }
    }

    @Test
    fun runSavedQueryHandlesMissingRecordsAndInvalidConfirmation() = runBlocking {
        val service = workspaceService()
        service.savedQueries += savedQuery("q1", "Customers", "c1")
        service.savedQueries += savedQuery("q2", "Remote", "missing")
        withTempMcpClient(service) { client ->
            val unknown = client.callTool("run_saved_query", mapOf("query_id" to "nope"))
            assertError(unknown, "not_found", "Saved query not found")

            val unknownConnection = client.callTool("run_saved_query", mapOf("query_id" to "q2"))
            assertError(unknownConnection, "not_found", "Connection not found")

            val invalidConfirmation =
                client.callTool(
                    "run_saved_query",
                    mapOf("query_id" to "q1", "confirmation" to "nope"),
                )
            assertError(invalidConfirmation, "invalid_arguments", "confirmation must be an object")

            val malformedConfirmation =
                client.callTool(
                    "run_saved_query",
                    mapOf("query_id" to "q1", "confirmation" to Json.parseToJsonElement("{}")),
                )
            assertError(malformedConfirmation, "invalid_arguments", "confirmation is invalid")
        }
    }

    @Test
    fun saveQueryValidatesAndPersistsTheNormalizedSpec() = runBlocking {
        val service = workspaceService()
        withTempMcpClient(service) { client ->
            val summary =
                client.callJson(
                    "save_query",
                    mapOf(
                        "connection_id" to "c1",
                        "name" to "Customers",
                        "sql" to "SELECT id, email FROM public.customers",
                    ),
                )
            assertEquals("Customers", summary.string("name"))
            assertEquals("c1", summary.string("connection_id"))
            assertEquals(
                listOf("public.customers"),
                summary.getValue("tables").jsonArray.map { it.jsonPrimitive.content },
            )
            assertTrue(summary.string("sql").contains("SELECT"))
            val saved = service.savedQueries.single()
            assertEquals("Customers", saved.name)
            assertEquals("c1", saved.connectionId)
            assertEquals(2, saved.spec.columns.size)
        }
    }

    @Test
    fun saveQueryUsesDefaultSchemaAndRejectsInvalidSql() = runBlocking {
        val service = workspaceService()
        service.settings = Settings(defaultConnectionId = "c1", defaultSchema = "public")
        withTempMcpClient(service) { client ->
            val summary =
                client.callJson(
                    "save_query",
                    mapOf(
                        "connection_id" to "c1",
                        "name" to "Customers",
                        "sql" to "SELECT id FROM customers",
                    ),
                )
            assertEquals("Customers", summary.string("name"))

            val explicitDefault =
                client.callJson(
                    "save_query",
                    mapOf(
                        "connection_id" to "c1",
                        "name" to "Orders",
                        "sql" to "SELECT id FROM orders",
                        "default_schema" to "public",
                    ),
                )
            assertEquals("Orders", explicitDefault.string("name"))

            val parse =
                client.callTool(
                    "save_query",
                    mapOf(
                        "connection_id" to "c1",
                        "name" to "Bad",
                        "sql" to "DELETE FROM public.customers",
                    ),
                )
            assertEquals("parse", parse.json().string("error"))

            val blocked =
                client.callTool(
                    "save_query",
                    mapOf(
                        "connection_id" to "c1",
                        "name" to "Blocked",
                        "sql" to "SELECT oid FROM pg_catalog.pg_class",
                    ),
                )
            assertEquals("validation", blocked.json().string("error"))
            assertTrue(blocked.json().string("message").contains("blocked"))

            val unknownConnection =
                client.callTool(
                    "save_query",
                    mapOf(
                        "connection_id" to "nope",
                        "name" to "X",
                        "sql" to "SELECT id FROM customers",
                    ),
                )
            assertError(unknownConnection, "not_found", "Connection not found")
        }
    }

    @Test
    fun deleteSavedQueryRequiresAndConsumesTheConfirmationToken() = runBlocking {
        val service = workspaceService()
        service.savedQueries += savedQuery("q1", "Customers", "c1")
        withTempMcpClient(service) { client ->
            val pending = client.callTool("delete_saved_query", mapOf("query_id" to "q1"))
            assertError(pending, "confirmation_required", pending.json().string("message"))
            assertEquals("q1", service.savedQueries.single().id)
            val confirmation = pending.json().getValue("confirmation")

            val deleted =
                client.callTool(
                    "delete_saved_query",
                    mapOf("query_id" to "q1", "confirmation" to confirmation),
                )
            assertFalse(deleted.isError == true)
            assertEquals("q1", deleted.json().string("deleted"))
            assertTrue(service.savedQueries.isEmpty())
        }
    }

    @Test
    fun deleteSavedQueryRejectsStaleTokensAndMalformedConfirmations() = runBlocking {
        val service = workspaceService()
        service.savedQueries += savedQuery("q1", "Customers", "c1")
        withTempMcpClient(service) { client ->
            val first =
                client
                    .callTool("delete_saved_query", mapOf("query_id" to "q1"))
                    .json()
                    .getValue("confirmation")
            val second =
                client
                    .callTool("delete_saved_query", mapOf("query_id" to "q1"))
                    .json()
                    .getValue("confirmation")
            assertFalse(first == second)

            val stale =
                client.callTool(
                    "delete_saved_query",
                    mapOf("query_id" to "q1", "confirmation" to first),
                )
            assertError(stale, "confirmation_required", stale.json().string("message"))

            val malformed =
                client.callTool(
                    "delete_saved_query",
                    mapOf(
                        "query_id" to "q1",
                        "confirmation" to Json.parseToJsonElement("""{"query_id":"q1"}"""),
                    ),
                )
            assertError(malformed, "invalid_arguments", "confirmation is invalid")

            val unknown = client.callTool("delete_saved_query", mapOf("query_id" to "nope"))
            assertError(unknown, "not_found", "Saved query not found")
        }
    }

    @Test
    fun listRecipesMapsSummaryFields() = runBlocking {
        val service = workspaceService()
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
        val service = workspaceService()
        service.exploreRecipes +=
            sampleRecipe(
                pivot =
                    ExploreConfig(
                        rowDimensions = listOf(PivotDimension("region")),
                        measures = listOf(PivotMeasure.countRows()),
                    )
            )
        withTempMcpClient(service) { client ->
            val resultId = storeWorkspaceResult(client)
            val body =
                client.callJson(
                    "apply_recipe",
                    mapOf("result_id" to resultId, "recipe_id" to "r1"),
                )
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
    fun applyRecipeSupportsWorksheetModeAndVisualizationDefaults() = runBlocking {
        val service = workspaceService()
        service.exploreRecipes +=
            sampleRecipe(
                id = "r2",
                defaultMode = ExploreMode.Worksheet,
                pivot = null,
                worksheet = WorksheetConfig(),
                requiredFields = emptyList(),
            )
        service.exploreRecipes +=
            sampleRecipe(
                id = "r3",
                defaultMode = ExploreMode.Visualization,
                pivot = ExploreConfig(rowDimensions = listOf(PivotDimension("region"))),
                requiredFields = emptyList(),
            )
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
            val resultId = storeWorkspaceResult(client)
            val worksheet =
                client.callJson(
                    "apply_recipe",
                    mapOf("result_id" to resultId, "recipe_id" to "r2"),
                )
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

            val visualizationWithPivot =
                client.callJson(
                    "apply_recipe",
                    mapOf("result_id" to resultId, "recipe_id" to "r3"),
                )
            assertEquals("4", visualizationWithPivot.string("row_count"))
            assertTrue(visualizationWithPivot.containsKey("pivot_truncated"))

            val visualizationWithoutPivot =
                client.callJson(
                    "apply_recipe",
                    mapOf("result_id" to resultId, "recipe_id" to "r4"),
                )
            assertTrue(visualizationWithoutPivot.containsKey("pivot_truncated"))
        }
    }

    @Test
    fun applyRecipeResolvesManualFieldMappingsAndRejectsUnresolvedFields() = runBlocking {
        val service = workspaceService()
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
            val resultId = storeWorkspaceResult(client)
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
        val service = workspaceService()
        service.exploreRecipes += sampleRecipe()
        withTempMcpClient(service) { client ->
            val unknownResult =
                client.callTool(
                    "apply_recipe",
                    mapOf("result_id" to "missing", "recipe_id" to "r1"),
                )
            assertError(unknownResult, "not_found", "Result not found")

            val resultId = storeWorkspaceResult(client)
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
    fun listQueryHistoryDefaultsCapsFiltersAndMapsFields() = runBlocking {
        val service = workspaceService()
        repeat(60) { index ->
            service.historyEntries += historyEntry("h$index", connectionId = "c1")
        }
        service.historyEntries +=
            historyEntry(
                "h-error",
                connectionId = "c2",
                connectionName = "Remote",
                error = "boom",
                riskSeverity = null,
            )
        withTempMcpClient(service) { client ->
            val default = client.callTool("list_query_history", emptyMap()).jsonArray()
            assertEquals(HISTORY_DEFAULT_LIMIT, default.size)
            val first = default[0].jsonObject
            assertEquals("h0", first.string("id"))
            assertEquals("c1", first.string("connection_id"))
            assertEquals("Demo", first.string("connection_name"))
            assertEquals("5", first.string("row_count"))
            assertEquals("Low", first.string("risk_severity"))
            assertEquals("7", first.string("risk_final_score"))
            assertEquals("Allowed", first.string("risk_gate_state"))
            assertEquals(
                specToSql(sampleMcpQuerySpec(), service.connections.single().dialect),
                first.string("sql"),
            )
            assertFalse(first.containsKey("error"))

            val capped = client.callTool("list_query_history", mapOf("limit" to 100)).jsonArray()
            assertEquals(HISTORY_MAX_LIMIT, capped.size)

            val limited = client.callTool("list_query_history", mapOf("limit" to 2)).jsonArray()
            assertEquals(2, limited.size)

            val filtered =
                client.callTool("list_query_history", mapOf("connection_id" to "c2")).jsonArray()
            assertEquals(1, filtered.size)
            val filteredEntry = filtered.single().jsonObject
            assertEquals("h-error", filteredEntry.string("id"))
            assertEquals("Remote", filteredEntry.string("connection_name"))
            assertEquals("boom", filteredEntry.string("error"))
            assertFalse(filteredEntry.containsKey("risk_severity"))

            assertError(
                client.callTool("list_query_history", mapOf("limit" to 0)),
                "invalid_arguments",
                "limit must be 1 or more",
            )
            assertError(
                client.callTool("list_query_history", mapOf("bogus" to true)),
                "invalid_arguments",
                "unknown argument 'bogus'",
            )
        }
    }
}

private fun workspaceService(): RecordingSafeDbService {
    val service = RecordingSafeDbService()
    service.connections += sampleMcpConnection()
    service.schemas["c1"] = sampleMcpSchema()
    service.runQueryResult = QueryRunResult(workspaceQueryResult(), allowedMcpRisk())
    return service
}

private fun workspaceQueryResult(): QueryResult =
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

private fun savedQuery(
    id: String,
    name: String,
    connectionId: String,
    spec: com.safedb.model.QuerySpec = sampleMcpQuerySpec(),
    createdAt: String = "1",
): SavedQuery =
    SavedQuery(
        id = id,
        name = name,
        connectionId = connectionId,
        spec = spec,
        createdAt = createdAt,
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

private fun historyEntry(
    id: String,
    connectionId: String = "c1",
    connectionName: String = "Demo",
    error: String? = null,
    riskSeverity: String? = "Low",
): HistoryEntry =
    HistoryEntry(
        id = id,
        connectionId = connectionId,
        connectionName = connectionName,
        spec = sampleMcpQuerySpec(),
        rowCount = 5,
        warnings = listOf("sample capped"),
        error = error,
        timestamp = id,
        riskSeverity = riskSeverity,
        riskFinalScore = 7,
        riskGateState = "Allowed",
    )

private suspend fun storeWorkspaceResult(client: Client): String {
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
