package com.safedb.mcp

import com.safedb.model.SavedQuery
import com.safedb.platform.DesktopPlatform
import com.safedb.secrets.SecretsManager
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.ContinuationInterceptor
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class McpRuntimeTest {
    @AfterTest
    fun resetSecrets() {
        SecretsManager.initStore("disabled")
    }

    @Test
    fun generatedVersionIsLoadedFromTheRuntimeResource() {
        val version = mcpVersion()
        assertTrue(version.isNotBlank())
        assertFalse(version.contains("\${"))
    }

    @Test
    fun wiredServiceListsEmptyConnections() = runBlocking {
        val dataDir = Files.createTempDirectory("safedb-mcp-service")
        try {
            val service = createMcpService(dataDir)
            assertEquals(emptyList(), service.listConnections())
        } finally {
            dataDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun runtimeInitializesDeterministicFileBackendAndStores() = runBlocking {
        val dataDir = Files.createTempDirectory("safedb-mcp-runtime")
        try {
            val service =
                createMcpRuntime(
                    dataDir,
                    platform = DesktopPlatform.Linux,
                    envValue = "protected",
                )
            assertEquals("file", SecretsManager.activeBackendLabel())
            assertTrue(Files.isDirectory(dataDir.resolve("credentials")))
            assertEquals(emptyList(), service.listConnections())
        } finally {
            dataDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun initializeListsConnectionTools() = runBlocking {
        withTempMcpClient(RecordingSafeDbService()) { client ->
            assertEquals("safe-db", client.serverVersion?.name)
            assertEquals(mcpVersion(), client.serverVersion?.version)
            assertEquals(
                listOf(
                    "apply_recipe",
                    "assess_query",
                    "delete_connection",
                    "delete_saved_query",
                    "describe_table",
                    "export_result",
                    "filter_result_rows",
                    "find_join_paths",
                    "get_result_rows",
                    "list_connections",
                    "list_query_history",
                    "list_recipes",
                    "list_saved_queries",
                    "list_tables",
                    "pivot_result",
                    "run_query",
                    "run_saved_query",
                    "save_query",
                    "search_catalog",
                    "summarize_result",
                ),
                client.listTools().tools.map { it.name }.sorted(),
            )
            assertEquals(MCP_SERVER_INSTRUCTIONS, client.serverInstructions)
            assertEquals(false, client.serverCapabilities?.tools?.listChanged)
            assertEquals(false, client.serverCapabilities?.resources?.listChanged)
            assertEquals(false, client.serverCapabilities?.resources?.subscribe)
            assertEquals(false, client.serverCapabilities?.prompts?.listChanged)
            val unknown = client.callTool("nope_tool", emptyMap())
            assertEquals(true, unknown.isError)
            val unknownBody =
                Json.parseToJsonElement((unknown.content.single() as TextContent).text).jsonObject
            assertEquals("not_found", unknownBody.getValue("error").jsonPrimitive.content)
            assertEquals(
                "Unknown tool 'nope_tool'",
                unknownBody.getValue("message").jsonPrimitive.content,
            )
        }
    }

    @Test
    fun objectToolsPublishOutputSchemaAndStructuredContent() = runBlocking {
        val objectTools =
            setOf(
                "apply_recipe",
                "assess_query",
                "delete_connection",
                "delete_saved_query",
                "describe_table",
                "export_result",
                "filter_result_rows",
                "find_join_paths",
                "get_result_rows",
                "list_connections",
                "list_recipes",
                "list_saved_queries",
                "list_tables",
                "pivot_result",
                "run_query",
                "run_saved_query",
                "save_query",
                "search_catalog",
                "summarize_result",
            )
        val arrayTools = setOf("list_query_history")
        val service = RecordingSafeDbService()
        service.connections += sampleMcpConnection()
        service.schemas["c1"] = sampleMcpSchema()
        service.savedQueries +=
            SavedQuery(
                id = "q1",
                name = "Customers",
                connectionId = "c1",
                spec = sampleMcpQuerySpec(),
                createdAt = "1",
            )
        withTempMcpClient(service) { client ->
            val tools = client.listTools().tools.associateBy { it.name }
            assertEquals(20, tools.size)
            assertEquals(objectTools + arrayTools, tools.keys)
            objectTools.forEach { name -> assertNotNull(tools.getValue(name).outputSchema, name) }
            arrayTools.forEach { name -> assertNull(tools.getValue(name).outputSchema, name) }

            assertStructuredMatchesText(client.callTool("list_connections", emptyMap()))
            assertStructuredMatchesText(client.callTool("list_saved_queries", emptyMap()))
            assertStructuredMatchesText(client.callTool("list_recipes", emptyMap()))
            assertStructuredMatchesText(
                client.callTool("list_tables", mapOf("connection_id" to "c1")),
            )

            val saved =
                client.callTool(
                    "save_query",
                    mapOf(
                        "connection_id" to "c1",
                        "name" to "Orders",
                        "sql" to "SELECT id FROM public.customers",
                    ),
                )
            assertStructuredMatchesText(saved)

            val pending = client.callTool("delete_saved_query", mapOf("query_id" to "q1"))
            val confirmation =
                Json.parseToJsonElement((pending.content.single() as TextContent).text)
                    .jsonObject
                    .getValue("confirmation")
            val deletedSaved =
                client.callTool(
                    "delete_saved_query",
                    mapOf("query_id" to "q1", "confirmation" to confirmation),
                )
            assertStructuredMatchesText(deletedSaved)

            val deletedConnection = client.deleteConnectionConfirmed("c1")
            assertStructuredMatchesText(deletedConnection)
        }
    }

    @Test
    fun toolDispatchCatchesUncaughtHandlerFailureWithoutLeakingRawMessage() = runBlocking {
        val server =
            Server(
                serverInfo = Implementation(name = "safe-db-test", version = "0"),
                options =
                    ServerOptions(
                        capabilities =
                            ServerCapabilities(
                                tools = ServerCapabilities.Tools(listChanged = false),
                            ),
                    ),
            )
        server.addTool(
            name = "boom",
            description = "Throws without runMcpTool",
            inputSchema = ToolSchema(properties = buildJsonObject {}),
        ) { _ ->
            error("/private/store/secret.json failed")
        }
        withMcpClient(server) { client ->
            val result = client.callTool("boom", emptyMap())
            assertEquals(true, result.isError)
            val body = result.json()
            assertEquals("internal", body.getValue("error").jsonPrimitive.content)
            assertEquals(
                "Tool 'boom' failed",
                body.getValue("message").jsonPrimitive.content,
            )
            assertFalse(result.text().contains("/private/store/secret.json"))
        }
    }

    @Test
    fun wiredServiceDispatchesStoreCallsOntoConfiguredDispatcher() = runBlocking {
        val dataDir = Files.createTempDirectory("safedb-mcp-dispatch")
        val dispatched = AtomicBoolean(false)
        val recordingDispatcher =
            object : CoroutineDispatcher() {
                override fun dispatch(
                    context: kotlin.coroutines.CoroutineContext,
                    block: Runnable,
                ) {
                    dispatched.set(true)
                    block.run()
                }
            }
        try {
            val service = createMcpService(dataDir, ioDispatcher = recordingDispatcher)
            assertEquals(emptyList(), service.listConnections())
            assertTrue(dispatched.get())
            assertFalse(
                coroutineContext[ContinuationInterceptor] === recordingDispatcher,
            )
        } finally {
            dataDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun printlnAfterRedirectLandsOnStderrNotProtocolStream() {
        val protocol = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val previous = System.out
        try {
            val protocolStream = PrintStream(protocol, true, Charsets.UTF_8)
            System.setOut(protocolStream)
            redirectStdoutToStderr(stderr)
            println("should-not-leak")
            assertFalse(protocol.toString(Charsets.UTF_8).contains("should-not-leak"))
            assertTrue(stderr.toString(Charsets.UTF_8).contains("should-not-leak"))

            System.setOut(protocolStream)
            redirectStdoutToStderr()
            assertFalse(System.out === protocolStream)
        } finally {
            System.setOut(previous)
        }
    }

    @Test
    fun allToolsExposeExpectedAnnotationsAndSchemas() = runBlocking {
        val readOnlyTools =
            setOf(
                "list_connections",
                "list_tables",
                "describe_table",
                "search_catalog",
                "find_join_paths",
                "get_result_rows",
                "summarize_result",
                "pivot_result",
                "filter_result_rows",
                "list_recipes",
                "apply_recipe",
                "list_saved_queries",
                "list_query_history",
            )
        val destructiveTools = setOf("delete_connection", "delete_saved_query")
        val nonReadOnlyAssessTools = setOf("assess_query")
        val unannotatedTools = setOf("run_query", "save_query", "run_saved_query", "export_result")

        withTempMcpClient(RecordingSafeDbService()) { client ->
            val tools = client.listTools().tools.associateBy { it.name }
            assertEquals(
                readOnlyTools + destructiveTools + nonReadOnlyAssessTools + unannotatedTools,
                tools.keys,
            )
            readOnlyTools.forEach { name ->
                assertEquals(true, tools.getValue(name).annotations?.readOnlyHint, name)
            }
            destructiveTools.forEach { name ->
                assertEquals(true, tools.getValue(name).annotations?.destructiveHint, name)
            }
            nonReadOnlyAssessTools.forEach { name ->
                assertEquals(false, tools.getValue(name).annotations?.readOnlyHint, name)
            }
            unannotatedTools.forEach { name -> assertNull(tools.getValue(name).annotations, name) }
            assertEquals(
                listOf("connection_id", "sql"),
                tools.getValue("run_query").inputSchema.required,
            )
            assertFalse(
                tools.getValue("run_query").inputSchema.properties?.containsKey("spec") == true,
            )
        }
    }

    @Test
    fun parseListPageParamsAndPaginateListValidateAndSlice() {
        val defaults = assertIs<Arg.Ok<ListPageParams>>(parseListPageParams(listRequest()))
        assertEquals(ListPageParams(offset = 0, limit = LIST_DEFAULT_LIMIT), defaults.value)

        val clamped =
            assertIs<Arg.Ok<ListPageParams>>(
                parseListPageParams(listRequest("offset" to 2, "limit" to LIST_MAX_LIMIT + 50)),
            )
        assertEquals(ListPageParams(offset = 2, limit = LIST_MAX_LIMIT), clamped.value)

        listOf(
                listRequest("offset" to -1) to "offset must be 0 or more",
                listRequest("limit" to 0) to "limit must be 1 or more",
                listRequest("offset" to "abc") to "offset must be an integer",
                listRequest("limit" to "abc") to "limit must be an integer",
            )
            .forEach { (request, expectedMessage) ->
                val err = assertIs<Arg.Err>(parseListPageParams(request))
                val msg =
                    Json.parseToJsonElement((err.result.content.single() as TextContent).text)
                        .jsonObject
                        .getValue("message")
                        .jsonPrimitive
                        .content
                assertEquals(expectedMessage, msg)
            }

        val middle = paginateList(listOf("a", "b", "c", "d", "e"), offset = 1, limit = 2)
        assertEquals(
            ListPage(listOf("b", "c"), totalCount = 5, truncated = true, nextOffset = 3),
            middle,
        )

        val end = paginateList(listOf("a", "b", "c", "d", "e"), offset = 3, limit = 2)
        assertEquals(
            ListPage(listOf("d", "e"), totalCount = 5, truncated = false, nextOffset = null),
            end,
        )

        val pastEnd = paginateList(listOf("a", "b"), offset = 5, limit = 2)
        assertEquals(
            ListPage(emptyList(), totalCount = 2, truncated = false, nextOffset = null),
            pastEnd,
        )
    }

    private fun listRequest(vararg entries: Pair<String, Any>): CallToolRequest =
        CallToolRequest(
            CallToolRequestParams(
                name = "list",
                arguments =
                    buildJsonObject {
                        entries.forEach { (key, value) ->
                            when (value) {
                                is Int -> put(key, value)
                                is String -> put(key, JsonPrimitive(value))
                            }
                        }
                    },
            ),
        )
}

private fun assertStructuredMatchesText(result: CallToolResult) {
    assertFalse(result.isError == true)
    val parsed = Json.parseToJsonElement((result.content.single() as TextContent).text).jsonObject
    assertNotNull(result.structuredContent)
    assertEquals(parsed, result.structuredContent)
}
