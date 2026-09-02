package com.safedb.mcp

import com.safedb.platform.DesktopPlatform
import com.safedb.secrets.SecretsManager
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

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
                    "delete_connection",
                    "describe_table",
                    "get_result_rows",
                    "list_connections",
                    "list_tables",
                    "run_query",
                    "summarize_result",
                ),
                client.listTools().tools.map { it.name }.sorted(),
            )
            assertEquals(MCP_SERVER_INSTRUCTIONS, client.serverInstructions)
            assertEquals(false, client.serverCapabilities?.tools?.listChanged)
            val tools = client.listTools().tools.associateBy { it.name }
            assertEquals(true, tools.getValue("list_connections").annotations?.readOnlyHint)
            assertEquals(true, tools.getValue("list_tables").annotations?.readOnlyHint)
            assertEquals(true, tools.getValue("describe_table").annotations?.readOnlyHint)
            assertEquals(true, tools.getValue("get_result_rows").annotations?.readOnlyHint)
            assertEquals(true, tools.getValue("summarize_result").annotations?.readOnlyHint)
            assertEquals(true, tools.getValue("delete_connection").annotations?.destructiveHint)
            assertEquals(null, tools.getValue("run_query").annotations)
            assertEquals(
                listOf("connection_id", "sql"),
                tools.getValue("run_query").inputSchema.required,
            )
            assertFalse(
                tools.getValue("run_query").inputSchema.properties?.containsKey("spec") == true
            )
        }
    }

    @Test
    fun printlnAfterRedirectLandsOnStderrNotProtocolStream() {
        val protocol = ByteArrayOutputStream()
        val previous = System.out
        try {
            System.setOut(PrintStream(protocol, true, Charsets.UTF_8))
            redirectStdoutToStderr()
            println("should-not-leak")
            assertFalse(protocol.toString(Charsets.UTF_8).contains("should-not-leak"))
        } finally {
            System.setOut(previous)
        }
    }
}
