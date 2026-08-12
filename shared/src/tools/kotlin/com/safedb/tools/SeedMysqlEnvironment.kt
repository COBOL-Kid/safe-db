package com.safedb.tools

import com.safedb.platform.DesktopPlatform
import com.safedb.platform.UnsupportedDesktopPlatformException
import java.nio.file.Path

internal fun safeDbAppDataDir(
    environment: SeedMysqlPlatformEnvironment = SeedMysqlPlatformEnvironment.current()
): Path? =
    when (DesktopPlatform.resolve(environment.osName)) {
        DesktopPlatform.MacOs ->
            Path.of(environment.userHome, "Library", "Application Support", "com.safedb.app")
        DesktopPlatform.Windows ->
            environment.appData?.takeIf(String::isNotBlank)?.let { Path.of(it, "com.safedb.app") }
    }

internal fun safeDbAppDataDirForStateReset(
    environment: SeedMysqlPlatformEnvironment = SeedMysqlPlatformEnvironment.current(),
    report: (String) -> Unit = ::println,
): Path? =
    try {
        safeDbAppDataDir(environment)
            ?: run {
                report("-> skipping safe-db app state reset (APPDATA is not set)")
                null
            }
    } catch (error: UnsupportedDesktopPlatformException) {
        report("-> skipping safe-db app state reset (${error.message})")
        null
    }

internal data class SeedMysqlPlatformEnvironment(
    val osName: String,
    val userHome: String,
    val appData: String? = null,
) {
    companion object {
        fun current(): SeedMysqlPlatformEnvironment =
            SeedMysqlPlatformEnvironment(
                osName = System.getProperty("os.name").orEmpty(),
                userHome = System.getProperty("user.home").orEmpty(),
                appData = System.getenv("APPDATA"),
            )
    }
}

internal fun runningMysqlContainers(): List<String> =
    runCommand(
            listOf(
                "docker",
                "ps",
                "--filter",
                "status=running",
                "--format",
                "{{.Names}}\t{{.Image}}",
            )
        )
        .stdout
        .lineSequence()
        .mapNotNull { parseMysqlContainerLine(it) }
        .toList()

internal fun parseMysqlContainerLine(line: String): String? {
    val parts = line.split('\t')
    if (parts.size < 2) return null
    val image = parts[1].lowercase()
    return parts[0].takeIf { image.contains("mysql") || image.contains("mariadb") }
}

internal fun dockerEnvVar(container: String, key: String): String {
    val result =
        runCommand(
            listOf(
                "docker",
                "inspect",
                container,
                "--format",
                "{{range .Config.Env}}{{println .}}{{end}}",
            )
        )
    if (result.exitCode != 0) return ""
    return result.stdout
        .lineSequence()
        .firstOrNull { it.substringBefore('=') == key }
        ?.substringAfter('=', "")
        .orEmpty()
}

internal fun sanitizeIdentifier(value: String, label: String) {
    if (!Regex("""^[A-Za-z0-9_.-]+$""").matches(value)) {
        throw RuntimeException("invalid $label (allowed: letters, digits, ., _, -)")
    }
}

internal fun isLocalHost(value: String): Boolean = value == "localhost" || value == "127.0.0.1"

internal fun commandExists(command: String): Boolean =
    runCommand(listOf("sh", "-c", "command -v ${shellQuote(command)} >/dev/null 2>&1")).exitCode ==
        0

private fun shellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

internal data class CommandResult(val exitCode: Int, val stdout: String, val stderr: String) {
    fun requireSuccess() {
        if (exitCode != 0) {
            throw RuntimeException(stderr.ifBlank { "command failed with exit code $exitCode" })
        }
    }
}

internal fun runCommand(
    command: List<String>,
    writeInput: ((java.io.OutputStream) -> Unit)? = null,
): CommandResult {
    val process = ProcessBuilder(command).start()
    if (writeInput != null) {
        process.outputStream.use(writeInput)
    } else {
        process.outputStream.close()
    }
    val stdout = process.inputStream.bufferedReader().readText()
    val stderr = process.errorStream.bufferedReader().readText()
    val exitCode = process.waitFor()
    return CommandResult(exitCode, stdout, stderr.trimEnd())
}
