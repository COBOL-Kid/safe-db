package com.safedb.platform

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PlatformPathsTest {
    @Test
    fun rejectsUnsupportedPlatformBeforeResolvingADataPath() {
        val error = assertFailsWith<UnsupportedDesktopPlatformException> {
            DataDirectory.baseDir(PlatformEnvironment("Linux", "/home/test"))
        }
        assertEquals(
            "unsupported operating system 'Linux'; supported platforms are macOS and Windows",
            error.message,
        )
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
                PlatformEnvironment("Windows 11", "C:/Users/test", appData = "C:/Users/test/AppData/Roaming"),
            ),
        )
    }

    @Test
    fun windowsRequiresAppData() {
        assertFailsWith<IllegalArgumentException> {
            DataDirectory.baseDir(PlatformEnvironment("Windows 11", "C:/Users/test"))
        }
    }

    @Test
    fun legacyResolverUsesFirstMarkedCandidateOrFallback() {
        val root = Files.createTempDirectory("safedb-platform-test")
        val empty = Files.createDirectories(root.resolve("empty"))
        val marked = Files.createDirectories(root.resolve("marked"))
        Files.writeString(marked.resolve("settings.json"), "{}")
        val fallback = root.resolve("fallback")

        assertEquals(marked, LegacyDataImport.resolveDataDir(listOf(empty, marked)) { fallback })
        assertEquals(fallback, LegacyDataImport.resolveDataDir(listOf(empty)) { fallback })
    }
}
