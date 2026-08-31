package com.safedb.mcp

import com.safedb.model.ConnectionDef
import com.safedb.model.Dialect
import com.safedb.model.TransportSecurity
import com.safedb.model.TransportSecurityMode
import com.safedb.persist.restrictToOwnerReadWrite
import com.safedb.service.FakeSafeDbServiceSupport
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered

internal const val PIPE_BUFFER = 64 * 1024

internal fun sampleMcpConnection(
    id: String = "c1",
    name: String = "Demo",
    dialect: Dialect = Dialect.Postgres,
    database: String = "app",
): ConnectionDef =
    ConnectionDef(
        id = id,
        name = name,
        dialect = dialect,
        host = "localhost",
        port = 5432,
        database = database,
        username = "readonly",
        transportSecurity = TransportSecurity(mode = TransportSecurityMode.Disabled),
    )

internal open class RecordingSafeDbService : FakeSafeDbServiceSupport() {
    val connections = mutableListOf<ConnectionDef>()
    val passwords = mutableMapOf<String, String>()
    var testResult: String = "ok"
    var testError: String? = null
    var tested: Pair<ConnectionDef, String?>? = null

    override suspend fun testConnection(def: ConnectionDef, password: String?): String {
        tested = def to password
        testError?.let { throw IllegalStateException(it) }
        return testResult
    }

    override suspend fun createConnection(def: ConnectionDef, password: String): ConnectionDef {
        require(connections.none { it.id == def.id }) { "Connection already exists" }
        connections += def
        passwords[def.id] = password
        return def
    }

    override suspend fun listConnections(): List<ConnectionDef> = connections.toList()

    override suspend fun deleteConnection(id: String) {
        connections.removeAll { it.id == id }
        passwords.remove(id)
    }
}

internal class BufferCliIo(
    private val lines: ArrayDeque<String> = ArrayDeque(),
    private val passwords: ArrayDeque<String> = ArrayDeque(),
) : CliIo {
    val stdout = StringBuilder()
    val stderr = StringBuilder()

    override fun print(line: String) {
        stdout.appendLine(line)
    }

    override fun printErr(line: String) {
        stderr.appendLine(line)
    }

    override fun readLine(prompt: String): String? = lines.removeFirstOrNull()

    override fun readPassword(prompt: String): String? = passwords.removeFirstOrNull()
}

internal fun writeOwnerOnlyPasswordFile(directory: Path, contents: String): Path {
    val path = directory.resolve("password.txt")
    Files.writeString(path, contents)
    restrictToOwnerReadWrite(path)
    return path
}

internal suspend fun withMcpClient(server: Server, block: suspend (Client) -> Unit) {
    val clientToServer = java.io.PipedInputStream(PIPE_BUFFER)
    val clientOut = java.io.PipedOutputStream(clientToServer)
    val serverToClient = java.io.PipedInputStream(PIPE_BUFFER)
    val serverOut = java.io.PipedOutputStream(serverToClient)
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
    coroutineScope {
        val sessionJob = launch {
            val session = server.createSession(serverTransport)
            val done = Job()
            session.onClose { done.complete() }
            done.join()
        }
        try {
            withTimeout(10_000) {
                client.connect(clientTransport)
                block(client)
            }
        } finally {
            runCatching { client.close() }
            sessionJob.cancelAndJoin()
        }
    }
}
