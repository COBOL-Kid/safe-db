package com.safedb

import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import com.safedb.launch.LaunchProfileBootstrap
import com.safedb.launch.LaunchProfileException
import com.safedb.platform.DataDirectory
import com.safedb.platform.DesktopPlatform
import com.safedb.platform.UnsupportedDesktopPlatformException
import com.safedb.secrets.SecretsManager
import com.safedb.service.SafeDbServiceImpl
import com.safedb.store.ConfigStore
import com.safedb.store.QueryStore
import com.safedb.store.RecipeStore
import com.safedb.store.SettingsStore
import com.safedb.ui.theme.SafeDbTheme
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

internal const val SMOKE_CHECK_FLAG = "--smoke-check"

fun main(args: Array<String>) {
    dispatchStartup(args = args) { platform ->
        try {
            LaunchProfileBootstrap.configure(args)
        } catch (error: LaunchProfileException) {
            System.err.println("safe-db: ${error.message}")
            exitProcess(2)
        }
        SecretsManager.initStore(platform = platform)
        val dataDir = DataDirectory.resolve()
        val service =
            SafeDbServiceImpl(
                configStore = ConfigStore.new(dataDir),
                queryStore = QueryStore.new(dataDir),
                settingsStore = SettingsStore.new(dataDir),
                recipeStore = RecipeStore.new(dataDir),
            )
        runApp(AppState(), service)
    }
}

internal fun isSmokeCheckArgs(args: Array<String>): Boolean =
    args.size == 1 && args[0] == SMOKE_CHECK_FLAG

internal fun dispatchStartup(
    args: Array<String>,
    osName: String = System.getProperty("os.name").orEmpty(),
    reportError: (String) -> Unit = System.err::println,
    exit: (Int) -> Nothing = ::exitProcess,
    smokeCheck: (DesktopPlatform) -> Nothing = { platform ->
        runSmokeCheck(platform = platform, reportError = reportError, exit = exit)
    },
    runDesktop: (DesktopPlatform) -> Unit,
) {
    // Keep this order: reject unsupported platforms before trust, credential, or data
    // initialization, and handle --smoke-check before LaunchProfileBootstrap.
    val platform = requireSupportedDesktopPlatform(osName, reportError, exit)
    if (isSmokeCheckArgs(args)) {
        smokeCheck(platform)
    }
    runDesktop(platform)
}

internal fun runSmokeCheck(
    platform: DesktopPlatform,
    createTempDir: () -> Path = { Files.createTempDirectory("safe-db-smoke-") },
    configureHeadless: () -> Unit = { System.setProperty("java.awt.headless", "true") },
    loadVersion: () -> String = ::loadAppVersion,
    loadIcon: () -> BufferedImage = ::loadAppWindowIconImage,
    renderFrame: () -> Unit = ::renderSmokeComposeFrame,
    reportOutput: (String) -> Unit = ::println,
    reportError: (String) -> Unit = System.err::println,
    exit: (Int) -> Nothing = ::exitProcess,
): Nothing {
    var tempDir: Path? = null
    val exitCode =
        try {
            val dir = createTempDir()
            tempDir = dir
            val version = loadVersion()
            require(version.isNotBlank()) { "app-version.txt is blank" }
            SecretsManager.initStore(envValue = "disabled", platform = platform, warn = false)
            ConfigStore.new(dir).list()
            QueryStore.new(dir).listSaved()
            SettingsStore.new(dir).load()
            RecipeStore.new(dir).list()
            configureHeadless()
            val icon = loadIcon()
            require(icon.width > 0 && icon.height > 0) { "window icon is empty" }
            renderFrame()
            reportOutput("safe-db $version smoke-check ok")
            0
        } catch (error: Throwable) {
            reportError("safe-db: smoke-check failed: ${formatSmokeCheckFailure(error)}")
            1
        } finally {
            tempDir?.toFile()?.deleteRecursively()
        }
    exit(exitCode)
}

private fun formatSmokeCheckFailure(error: Throwable): String {
    val type = error::class.simpleName
    val message = error.message?.takeIf { it.isNotBlank() }
    return when {
        type != null && message != null -> "$type: $message"
        message != null -> message
        type != null -> type
        else -> error.toString()
    }
}

internal fun renderSmokeComposeFrame() {
    ImageComposeScene(width = 64, height = 64, density = Density(1f)) {
            SafeDbTheme(isDark = false) { Surface { Text("safe-db") } }
        }
        .use { scene ->
            scene.render(0L).use { image ->
                require(image.width > 0 && image.height > 0) {
                    "smoke-check Compose frame was empty"
                }
            }
        }
}

internal fun requireSupportedDesktopPlatform(
    osName: String = System.getProperty("os.name").orEmpty(),
    reportError: (String) -> Unit = System.err::println,
    exit: (Int) -> Nothing = ::exitProcess,
): DesktopPlatform {
    val platform =
        try {
            DesktopPlatform.resolve(osName)
        } catch (_: UnsupportedDesktopPlatformException) {
            null
        }
    if (platform == null || !platform.supportsDesktopApp) {
        reportError(
            "safe-db: unsupported operating system '$osName'; supported platforms are macOS and Windows",
        )
        exit(2)
    }
    return platform
}
