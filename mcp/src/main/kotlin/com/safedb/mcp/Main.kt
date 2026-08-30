package com.safedb.mcp

import com.safedb.platform.UnsupportedDesktopPlatformException
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import kotlin.system.exitProcess
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered

fun main() {
    val stdout = System.out
    redirectStdoutToStderr()
    try {
        val dataDir = McpDataDirectory.resolve()
        val service = createMcpService(dataDir)
        runMcpStdio(createSafeDbMcpServer(service), stdout)
    } catch (error: UnsupportedDesktopPlatformException) {
        System.err.println("safe-db-mcp: ${error.message}")
        exitProcess(2)
    }
}

internal fun redirectStdoutToStderr() {
    System.setOut(PrintStream(FileOutputStream(FileDescriptor.err), true, Charsets.UTF_8))
}

internal fun runMcpStdio(server: Server, stdout: PrintStream) {
    val transport =
        StdioServerTransport(
            input = System.`in`.asSource().buffered(),
            output = stdout.asSink().buffered(),
        )
    runBlocking {
        val session = server.createSession(transport)
        val done = Job()
        session.onClose { done.complete() }
        done.join()
    }
}
