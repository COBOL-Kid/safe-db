package com.safedb.mcp

import com.safedb.platform.DesktopPlatform
import com.safedb.secrets.SecretsManager
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class McpSecretsTest {
    private val tempDirs = mutableListOf<Path>()

    @AfterTest
    fun resetSecrets() {
        SecretsManager.initStore("disabled")
        tempDirs.forEach { it.toFile().deleteRecursively() }
        tempDirs.clear()
    }

    private fun tempDir(prefix: String): Path =
        Files.createTempDirectory(prefix).also { tempDirs.add(it) }

    @Test
    fun linuxAndMacUseFileStoreUnlessDisabled() {
        val linuxDir = tempDir("safedb-mcp-secrets-linux")
        initMcpSecrets(DesktopPlatform.Linux, linuxDir, envValue = null)
        assertEquals("file", SecretsManager.activeBackendLabel())
        SecretsManager.savePasswordForDefinition(sampleMcpConnection("linux"), "secret")
            .getOrThrow()
        assertTrue(Files.isRegularFile(linuxDir.resolve("credentials").resolve("linux")))

        val macDir = tempDir("safedb-mcp-secrets-mac")
        initMcpSecrets(DesktopPlatform.MacOs, macDir, envValue = null)
        assertEquals("file", SecretsManager.activeBackendLabel())

        initMcpSecrets(
            DesktopPlatform.Linux,
            tempDir("safedb-mcp-secrets-disabled"),
            envValue = "disabled",
        )
        assertEquals("disabled", SecretsManager.activeBackendLabel())
    }

    @Test
    fun windowsDisabledStillUsesMemory() {
        initMcpSecrets(
            DesktopPlatform.Windows,
            tempDir("safedb-mcp-secrets-win"),
            envValue = "disabled",
        )
        assertEquals("disabled", SecretsManager.activeBackendLabel())
    }

    @Test
    fun windowsAutoAndProtectedDoNotUseFileStore() {
        listOf(null, "auto", "protected").forEach { envValue ->
            val dataDir = tempDir("safedb-mcp-secrets-win-auto")
            initMcpSecrets(DesktopPlatform.Windows, dataDir, envValue)

            assertNotEquals("file", SecretsManager.activeBackendLabel(), "envValue=$envValue")
            assertFalse(Files.exists(dataDir.resolve("credentials")), "envValue=$envValue")
        }
    }

    @Test
    fun unixAutoLikeBackendValuesDeterministicallyUseFileStorage() {
        listOf("auto", "protected", "legacy", "keychain", "unexpected", "AUTO", "   ").forEach {
            envValue ->
            val dataDir = tempDir("safedb-mcp-secrets-auto")
            initMcpSecrets(DesktopPlatform.Linux, dataDir, envValue)

            assertEquals("file", SecretsManager.activeBackendLabel(), "envValue=$envValue")
            assertTrue(Files.isDirectory(dataDir.resolve("credentials")))
        }
    }

    @Test
    fun disabledBackendDoesNotCreateCredentialFiles() {
        listOf(DesktopPlatform.Linux, DesktopPlatform.MacOs, DesktopPlatform.Windows).forEach {
            platform ->
            val dataDir = tempDir("safedb-mcp-secrets-disabled")
            initMcpSecrets(platform, dataDir, envValue = "disabled")

            assertEquals("disabled", SecretsManager.activeBackendLabel())
            assertFalse(Files.exists(dataDir.resolve("credentials")))
        }
    }
}
