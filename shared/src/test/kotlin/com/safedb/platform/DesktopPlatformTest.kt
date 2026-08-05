package com.safedb.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DesktopPlatformTest {
    @Test
    fun resolvesMacOsAndDarwinNames() {
        assertEquals(DesktopPlatform.MacOs, DesktopPlatform.resolve("Mac OS X"))
        assertEquals(DesktopPlatform.MacOs, DesktopPlatform.resolve("Darwin"))
    }

    @Test
    fun resolvesWindowsNames() {
        assertEquals(DesktopPlatform.Windows, DesktopPlatform.resolve("Windows 11"))
    }

    @Test
    fun rejectsLinuxAndUnknownNamesWithTheSupportedPlatformMessage() {
        listOf("Linux", "Haiku", "SwingOS").forEach { osName ->
            val error =
                assertFailsWith<UnsupportedDesktopPlatformException> {
                    DesktopPlatform.resolve(osName)
                }
            assertEquals(
                "unsupported operating system '$osName'; supported platforms are macOS and Windows",
                error.message,
            )
        }
    }
}
