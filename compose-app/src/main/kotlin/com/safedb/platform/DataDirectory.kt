package com.safedb.platform

import java.nio.file.Files
import java.nio.file.Path

/** Resolves the Compose app data directory, mirroring Tauri 2 `app_data_dir()` layout. */
object DataDirectory {
    const val APP_ID: String = "com.safedb.app"

    fun resolve(): Path {
        val dir = baseDir().resolve(APP_ID)
        Files.createDirectories(dir)
        return dir
    }

    internal fun baseDir(): Path =
        when (currentOs()) {
            Os.Windows -> {
                val appData = System.getenv("APPDATA")
                require(!appData.isNullOrBlank()) { "APPDATA is not set" }
                Path.of(appData)
            }
            Os.MacOs -> Path.of(System.getProperty("user.home"), "Library", "Application Support")
            Os.Linux -> {
                val xdg = System.getenv("XDG_DATA_HOME")
                if (!xdg.isNullOrBlank()) {
                    Path.of(xdg)
                } else {
                    Path.of(System.getProperty("user.home"), ".local", "share")
                }
            }
        }

    private fun currentOs(): Os {
        val name = System.getProperty("os.name").lowercase()
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
