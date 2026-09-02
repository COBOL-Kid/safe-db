package com.safedb.platform

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DataDirectoryTest {
    @Test
    fun rejectsLinuxBeforeResolvingADesktopDataPath() {
        val error =
            assertFailsWith<DesktopStoreUnavailableException> {
                DataDirectory.baseDir(PlatformEnvironment("Linux", "/home/test"))
            }
        assertEquals("desktop app data directory is not available on Linux", error.message)
    }

    @Test
    fun resolvesMacAndWindowsPaths() {
        assertEquals(
            Path.of("/Users/test/Library/Application Support"),
            DataDirectory.baseDir(PlatformEnvironment("Mac OS X", "/Users/test")),
        )
        assertEquals(
            Path.of("/Users/test/Library/Application Support"),
            DataDirectory.baseDir(PlatformEnvironment("Darwin", "/Users/test")),
        )
        assertEquals(
            Path.of("C:/Users/test/AppData/Roaming"),
            DataDirectory.baseDir(
                PlatformEnvironment(
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
            DataDirectory.baseDir(PlatformEnvironment("Windows 11", "C:/Users/test"))
        }
    }
}
