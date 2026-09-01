package com.safedb.mcp

import com.safedb.platform.DataDirectory
import com.safedb.platform.DesktopPlatform
import java.nio.file.Files
import java.nio.file.Path

object McpDataDirectory {
    const val MCP_APP_ID: String = "com.safedb.mcp"

    fun resolve(): Path {
        val dir = pathFor(McpEnvironment.current())
        Files.createDirectories(dir)
        return dir
    }

    internal fun pathFor(environment: McpEnvironment): Path =
        when (DesktopPlatform.resolve(environment.osName)) {
            DesktopPlatform.Windows -> {
                val appData = environment.appData
                require(!appData.isNullOrBlank()) { "APPDATA is not set" }
                // Same roaming dir as the desktop app so Windows MCP reuses the UI's connections.
                Path.of(appData).resolve(DataDirectory.APP_ID)
            }
            DesktopPlatform.MacOs ->
                Path.of(environment.userHome, "Library", "Application Support", MCP_APP_ID)
            DesktopPlatform.Linux -> {
                val xdg = environment.xdgDataHome
                val base =
                    if (!xdg.isNullOrBlank()) Path.of(xdg)
                    else Path.of(environment.userHome, ".local", "share")
                base.resolve(MCP_APP_ID)
            }
        }
}

internal data class McpEnvironment(
    val osName: String,
    val userHome: String,
    val appData: String? = null,
    val xdgDataHome: String? = null,
) {
    companion object {
        fun current(): McpEnvironment =
            McpEnvironment(
                osName = System.getProperty("os.name").orEmpty(),
                userHome = System.getProperty("user.home").orEmpty(),
                appData = System.getenv("APPDATA"),
                xdgDataHome = System.getenv("XDG_DATA_HOME"),
            )
    }
}
