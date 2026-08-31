package com.safedb.mcp

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.runBlocking

class McpRuntimeTest {
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
    fun initializeListsConnectionTools() = runBlocking {
        withMcpClient(createSafeDbMcpServer(RecordingSafeDbService())) { client ->
            assertEquals("safe-db", client.serverVersion?.name)
            assertEquals(
                listOf(
                    "delete_connection",
                    "describe_table",
                    "list_connections",
                    "list_tables",
                    "run_query",
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
