package com.safedb.buildlogic

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class McpNpmTest {
    @Test
    fun npmPlatformMapsOsAndArch() {
        assertEquals("linux-x64", npmPlatform("Linux", "amd64"))
        assertEquals("linux-arm64", npmPlatform("Linux", "aarch64"))
        assertEquals("darwin-arm64", npmPlatform("Mac OS X", "aarch64"))
        assertEquals("darwin-x64", npmPlatform("Mac OS X", "x86_64"))
        assertEquals("win32-x64", npmPlatform("Windows 11", "amd64"))
        assertFails { npmPlatform("Linux", "riscv64") }
        assertFails { npmPlatform("FreeBSD", "amd64") }
    }

    @Test
    fun parseTemurinManifestIncludesPinnedPlatforms() {
        val manifest = parseTemurinManifest(temurinManifestFile())
        assertEquals("jdk-25.0.4+7", manifest.releaseName)
        assertTrue(manifest.jlinkJdk.sha256.matches(Regex("[0-9a-f]{64}")))
        assertEquals(
            listOf("darwin-arm64", "darwin-x64", "linux-arm64", "linux-x64", "win32-x64"),
            manifest.platforms.map { it.npm },
        )
        manifest.platforms.forEach { platform ->
            assertTrue(platform.artifact.sha256.matches(Regex("[0-9a-f]{64}")))
            assertTrue(platform.os.isNotEmpty())
            assertTrue(platform.cpu.isNotEmpty())
        }
    }

    @Test
    fun jlinkModulesAreExplicitAndNonEmpty() {
        val modules = readJlinkModules(jlinkModulesFile())
        assertTrue(modules.containsAll(listOf("java.base", "java.sql", "jdk.unsupported")))
        assertEquals(modules, modules.distinct())
    }

    private fun temurinManifestFile(): File = mcpNpmFile("temurin.json")

    private fun jlinkModulesFile(): File = mcpNpmFile("jlink-modules.txt")

    private fun mcpNpmFile(name: String): File {
        val file = File("../mcp/npm/$name").canonicalFile
        assertTrue(file.isFile, "missing $file")
        return file
    }
}
