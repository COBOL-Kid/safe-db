package com.safedb.mcp

import com.safedb.platform.DesktopStoreUnavailableException
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

fun main(args: Array<String>) {
    val command =
        try {
            parseMcpArgs(args)
        } catch (error: McpCliUsageException) {
            System.err.println("safe-db-mcp: ${error.message}")
            if (error.message != MCP_USAGE) System.err.println(MCP_USAGE)
            exitProcess(2)
        }
    if (command is McpCommand.Help) {
        println(MCP_USAGE)
        return
    }
    try {
        when (command) {
            McpCommand.Stdio -> {
                val stdout = System.out
                redirectStdoutToStderr()
                val service = createMcpRuntime(McpDataDirectory.resolve())
                runMcpStdio(createSafeDbMcpServer(service), stdout)
            }
            else -> {
                val service = createMcpRuntime(McpDataDirectory.resolve())
                val code = runBlocking {
                    executeMcpCommand(
                        command,
                        service,
                        SystemCliIo,
                        tty = System.console() != null,
                    )
                }
                if (code != 0) exitProcess(code)
            }
        }
    } catch (error: UnsupportedDesktopPlatformException) {
        System.err.println("safe-db-mcp: ${error.message}")
        exitProcess(2)
    } catch (error: DesktopStoreUnavailableException) {
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
