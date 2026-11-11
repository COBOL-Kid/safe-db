package com.safedb.mcp

import com.safedb.explore.ExploreConfig
import com.safedb.explore.ExploreMode
import com.safedb.explore.ExploreRecipe
import com.safedb.explore.PivotDimension
import com.safedb.explore.PivotMeasure
import com.safedb.model.ColumnInfo
import com.safedb.model.Dialect
import com.safedb.model.Schema
import com.safedb.model.TableInfo
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.types.GetPromptRequest
import io.modelcontextprotocol.kotlin.sdk.types.GetPromptRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.GetPromptResult
import io.modelcontextprotocol.kotlin.sdk.types.ListPromptsRequest
import io.modelcontextprotocol.kotlin.sdk.types.ListResourceTemplatesRequest
import io.modelcontextprotocol.kotlin.sdk.types.ListResourcesRequest
import io.modelcontextprotocol.kotlin.sdk.types.McpException
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceRequest
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class McpResourcesAndPromptsTest {
    @Test
    fun listsStaticResourcesAndTemplates() = runBlocking {
        val service = resourcesService()
        withTempMcpClient(service) { client ->
            val resources = client.listResources(ListResourcesRequest()).resources
            assertEquals(listOf(CONNECTIONS_RESOURCE_URI), resources.map { it.uri })
            assertEquals("connections", resources.single().name)
            assertEquals("application/json", resources.single().mimeType)

            val templates =
                client.listResourceTemplates(ListResourceTemplatesRequest()).resourceTemplates
            assertEquals(
                listOf(
                    CONNECTION_TABLES_RESOURCE_TEMPLATE,
                    RESULT_SUMMARY_RESOURCE_TEMPLATE,
                ),
                templates.map { it.uriTemplate },
            )
        }
    }

    @Test
    fun readsConnectionsAndConnectionTables() = runBlocking {
        val service = resourcesService()
        withTempMcpClient(service) { client ->
            val connections =
                Json.parseToJsonElement(client.readText(CONNECTIONS_RESOURCE_URI)).jsonArray
            assertEquals(1, connections.size)
            val connection = connections.single().jsonObject
            assertEquals("c1", connection.getValue("id").jsonPrimitive.content)
            assertEquals("Demo", connection.getValue("name").jsonPrimitive.content)
            assertEquals("Postgres", connection.getValue("dialect").jsonPrimitive.content)
            assertEquals("app", connection.getValue("database").jsonPrimitive.content)

            val tables =
                Json.parseToJsonElement(client.readText("safedb://connections/c1/tables")).jsonArray
            val names = tables.map { it.jsonObject.getValue("name").jsonPrimitive.content }
            assertEquals(listOf("events", "customers", "orders"), names)
            assertTrue(
                tables.none {
                    it.jsonObject.getValue("schema").jsonPrimitive.content == "pg_catalog"
                }
            )
            val customers =
                tables
                    .first { it.jsonObject.getValue("name").jsonPrimitive.content == "customers" }
                    .jsonObject
            assertEquals(
                "public.customers",
                customers.getValue("qualified_name").jsonPrimitive.content,
            )
            assertEquals("2", customers.getValue("column_count").jsonPrimitive.content)

            val missingConnection =
                assertFailsWith<McpException> {
                    client.readText("safedb://connections/missing/tables")
                }
            assertEquals("Connection not found", missingConnection.message)
        }
    }

    @Test
    fun connectionTablesResourceHidesDb2InternalCatalogTables() = runBlocking {
        val service = db2ResourcesService()
        withTempMcpClient(service) { client ->
            val tables =
                Json.parseToJsonElement(client.readText("safedb://connections/c1/tables")).jsonArray
            assertEquals(
                listOf("CUSTOMERS"),
                tables.map { it.jsonObject.getValue("name").jsonPrimitive.content },
            )
        }
    }

    @Test
    fun readsAResultSummaryResource() = runBlocking {
        val service = resourcesService()
        withTempMcpClient(service) { client ->
            val resultId =
                client
                    .callTool(
                        "run_query",
                        mapOf(
                            "connection_id" to "c1",
                            "sql" to "SELECT id, email FROM public.customers",
                        ),
                    )
                    .let { result ->
                        Json.parseToJsonElement((result.content.single() as TextContent).text)
                            .jsonObject
                            .getValue("result_id")
                            .jsonPrimitive
                            .content
                    }
            val summary =
                Json.parseToJsonElement(client.readText("safedb://results/$resultId/summary"))
                    .jsonObject
            assertEquals(resultId, summary.getValue("result_id").jsonPrimitive.content)
            assertEquals("2", summary.getValue("row_count").jsonPrimitive.content)
            val columns = summary.getValue("columns").jsonArray
            assertEquals(2, columns.size)
            assertEquals("id", columns[0].jsonObject.getValue("name").jsonPrimitive.content)
            assertEquals(
                "email",
                columns[1].jsonObject.getValue("name").jsonPrimitive.content,
            )

            val missing =
                assertFailsWith<McpException> {
                    client.readText("safedb://results/missing/summary")
                }
            assertEquals("Result not found", missing.message)
        }
    }

    @Test
    fun listsPromptsAndRendersTheirWorkflows() = runBlocking {
        val service = resourcesService()
        withTempMcpClient(service) { client ->
            val prompts = client.listPrompts(ListPromptsRequest()).prompts
            assertEquals(
                listOf(ANALYZE_RESULT_PROMPT, EXPLORE_DATABASE_PROMPT),
                prompts.map { it.name }.sorted(),
            )
            val explore = prompts.first { it.name == EXPLORE_DATABASE_PROMPT }
            assertEquals(false, explore.arguments?.single()?.required)
            assertEquals("connection_id", explore.arguments?.single()?.name)
            val analyze = prompts.first { it.name == ANALYZE_RESULT_PROMPT }
            assertEquals(true, analyze.arguments?.single()?.required)
            assertEquals("result_id", analyze.arguments?.single()?.name)

            val withConnection =
                client.getPrompt(
                    GetPromptRequest(
                        GetPromptRequestParams(
                            name = EXPLORE_DATABASE_PROMPT,
                            arguments = mapOf("connection_id" to "c1"),
                        )
                    )
                )
            assertTrue(promptText(withConnection).contains("c1"))
            assertTrue(promptText(withConnection).contains("list_tables"))

            val withoutConnection =
                client.getPrompt(
                    GetPromptRequest(GetPromptRequestParams(name = EXPLORE_DATABASE_PROMPT))
                )
            assertTrue(promptText(withoutConnection).contains("list_connections"))

            val analyzeResult =
                client.getPrompt(
                    GetPromptRequest(
                        GetPromptRequestParams(
                            name = ANALYZE_RESULT_PROMPT,
                            arguments = mapOf("result_id" to "result-1"),
                        )
                    )
                )
            assertTrue(promptText(analyzeResult).contains("result-1"))
            assertTrue(promptText(analyzeResult).contains("pivot_result"))

            val analyzeWithoutResult =
                client.getPrompt(
                    GetPromptRequest(GetPromptRequestParams(name = ANALYZE_RESULT_PROMPT))
                )
            assertTrue(promptText(analyzeWithoutResult).contains("run_query"))
        }
    }
}

