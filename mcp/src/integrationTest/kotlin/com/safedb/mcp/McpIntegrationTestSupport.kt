package com.safedb.mcp

import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered

private const val PIPE_BUFFER_SIZE = 64 * 1024
private const val MCP_TEST_TIMEOUT_MS = 30_000L

internal suspend fun withIntegrationMcpClient(server: Server, block: suspend (Client) -> Unit) {
    val clientToServer = PipedInputStream(PIPE_BUFFER_SIZE)
    val serverToClient = PipedInputStream(PIPE_BUFFER_SIZE)
    val (clientOutput, serverOutput) =
        withContext(Dispatchers.IO) {
            PipedOutputStream(clientToServer) to PipedOutputStream(serverToClient)
        }
    val serverTransport =
        StdioServerTransport(
            input = clientToServer.asSource().buffered(),
            output = serverOutput.asSink().buffered(),
        )
    val clientTransport =
        StdioClientTransport(
            input = serverToClient.asSource().buffered(),
            output = clientOutput.asSink().buffered(),
        )
    val client = Client(clientInfo = Implementation(name = "integration-test", version = "0"))

    coroutineScope {
        val sessionJob = launch {
            val session = server.createSession(serverTransport)
            val closed = Job()
            session.onClose { closed.complete() }
            closed.join()
        }
        try {
            withTimeout(MCP_TEST_TIMEOUT_MS.milliseconds) {
                client.connect(clientTransport)
                block(client)
            }
        } finally {
            runCatching { client.close() }
            sessionJob.cancelAndJoin()
        }
    }
}
