package com.safedb.mcp

import com.safedb.platform.DesktopPlatform
import com.safedb.secrets.SecretsManager
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpSecretsTest {
    @AfterTest
    fun resetSecrets() {
        SecretsManager.initStore("disabled")
    }

    @Test
    fun linuxAndMacUseFileStoreUnlessDisabled() {
        val linuxDir = Files.createTempDirectory("safedb-mcp-secrets-linux")
        initMcpSecrets(DesktopPlatform.Linux, linuxDir, envValue = null)
        assertEquals("file", SecretsManager.activeBackendLabel())
        SecretsManager.savePasswordForDefinition(sampleMcpConnection("linux"), "secret")
            .getOrThrow()
        assertTrue(Files.isRegularFile(linuxDir.resolve("credentials").resolve("linux")))

        val macDir = Files.createTempDirectory("safedb-mcp-secrets-mac")
        initMcpSecrets(DesktopPlatform.MacOs, macDir, envValue = null)
        assertEquals("file", SecretsManager.activeBackendLabel())

        initMcpSecrets(
            DesktopPlatform.Linux,
            Files.createTempDirectory("safedb-mcp-secrets-disabled"),
            envValue = "disabled",
        )
        assertEquals("disabled", SecretsManager.activeBackendLabel())
    }

    @Test
    fun windowsDisabledStillUsesMemory() {
        initMcpSecrets(
            DesktopPlatform.Windows,
            Files.createTempDirectory("safedb-mcp-secrets-win"),
            envValue = "disabled",
        )
        assertEquals("disabled", SecretsManager.activeBackendLabel())
    }

    @Test
    fun unixAutoLikeBackendValuesDeterministicallyUseFileStorage() {
        listOf("auto", "protected", "legacy", "keychain", "unexpected", "AUTO", "   ").forEach {
            envValue ->
            val dataDir = Files.createTempDirectory("safedb-mcp-secrets-auto")
            initMcpSecrets(DesktopPlatform.Linux, dataDir, envValue)

            assertEquals("file", SecretsManager.activeBackendLabel(), "envValue=$envValue")
            assertTrue(Files.isDirectory(dataDir.resolve("credentials")))
        }
    }

    @Test
    fun disabledBackendDoesNotCreateCredentialFiles() {
        listOf(DesktopPlatform.Linux, DesktopPlatform.MacOs, DesktopPlatform.Windows).forEach {
            platform ->
            val dataDir = Files.createTempDirectory("safedb-mcp-secrets-disabled")
            initMcpSecrets(platform, dataDir, envValue = "disabled")

            assertEquals("disabled", SecretsManager.activeBackendLabel())
            assertFalse(Files.exists(dataDir.resolve("credentials")))
        }
    }
}
