package com.safedb.mcp

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("integration")
class McpPackagedStdioIntegrationTest {
    @Test
    fun packagedJarServesMcpOverStdioAndExitsCleanly() = runBlocking {
        withPackagedMcpClient { client ->
            assertEquals("safe-db", client.serverVersion?.name)
            assertTrue(client.serverVersion?.version.orEmpty().isNotBlank())
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
    fun packagedJarListsConnectionsOnEmptyDataDir() {
        val result = runPackagedMcpProcess(listOf("connections", "list"))
        assertEquals(0, result.exitCode)
        assertTrue(
            result.stdout.contains("No connections."),
            "stdout: ${result.stdout.ifBlank { "<empty>" }}",
        )
        result.stdout
            .lineSequence()
            .filter { it.isNotBlank() }
            .forEach { line ->
                assertFalse(
                    line.contains("\"jsonrpc\""),
                    "JSON-RPC protocol found on CLI stdout: $line",
                )
            }
    }

    @Test
    fun packagedJarPrintsUsageOnHelp() {
        val result = runPackagedMcpProcess(listOf("--help"))
        assertEquals(0, result.exitCode)
        assertTrue(
            result.stdout.contains("Usage: safe-db-mcp"),
            "stdout: ${result.stdout.ifBlank { "<empty>" }}",
        )
        assertTrue(
            result.stdout.contains(MCP_USAGE),
            "stdout did not contain MCP_USAGE: ${result.stdout.ifBlank { "<empty>" }}",
        )
    }

    @Test
    fun packagedJarPrintsUsageOnUnknownArgs() {
        val result = runPackagedMcpProcess(listOf("unknown"))
        assertEquals(2, result.exitCode)
        assertTrue(
            result.stderr.contains("Usage: safe-db-mcp"),
            "stderr: ${result.stderr.ifBlank { "<empty>" }}",
        )
    }
}
