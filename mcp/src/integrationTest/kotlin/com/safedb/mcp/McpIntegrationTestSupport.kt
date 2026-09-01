package com.safedb.mcp

import com.safedb.model.ConnectionDef
import com.safedb.platform.DesktopPlatform
import com.safedb.secrets.SecretsManager
import com.safedb.service.SafeDbServiceImpl
import com.safedb.store.ConfigStore
import com.safedb.store.QueryStore
import com.safedb.store.SettingsStore
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val PIPE_BUFFER_SIZE = 64 * 1024
private const val MCP_TEST_TIMEOUT_MS = 30_000L
internal const val CLIENT_TIMEOUT_MS = 30_000L
internal const val PROCESS_EXIT_TIMEOUT_SECONDS = 10L
internal const val PROCESS_CLEANUP_TIMEOUT_SECONDS = 5L
internal const val STDERR_DRAIN_TIMEOUT_MS = 5_000L
internal const val SHADOW_JAR_PROPERTY = "safedb.mcp.shadowJar"

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

internal fun resolveShadowJar(): Path {
    val shadowJar =
        Path.of(
            assertNotNull(
                System.getProperty(SHADOW_JAR_PROPERTY),
                "Missing -D$SHADOW_JAR_PROPERTY; run this test via :mcp:integrationTest",
            )
        )
    assertTrue(Files.isRegularFile(shadowJar), "Shadow JAR does not exist: $shadowJar")
    return shadowJar
}

internal fun resolveJavaExecutable(): Path {
    val javaExecutable =
        Path.of(System.getProperty("java.home"))
            .resolve("bin")
            .resolve(if (isWindows()) "java.exe" else "java")
    assertTrue(Files.isRegularFile(javaExecutable), "Java executable not found: $javaExecutable")
    return javaExecutable
}

internal fun isolatedMcpDataDir(tempRoot: Path, home: Path): Path =
    McpDataDirectory.pathFor(
        McpEnvironment(
            osName = System.getProperty("os.name").orEmpty(),
            userHome = home.toString(),
            appData = tempRoot.resolve("appdata").toString(),
            xdgDataHome = tempRoot.resolve("xdg-data").toString(),
        )
    )

internal fun configureIsolatedEnvironment(
    processBuilder: ProcessBuilder,
    tempRoot: Path,
    home: Path,
    keychainBackend: String? = "disabled",
) {
    val inherited = processBuilder.environment().toMap()
    val environment = processBuilder.environment()
    environment.clear()

    // Process start on Windows still needs these after a full env wipe; dropping them
    // fails only there.
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
    if (keychainBackend != null) {
        environment["SAFEDB_KEYCHAIN_BACKEND"] = keychainBackend
    }
}

internal suspend fun seedMcpConnectionForPackagedJar(
    dataDir: Path,
    connection: ConnectionDef,
    password: String,
) {
    Files.createDirectories(dataDir)
    SecretsManager.lockCredentials()
    try {
        if (isWindows()) {
            SecretsManager.initStore(null, DesktopPlatform.Windows)
        } else {
            SecretsManager.initFileStore(dataDir.resolve("credentials"))
        }
        val service =
            SafeDbServiceImpl(
                configStore = ConfigStore.new(dataDir),
                queryStore = QueryStore.new(dataDir),
                settingsStore = SettingsStore.new(dataDir),
            )
        service.createConnection(connection, password)
    } finally {
        SecretsManager.lockCredentials()
        SecretsManager.initStore("disabled")
        SecretsManager.resetStoreReadCountForTest()
    }
}

internal fun cleanupPackagedJarWindowsSecret(connectionId: String) {
    if (!isWindows()) return
    SecretsManager.lockCredentials()
    try {
        SecretsManager.initStore(null, DesktopPlatform.Windows)
        SecretsManager.deletePassword(connectionId)
    } finally {
        SecretsManager.lockCredentials()
        SecretsManager.initStore("disabled")
        SecretsManager.resetStoreReadCountForTest()
    }
}