private suspend fun Client.readText(uri: String): String {
    val result = readResource(ReadResourceRequest(ReadResourceRequestParams(uri = uri)))
    return (result.contents.single() as TextResourceContents).text
}

private fun promptText(result: GetPromptResult): String =
    (result.messages.single().content as TextContent).text

private fun db2ResourcesService(): RecordingSafeDbService {
    val service = RecordingSafeDbService()
    service.connections += sampleMcpConnection(dialect = Dialect.Db2)
    val columns = listOf(ColumnInfo(name = "id", dataType = "int", nullable = false))
    service.schemas["c1"] =
        Schema(
            tables =
                listOf(
                    TableInfo(
                        schema = "DB2INST1",
                        name = "CUSTOMERS",
                        columns = columns,
                        indexes = emptyList(),
                    ),
                    TableInfo(
                        schema = "DB2INST1",
                        name = "EXPLAIN_INSTANCE",
                        columns = columns,
                        indexes = emptyList(),
                    ),
                    TableInfo(
                        schema = "DB2INST1",
                        name = "ADVISE_INDEX",
                        columns = columns,
                        indexes = emptyList(),
                    ),
                )
        )
    return service
}

private fun resourcesService(): RecordingSafeDbService {
    val service = RecordingSafeDbService()
    service.connections += sampleMcpConnection()
    service.schemas["c1"] = sampleMcpSchema()
    service.runQueryResult =
        com.safedb.service.QueryRunResult(sampleMcpQueryResult(), allowedMcpRisk())
    service.exploreRecipes +=
        ExploreRecipe(
            id = "r1",
            name = "By region",
            createdAt = "1",
            updatedAt = "2",
            defaultMode = ExploreMode.Pivot,
            pivot =
                ExploreConfig(
                    rowDimensions = listOf(PivotDimension("region")),
                    measures = listOf(PivotMeasure.countRows()),
                ),
        )
    return service
}
