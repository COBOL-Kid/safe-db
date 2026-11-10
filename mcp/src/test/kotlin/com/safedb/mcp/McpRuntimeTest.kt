package com.safedb.mcp

import com.safedb.platform.DesktopPlatform
import com.safedb.secrets.SecretsManager
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
            val tools = client.listTools().tools.associateBy { it.name }
            assertEquals(true, tools.getValue("list_connections").annotations?.readOnlyHint)
            assertEquals(true, tools.getValue("list_tables").annotations?.readOnlyHint)
            assertEquals(true, tools.getValue("describe_table").annotations?.readOnlyHint)
            assertEquals(true, tools.getValue("get_result_rows").annotations?.readOnlyHint)
            assertEquals(true, tools.getValue("summarize_result").annotations?.readOnlyHint)
            assertEquals(true, tools.getValue("assess_query").annotations?.readOnlyHint)
            assertEquals(true, tools.getValue("search_catalog").annotations?.readOnlyHint)
            assertEquals(true, tools.getValue("find_join_paths").annotations?.readOnlyHint)
            assertEquals(true, tools.getValue("pivot_result").annotations?.readOnlyHint)
            assertEquals(true, tools.getValue("filter_result_rows").annotations?.readOnlyHint)
            assertEquals(true, tools.getValue("list_saved_queries").annotations?.readOnlyHint)
            assertEquals(true, tools.getValue("list_recipes").annotations?.readOnlyHint)
            assertEquals(true, tools.getValue("apply_recipe").annotations?.readOnlyHint)
            assertEquals(true, tools.getValue("list_query_history").annotations?.readOnlyHint)
            assertEquals(null, tools.getValue("export_result").annotations)
            assertEquals(true, tools.getValue("delete_connection").annotations?.destructiveHint)
            assertEquals(true, tools.getValue("delete_saved_query").annotations?.destructiveHint)
            assertEquals(null, tools.getValue("save_query").annotations)
            assertEquals(null, tools.getValue("run_saved_query").annotations)
            assertEquals(
                listOf("connection_id"),
                tools.getValue("delete_connection").inputSchema.required,
            )
            assertTrue(
                tools
                    .getValue("delete_connection")
                    .inputSchema
                    .properties
                    ?.containsKey("confirmation") == true
            )
            assertEquals(null, tools.getValue("run_query").annotations)
            val runQueryDescription = tools.getValue("run_query").description.orEmpty()
            assertTrue(runQueryDescription.contains("On error risk_gate"))
            assertFalse(runQueryDescription.contains("change the gate"))
            assertEquals(
                listOf("connection_id", "sql"),
                tools.getValue("run_query").inputSchema.required,
            )
            assertFalse(
                tools.getValue("run_query").inputSchema.properties?.containsKey("spec") == true
            )
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
