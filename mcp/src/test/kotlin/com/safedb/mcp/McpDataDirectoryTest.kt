package com.safedb.mcp

import com.safedb.platform.DataDirectory
import com.safedb.platform.UnsupportedDesktopPlatformException
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class McpDataDirectoryTest {
    @Test
    fun rejectsUnsupportedPlatformBeforeResolvingADataPath() {
        val error =
            assertFailsWith<UnsupportedDesktopPlatformException> {
                McpDataDirectory.pathFor(McpEnvironment("FreeBSD", "/home/test"))
            }
        assertEquals(
            "unsupported operating system 'FreeBSD'; supported platforms are macOS, Windows, and Linux",
            error.message,
        )
    }

    @Test
    fun resolvesWindowsToDesktopAppData() {
        assertEquals(
            Path.of("C:/Users/test/AppData/Roaming").resolve(DataDirectory.APP_ID),
            McpDataDirectory.pathFor(
                McpEnvironment(
                    "Windows 11",
                    "C:/Users/test",
                    appData = "C:/Users/test/AppData/Roaming",
                )
            ),
        )
    }

    @Test
    fun windowsRequiresAppData() {
        assertFailsWith<IllegalArgumentException> {
            McpDataDirectory.pathFor(McpEnvironment("Windows 11", "C:/Users/test"))
        }
    }

    @Test
    fun resolvesMacToMcpApplicationSupport() {
        val expected =
            Path.of("/Users/test/Library/Application Support", McpDataDirectory.MCP_APP_ID)
        assertEquals(expected, McpDataDirectory.pathFor(McpEnvironment("Mac OS X", "/Users/test")))
        assertEquals(expected, McpDataDirectory.pathFor(McpEnvironment("Darwin", "/Users/test")))
        assertEquals("com.safedb.app", DataDirectory.APP_ID)
        assertEquals("com.safedb.mcp", McpDataDirectory.MCP_APP_ID)
    }

    @Test
    fun resolvesLinuxXdgAndDefault() {
        assertEquals(
            Path.of("/home/test/.local/share", McpDataDirectory.MCP_APP_ID),
            McpDataDirectory.pathFor(McpEnvironment("Linux", "/home/test")),
        )
        assertEquals(
            Path.of("/custom/data", McpDataDirectory.MCP_APP_ID),
            McpDataDirectory.pathFor(
                McpEnvironment("Linux", "/home/test", xdgDataHome = "/custom/data")
            ),
        )
    }
}
