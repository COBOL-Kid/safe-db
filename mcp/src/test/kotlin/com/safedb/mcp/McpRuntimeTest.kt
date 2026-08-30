package com.safedb.mcp

import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered

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
    fun initializeListsNoTools() = runBlocking {
        val dataDir = Files.createTempDirectory("safedb-mcp-server")
        try {
            val server = createSafeDbMcpServer(createMcpService(dataDir))
            val clientToServer = PipedInputStream(PIPE_BUFFER)
            val clientOut = PipedOutputStream(clientToServer)
            val serverToClient = PipedInputStream(PIPE_BUFFER)
            val serverOut = PipedOutputStream(serverToClient)
            val serverTransport =
                StdioServerTransport(
                    input = clientToServer.asSource().buffered(),
                    output = serverOut.asSink().buffered(),
                )
            val clientTransport =
                StdioClientTransport(
                    input = serverToClient.asSource().buffered(),
                    output = clientOut.asSink().buffered(),
                )
            val client = Client(clientInfo = Implementation(name = "test", version = "0"))
            val sessionJob = launch {
                val session = server.createSession(serverTransport)
                val done = Job()
                session.onClose { done.complete() }
                done.join()
            }
            try {
                withTimeout(10_000) {
                    client.connect(clientTransport)
                    assertEquals("safe-db", client.serverVersion?.name)
                    assertTrue(client.listTools().tools.isEmpty())
                }
            } finally {
                runCatching { client.close() }
                sessionJob.cancelAndJoin()
            }
        } finally {
            dataDir.toFile().deleteRecursively()
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

private const val PIPE_BUFFER = 64 * 1024
