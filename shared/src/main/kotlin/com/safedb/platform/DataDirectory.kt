package com.safedb.platform

import java.nio.file.Files
import java.nio.file.Path

object DataDirectory {
    const val APP_ID: String = "com.safedb.app"

    fun resolve(): Path {
        val dir = baseDir().resolve(APP_ID)
        Files.createDirectories(dir)
        return dir
    }

    internal fun baseDir(environment: PlatformEnvironment = PlatformEnvironment.current()): Path =
        when (DesktopPlatform.resolve(environment.osName)) {
            DesktopPlatform.Windows -> {
                val appData = environment.appData
                require(!appData.isNullOrBlank()) { "APPDATA is not set" }
                Path.of(appData)
            }
            DesktopPlatform.MacOs -> Path.of(environment.userHome, "Library", "Application Support")
        }
}

internal data class PlatformEnvironment(
    val osName: String,
    val userHome: String,
    val appData: String? = null,
) {
    companion object {
        fun current(): PlatformEnvironment =
            PlatformEnvironment(
                osName = System.getProperty("os.name").orEmpty(),
                userHome = System.getProperty("user.home").orEmpty(),
                appData = System.getenv("APPDATA"),
            )
    }
}
