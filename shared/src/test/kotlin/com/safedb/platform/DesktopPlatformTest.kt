package com.safedb.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopPlatformTest {
    @Test
    fun resolvesMacOsAndDarwinNames() {
        assertEquals(DesktopPlatform.MacOs, DesktopPlatform.resolve("Mac OS X"))
        assertEquals(DesktopPlatform.MacOs, DesktopPlatform.resolve("Darwin"))
        assertTrue(DesktopPlatform.MacOs.supportsDesktopApp)
    }

    @Test
    fun resolvesWindowsNames() {
        assertEquals(DesktopPlatform.Windows, DesktopPlatform.resolve("Windows 11"))
        assertTrue(DesktopPlatform.Windows.supportsDesktopApp)
    }

    @Test
    fun resolvesLinuxWithoutEnablingTheDesktopApp() {
        assertEquals(DesktopPlatform.Linux, DesktopPlatform.resolve("Linux"))
        assertFalse(DesktopPlatform.Linux.supportsDesktopApp)
    }

    @Test
    fun rejectsUnknownNamesWithTheSupportedPlatformMessage() {
        listOf("Haiku", "SwingOS").forEach { osName ->
            val error =
                assertFailsWith<UnsupportedDesktopPlatformException> {
                    DesktopPlatform.resolve(osName)
                }
            assertEquals(
                "unsupported operating system '$osName'; supported platforms are macOS, Windows, and Linux",
                error.message,
            )
        }
    }
}
