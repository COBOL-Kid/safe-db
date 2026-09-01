package com.safedb.mcp

import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

private const val CLIENT_TIMEOUT_MS = 30_000L
private const val PROCESS_EXIT_TIMEOUT_SECONDS = 10L
private const val PROCESS_CLEANUP_TIMEOUT_SECONDS = 5L
private const val STDERR_DRAIN_TIMEOUT_MS = 5_000L
private const val SHADOW_JAR_PROPERTY = "safedb.mcp.shadowJar"

@Tag("integration")
class McpPackagedStdioIntegrationTest {
    @Test
    fun packagedJarServesMcpOverStdioAndExitsCleanly() = runBlocking {
        val shadowJar =
            Path.of(
                assertNotNull(
                    System.getProperty(SHADOW_JAR_PROPERTY),
                    "Missing -D$SHADOW_JAR_PROPERTY; run this test via :mcp:integrationTest",
                )
            )
        assertTrue(Files.isRegularFile(shadowJar), "Shadow JAR does not exist: $shadowJar")

        val tempRoot = Files.createTempDirectory("safedb-mcp-packaged-")
        runPackagedSmokeTest(shadowJar, tempRoot)
    }
}

private suspend fun runPackagedSmokeTest(shadowJar: Path, tempRoot: Path) {
    val protocolCapture = ByteArrayOutputStream()
    val stderrCapture = ByteArrayOutputStream()
    val stderrFailure = AtomicReference<Throwable?>()
    var process: Process? = null
    var client: Client? = null
    var stderrThread: Thread? = null
    var failure: Throwable? = null

    try {
        val home = Files.createDirectories(tempRoot.resolve("home"))
        val javaExecutable =
            Path.of(System.getProperty("java.home"))
                .resolve("bin")
                .resolve(if (isWindows()) "java.exe" else "java")
        assertTrue(
            Files.isRegularFile(javaExecutable),
            "Java executable not found: $javaExecutable",
        )

        val processBuilder =
            ProcessBuilder(
                javaExecutable.toString(),
                "-Duser.home=$home",
                "-jar",
                shadowJar.toAbsolutePath().toString(),
            )
        configureIsolatedEnvironment(processBuilder, tempRoot, home)
        process = processBuilder.start()

        val runningProcess = process
        stderrThread =
            thread(isDaemon = true, name = "safe-db-mcp-stderr-drain") {
                try {
                    runningProcess.errorStream.copyTo(stderrCapture)
                } catch (error: Throwable) {
                    stderrFailure.set(error)
                }
            }

        val protocolInput = CapturingInputStream(runningProcess.inputStream, protocolCapture)
        val transport =
            StdioClientTransport(
                input = protocolInput.asSource().buffered(),
                output = runningProcess.outputStream.asSink().buffered(),
            )
        client =
            Client(clientInfo = Implementation(name = "packaged-integration-test", version = "0"))

        withTimeout(CLIENT_TIMEOUT_MS) {
            client.connect(transport)
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
            client.close()
            client = null
        }

        runningProcess.outputStream.close()
        assertTrue(
            runningProcess.waitFor(PROCESS_EXIT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
            "MCP process did not exit after the client closed",
        )
        stderrThread.join(STDERR_DRAIN_TIMEOUT_MS)
        assertFalse(stderrThread.isAlive, "stderr drain did not finish")
        stderrFailure.get()?.let { throw AssertionError("stderr drain failed", it) }
        assertEquals(0, runningProcess.exitValue(), "MCP process exited nonzero")

        assertProtocolAndDiagnostics(protocolCapture.snapshot(), stderrCapture.snapshot())
    } catch (error: Throwable) {
        failure = error
    } finally {
        runCatching { withTimeout(PROCESS_CLEANUP_TIMEOUT_SECONDS * 1_000) { client?.close() } }
        runCatching { process?.outputStream?.close() }
        process?.let { runningProcess ->
            if (runningProcess.isAlive) {
                runningProcess.destroy()
                if (
                    !runningProcess.waitFor(
                        PROCESS_CLEANUP_TIMEOUT_SECONDS,
                        TimeUnit.SECONDS,
                    )
                ) {
                    runningProcess.destroyForcibly()
                    runningProcess.waitFor(PROCESS_CLEANUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                }
            }
            runCatching { runningProcess.inputStream.close() }
            runCatching { runningProcess.errorStream.close() }
        }
        stderrThread?.join(STDERR_DRAIN_TIMEOUT_MS)

        if (!tempRoot.toFile().deleteRecursively()) {
            val cleanupFailure = AssertionError("Failed to delete temporary data: $tempRoot")
            if (failure == null) failure = cleanupFailure else failure.addSuppressed(cleanupFailure)
        }
    }

    failure?.let { error ->
        throw AssertionError(
            buildString {
                appendLine("Packaged MCP smoke test failed.")
                appendLine("stderr:")
                appendLine(stderrCapture.snapshot().ifBlank { "<empty>" })
                appendLine("stdout protocol:")
                append(protocolCapture.snapshot().ifBlank { "<empty>" })
            },
            error,
        )
    }
}

private fun configureIsolatedEnvironment(
    processBuilder: ProcessBuilder,
    tempRoot: Path,
    home: Path,
) {
    val inherited = processBuilder.environment().toMap()
    val environment = processBuilder.environment()
    environment.clear()

    if (isWindows()) {
        listOf("SystemRoot", "ComSpec", "PATHEXT", "WINDIR").forEach { name ->
            inherited.entries
                .firstOrNull { it.key.equals(name, ignoreCase = true) }
                ?.let { environment[it.key] = it.value }
        }
    }

    val xdgData = Files.createDirectories(tempRoot.resolve("xdg-data"))
    val xdgConfig = Files.createDirectories(tempRoot.resolve("xdg-config"))
    val xdgCache = Files.createDirectories(tempRoot.resolve("xdg-cache"))
    val appData = Files.createDirectories(tempRoot.resolve("appdata"))
    val localAppData = Files.createDirectories(tempRoot.resolve("local-appdata"))
    val temp = Files.createDirectories(tempRoot.resolve("tmp"))
    environment["HOME"] = home.toString()
    environment["USERPROFILE"] = home.toString()
    environment["XDG_DATA_HOME"] = xdgData.toString()
    environment["XDG_CONFIG_HOME"] = xdgConfig.toString()
    environment["XDG_CACHE_HOME"] = xdgCache.toString()
    environment["APPDATA"] = appData.toString()
    environment["LOCALAPPDATA"] = localAppData.toString()
    environment["TMPDIR"] = temp.toString()
    environment["TEMP"] = temp.toString()
    environment["TMP"] = temp.toString()
    environment["SAFEDB_KEYCHAIN_BACKEND"] = "disabled"
}

private fun assertProtocolAndDiagnostics(protocol: String, stderr: String) {
    val protocolLines = protocol.lineSequence().filter { it.isNotBlank() }.toList()
    assertTrue(protocolLines.isNotEmpty(), "MCP process produced no stdout protocol messages")
    protocolLines.forEach { line ->
        assertEquals(
            "2.0",
            Json.parseToJsonElement(line).jsonObject["jsonrpc"]?.jsonPrimitive?.content,
            "Non-protocol output found on stdout: $line",
        )
    }
    assertFalse(stderr.contains("\"jsonrpc\""), "JSON-RPC protocol leaked to stderr")

    val secretKey = Regex("""(?i)"(?:password|secret|token|credentials?)"\s*:""")
    assertFalse(secretKey.containsMatchIn(protocol), "Secret-like data found in stdout protocol")
    assertFalse(secretKey.containsMatchIn(stderr), "Secret-like data found in stderr")
}

private fun isWindows(): Boolean =
    System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

private fun ByteArrayOutputStream.snapshot(): String =
    synchronized(this) { toString(Charsets.UTF_8) }

private class CapturingInputStream(
    input: InputStream,
    private val capture: ByteArrayOutputStream,
) : FilterInputStream(input) {
    override fun read(): Int {
        val value = `in`.read()
        if (value >= 0) synchronized(capture) { capture.write(value) }
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val count = `in`.read(buffer, offset, length)
        if (count > 0) synchronized(capture) { capture.write(buffer, offset, count) }
        return count
    }
}