internal data class PackagedProcessResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)

internal fun runPackagedMcpProcess(args: List<String>): PackagedProcessResult {
    val shadowJar = resolveShadowJar()
    val tempRoot = Files.createTempDirectory("safedb-mcp-cli-")
    val stdoutCapture = ByteArrayOutputStream()
    val stderrCapture = ByteArrayOutputStream()
    val stdoutFailure = AtomicReference<Throwable?>()
    val stderrFailure = AtomicReference<Throwable?>()
    var process: Process? = null
    var stdoutThread: Thread? = null
    var stderrThread: Thread? = null
    var failure: Throwable? = null
    var result: PackagedProcessResult? = null

    try {
        val home = Files.createDirectories(tempRoot.resolve("home"))
        val processBuilder =
            ProcessBuilder(
                buildList {
                    add(resolveJavaExecutable().toString())
                    add("-Duser.home=$home")
                    add("-jar")
                    add(shadowJar.toAbsolutePath().toString())
                    addAll(args)
                }
            )
        configureIsolatedEnvironment(processBuilder, tempRoot, home)
        process = processBuilder.start()
        val runningProcess = process
        stdoutThread =
            thread(isDaemon = true, name = "safe-db-mcp-cli-stdout-drain") {
                try {
                    runningProcess.inputStream.copyTo(stdoutCapture)
                } catch (error: Throwable) {
                    stdoutFailure.set(error)
                }
            }
        stderrThread =
            thread(isDaemon = true, name = "safe-db-mcp-cli-stderr-drain") {
                try {
                    runningProcess.errorStream.copyTo(stderrCapture)
                } catch (error: Throwable) {
                    stderrFailure.set(error)
                }
            }
        assertTrue(
            runningProcess.waitFor(PROCESS_EXIT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
            "MCP CLI process did not exit",
        )
        stdoutThread.join(STDERR_DRAIN_TIMEOUT_MS)
        stderrThread.join(STDERR_DRAIN_TIMEOUT_MS)
        assertFalse(stdoutThread.isAlive, "stdout drain did not finish")
        assertFalse(stderrThread.isAlive, "stderr drain did not finish")
        stdoutFailure.get()?.let { throw AssertionError("stdout drain failed", it) }
        stderrFailure.get()?.let { throw AssertionError("stderr drain failed", it) }
        result =
            PackagedProcessResult(
                exitCode = runningProcess.exitValue(),
                stdout = stdoutCapture.snapshot(),
                stderr = stderrCapture.snapshot(),
            )
    } catch (error: Throwable) {
        failure = error
    } finally {
        process?.let { runningProcess ->
            if (runningProcess.isAlive) {
                runningProcess.destroy()
                if (!runningProcess.waitFor(PROCESS_CLEANUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    runningProcess.destroyForcibly()
                    runningProcess.waitFor(PROCESS_CLEANUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                }
            }
            runCatching { runningProcess.inputStream.close() }
            runCatching { runningProcess.errorStream.close() }
            runCatching { runningProcess.outputStream.close() }
        }
        stdoutThread?.join(STDERR_DRAIN_TIMEOUT_MS)
        stderrThread?.join(STDERR_DRAIN_TIMEOUT_MS)
        if (!tempRoot.toFile().deleteRecursively()) {
            val cleanupFailure = AssertionError("Failed to delete temporary data: $tempRoot")
            if (failure == null) failure = cleanupFailure else failure.addSuppressed(cleanupFailure)
        }
    }

    failure?.let { error ->
        throw AssertionError(
            buildString {
                appendLine("Packaged MCP CLI process failed.")
                appendLine("stderr:")
                appendLine(stderrCapture.snapshot().ifBlank { "<empty>" })
                appendLine("stdout:")
                append(stdoutCapture.snapshot().ifBlank { "<empty>" })
            },
            error,
        )
    }
    return checkNotNull(result)
}

internal suspend fun withPackagedMcpClient(
    // null leaves SAFEDB_KEYCHAIN_BACKEND unset so the child can read seeded secrets.
    keychainBackend: String? = "disabled",
    prepare: suspend (tempRoot: Path, home: Path) -> Unit = { _, _ -> },
    block: suspend (Client) -> Unit,
) {
    val shadowJar = resolveShadowJar()
    val tempRoot = Files.createTempDirectory("safedb-mcp-packaged-")
    val protocolCapture = ByteArrayOutputStream()
    val stderrCapture = ByteArrayOutputStream()
    val stderrFailure = AtomicReference<Throwable?>()
    var process: Process? = null
    var client: Client? = null
    var stderrThread: Thread? = null
    var failure: Throwable? = null

    try {
        val home = withContext(Dispatchers.IO) { Files.createDirectories(tempRoot.resolve("home")) }
        val processBuilder =
            ProcessBuilder(
                resolveJavaExecutable().toString(),
                "-Duser.home=$home",
                "-jar",
                shadowJar.toAbsolutePath().toString(),
            )
        configureIsolatedEnvironment(processBuilder, tempRoot, home, keychainBackend)
        prepare(tempRoot, home)
        process = withContext(Dispatchers.IO) { processBuilder.start() }

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

        withTimeout(CLIENT_TIMEOUT_MS.milliseconds) {
            client.connect(transport)
            block(client)
            client.close()
            client = null
        }

        withContext(Dispatchers.IO) {
            runningProcess.outputStream.close()
            assertTrue(
                runningProcess.waitFor(PROCESS_EXIT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "MCP process did not exit after the client closed",
            )
            stderrThread.join(STDERR_DRAIN_TIMEOUT_MS)
        }
        assertFalse(stderrThread.isAlive, "stderr drain did not finish")
        stderrFailure.get()?.let { throw AssertionError("stderr drain failed", it) }
        assertEquals(0, runningProcess.exitValue(), "MCP process exited nonzero")

        assertProtocolAndDiagnostics(protocolCapture.snapshot(), stderrCapture.snapshot())
    } catch (error: Throwable) {
        failure = error
    } finally {
        runCatching { withTimeout(PROCESS_CLEANUP_TIMEOUT_SECONDS.seconds) { client?.close() } }
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
        stderrThread?.let { draining ->
            withContext(Dispatchers.IO) { draining.join(STDERR_DRAIN_TIMEOUT_MS) }
        }

        if (!tempRoot.toFile().deleteRecursively()) {
            val cleanupFailure = AssertionError("Failed to delete temporary data: $tempRoot")
            if (failure == null) failure = cleanupFailure else failure.addSuppressed(cleanupFailure)
        }
    }

    failure?.let { error ->
        throw AssertionError(
            buildString {
                appendLine("Packaged MCP process test failed.")
                appendLine("stderr:")
                appendLine(stderrCapture.snapshot().ifBlank { "<empty>" })
                appendLine("stdout protocol:")
                append(protocolCapture.snapshot().ifBlank { "<empty>" })
            },
            error,
        )
    }
}

internal fun assertProtocolAndDiagnostics(protocol: String, stderr: String) {
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

internal fun CallToolResult.text(): String = (content.single() as TextContent).text

internal fun assertPayloadIsPrivate(
    payload: String,
    connection: ConnectionDef,
    password: String,
) {
    val lower = payload.lowercase()
    assertFalse(lower.contains("\"password\""))
    assertFalse(lower.contains("\"host\""))
    assertFalse(lower.contains("\"port\""))
    assertFalse(lower.contains("\"username\""))
    assertFalse(lower.contains("jdbc:mysql"))
    assertFalse(lower.contains("jdbc:postgresql"))
    assertFalse(payload.contains("${connection.host}:${connection.port}"))
    if (password.isNotEmpty()) {
        assertFalse(payload.contains(JsonPrimitive(password).toString()))
    }
}

internal fun isWindows(): Boolean =
    System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

internal fun ByteArrayOutputStream.snapshot(): String =
    synchronized(this) { toString(Charsets.UTF_8) }

internal class CapturingInputStream(
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
