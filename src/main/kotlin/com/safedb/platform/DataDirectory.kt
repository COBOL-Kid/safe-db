package com.safedb.platform

import java.nio.file.Files
import java.nio.file.Path

/** Resolves the safe-db app data directory. */
object DataDirectory {
    const val APP_ID: String = "com.safedb.app"

    fun resolve(): Path {
        val dir = baseDir().resolve(APP_ID)
        Files.createDirectories(dir)
        return dir
    }

    internal fun baseDir(environment: PlatformEnvironment = PlatformEnvironment.current()): Path =
        when (currentOs(environment.osName)) {
            Os.Windows -> {
                val appData = environment.appData
                require(!appData.isNullOrBlank()) { "APPDATA is not set" }
                Path.of(appData)
            }
            Os.MacOs -> Path.of(environment.userHome, "Library", "Application Support")
            Os.Linux -> {
                val xdg = environment.xdgDataHome
                if (!xdg.isNullOrBlank()) {
                    Path.of(xdg)
                } else {
                    Path.of(environment.userHome, ".local", "share")
                }
            }
        }

    private fun currentOs(osName: String): Os {
        val name = osName.lowercase()
        return when {
            name.contains("mac") || name.contains("darwin") -> Os.MacOs
            name.contains("win") -> Os.Windows
            else -> Os.Linux
        }
    }

    private enum class Os {
        Linux,
        MacOs,
        Windows,
    }
}

internal data class PlatformEnvironment(
    val osName: String,
    val userHome: String,
    val appData: String? = null,
    val xdgDataHome: String? = null,
) {
    companion object {
        fun current(): PlatformEnvironment = PlatformEnvironment(
            osName = System.getProperty("os.name"),
            userHome = System.getProperty("user.home"),
            appData = System.getenv("APPDATA"),
            xdgDataHome = System.getenv("XDG_DATA_HOME"),
        )
    }
}
