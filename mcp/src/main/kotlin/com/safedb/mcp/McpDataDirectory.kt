package com.safedb.mcp

import com.safedb.platform.DataDirectory
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

object McpDataDirectory {
    const val MCP_APP_ID: String = "com.safedb.mcp"

    fun resolve(): Path {
        val dir = pathFor(McpEnvironment.current())
        Files.createDirectories(dir)
        return dir
    }

    internal fun pathFor(environment: McpEnvironment): Path =
        when (McpPlatform.resolve(environment.osName)) {
            McpPlatform.Windows -> {
                val appData = environment.appData
                require(!appData.isNullOrBlank()) { "APPDATA is not set" }
                Path.of(appData).resolve(DataDirectory.APP_ID)
            }
            McpPlatform.MacOs ->
                Path.of(environment.userHome, "Library", "Application Support", MCP_APP_ID)
            McpPlatform.Linux -> {
                val xdg = environment.xdgDataHome
                val base =
                    if (!xdg.isNullOrBlank()) Path.of(xdg)
                    else Path.of(environment.userHome, ".local", "share")
                base.resolve(MCP_APP_ID)
            }
        }
}

internal enum class McpPlatform {
    MacOs,
    Windows,
    Linux;

    companion object {
        fun resolve(osName: String): McpPlatform {
            val normalized = osName.trim().lowercase(Locale.ROOT)
            return when {
                normalized.startsWith("mac") || normalized == "darwin" -> MacOs
                normalized.startsWith("windows") -> Windows
                normalized.startsWith("linux") -> Linux
                else -> throw UnsupportedMcpPlatformException(osName)
            }
        }
    }
}

class UnsupportedMcpPlatformException(osName: String) :
    IllegalStateException(
        "unsupported operating system '$osName'; supported platforms are macOS, Windows, and Linux"
    )

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
