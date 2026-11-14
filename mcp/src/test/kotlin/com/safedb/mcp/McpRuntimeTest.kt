package com.safedb.mcp

import com.safedb.model.SavedQuery
import com.safedb.platform.DesktopPlatform
import com.safedb.secrets.SecretsManager
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
                "list_tables",
                "pivot_result",
                "run_query",
                "run_saved_query",
                "save_query",
                "search_catalog",
                "summarize_result",
            )
        val arrayTools =
            setOf(
                "list_connections",
                "list_query_history",
                "list_recipes",
                "list_saved_queries",
            )
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
}

private fun assertStructuredMatchesText(result: CallToolResult) {
    assertFalse(result.isError == true)
    val parsed = Json.parseToJsonElement((result.content.single() as TextContent).text).jsonObject
    assertNotNull(result.structuredContent)
    assertEquals(parsed, result.structuredContent)
}
