package com.safedb.buildlogic

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.gradle.api.GradleException

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
            listOf("darwin-arm64", "linux-arm64", "linux-x64", "win32-x64"),
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
        assertTrue(
            modules.containsAll(
                listOf("java.base", "java.sql", "jdk.net", "jdk.security.jgss", "jdk.unsupported")
            )
        )
        assertEquals(modules, modules.distinct())
    }

    @Test
    fun stampMetaPackageJsonWritesVersionAndOptionalDependencies() {
        val platforms = parseTemurinManifest(temurinManifestFile()).platforms
        val json =
            stampMetaPackageJson(
                """{"version": "0.0.0-dev", "optionalDependencies": {}}""",
                "1.2.3",
                platforms,
            )
        assertTrue(json.contains("\"version\": \"1.2.3\""))
        assertEquals(
            listOf(
                "\"@safe-db/mcp-darwin-arm64\": \"1.2.3\"",
                "\"@safe-db/mcp-linux-arm64\": \"1.2.3\"",
                "\"@safe-db/mcp-linux-x64\": \"1.2.3\"",
                "\"@safe-db/mcp-win32-x64\": \"1.2.3\"",
            ),
            Regex("\"@safe-db/mcp-[^\"]+\": \"1.2.3\"").findAll(json).map { it.value }.toList(),
        )
    }

    @Test
    fun replaceRequiredFailsWhenSentinelIsMissing() {
        val error =
            assertFailsWith<GradleException> {
                "{}".replaceRequired("\"version\": \"0.0.0-dev\"", "\"version\": \"1.0.0\"")
            }
        assertTrue(error.message.orEmpty().contains("\"version\": \"0.0.0-dev\""))
    }

    @Test
    fun extraModulesFromJdepsOutputIsEmptyWhenCovered() {
        assertEquals(
            emptyList(),
            extraModulesFromJdepsOutput("java.base, java.sql", setOf("java.base", "java.sql")),
        )
    }

    @Test
    fun extraModulesFromJdepsOutputListsUnknownModules() {
        assertEquals(
            listOf("java.desktop"),
            extraModulesFromJdepsOutput("java.base,java.desktop", setOf("java.base")),
        )
    }

    @Test
    fun extraModulesFromJdepsOutputFailsOnNonzeroExit() {
        assertFailsWith<GradleException> {
            extraModulesFromJdepsOutput("java.base", setOf("java.base"), exitValue = 1)
        }
    }

    private fun temurinManifestFile(): File = mcpNpmFile("temurin.json")

    private fun jlinkModulesFile(): File = mcpNpmFile("jlink-modules.txt")

    private fun mcpNpmFile(name: String): File {
        val file = File("../mcp/npm/$name").canonicalFile
        assertTrue(file.isFile, "missing $file")
        return file
    }
}
