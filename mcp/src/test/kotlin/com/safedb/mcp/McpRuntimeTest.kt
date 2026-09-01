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
