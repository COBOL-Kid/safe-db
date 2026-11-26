package com.safedb

import com.safedb.platform.DesktopPlatform
import com.safedb.secrets.SecretsManager
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MainTest {
    @AfterTest
    fun resetSecretsStore() {
        SecretsManager.lockCredentials()
        SecretsManager.initStore("disabled")
    }

    @Test
    fun unsupportedPlatformExitsBeforeStartup() {
        listOf("Linux", "Haiku").forEach { osName ->
            var reported = ""

            val exit =
                assertFailsWith<ExitInvoked> {
                    requireSupportedDesktopPlatform(
                        osName = osName,
                        reportError = { reported = it },
                        exit = { throw ExitInvoked(it) },
                    )
                }

            assertEquals(2, exit.status)
            assertEquals(
                "safe-db: unsupported operating system '$osName'; supported platforms are macOS and Windows",
                reported,
            )
        }
    }

    @Test
    fun isSmokeCheckArgsMatchesOnlyExactFlag() {
        assertTrue(isSmokeCheckArgs(arrayOf("--smoke-check")))
        assertFalse(isSmokeCheckArgs(emptyArray()))
        assertFalse(isSmokeCheckArgs(arrayOf("--launch-profile", "/tmp/profile.json")))
        assertFalse(isSmokeCheckArgs(arrayOf("--smoke-check", "--extra")))
    }

    @Test
    fun dispatchStartupRejectsUnsupportedPlatformBeforeSmokeCheck() {
        var smokeCheckInvoked = false
        var desktopInvoked = false
        var reported = ""

        val exit =
            assertFailsWith<ExitInvoked> {
                dispatchStartup(
                    args = arrayOf("--smoke-check"),
                    osName = "Linux",
                    reportError = { reported = it },
                    exit = { throw ExitInvoked(it) },
                    smokeCheck = {
                        smokeCheckInvoked = true
                        throw ExitInvoked(0)
                    },
                    runDesktop = { desktopInvoked = true },
                )
            }

        assertEquals(2, exit.status)
        assertFalse(smokeCheckInvoked)
        assertFalse(desktopInvoked)
        assertTrue(reported.contains("unsupported operating system 'Linux'"))
    }

    @Test
    fun dispatchStartupRunsSmokeCheckBeforeLaunchProfileOnSupportedPlatform() {
        var smokePlatform: DesktopPlatform? = null
        var desktopPlatform: DesktopPlatform? = null

        val exit =
            assertFailsWith<ExitInvoked> {
                dispatchStartup(
                    args = arrayOf("--smoke-check"),
                    osName = "Mac OS X",
                    exit = { throw ExitInvoked(it) },
                    smokeCheck = { platform ->
                        smokePlatform = platform
                        throw ExitInvoked(0)
                    },
                    runDesktop = { desktopPlatform = it },
                )
            }

        assertEquals(0, exit.status)
        assertEquals(DesktopPlatform.MacOs, smokePlatform)
        assertEquals(null, desktopPlatform)

        var normalSmokeInvoked = false
        var normalDesktopPlatform: DesktopPlatform? = null
        dispatchStartup(
            args = emptyArray(),
            osName = "Windows 11",
            exit = { throw ExitInvoked(it) },
            smokeCheck = {
                normalSmokeInvoked = true
                throw ExitInvoked(0)
            },
            runDesktop = { normalDesktopPlatform = it },
        )

        assertFalse(normalSmokeInvoked)
        assertEquals(DesktopPlatform.Windows, normalDesktopPlatform)
    }

    @Test
    fun smokeCheckVerifiesVersionStoresIconComposeAndCleansUpTempDir() {
        var createdTempDir: Path? = null
        val outputs = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val events = mutableListOf<String>()
        val expectedVersion = loadAppVersion()
        val originalOut = System.out
        val capturedOut = ByteArrayOutputStream()

        val exit =
            try {
                System.setOut(PrintStream(capturedOut, true, Charsets.UTF_8))
                assertFailsWith<ExitInvoked> {
                    runSmokeCheck(
                        platform = DesktopPlatform.MacOs,
                        createTempDir = {
                            Files.createTempDirectory("safe-db-smoke-test-").also {
                                createdTempDir = it
                            }
                        },
                        configureHeadless = { events += "headless" },
                        loadIcon = {
                            events += "icon"
                            loadAppWindowIconImage()
                        },
                        renderFrame = {
                            events += "frame"
                            renderSmokeComposeFrame()
                        },
                        reportOutput = { outputs += it },
                        reportError = { errors += it },
                        exit = { status ->
                            assertFalse(
                                Files.exists(checkNotNull(createdTempDir)),
                                "temp dir must be deleted before exit($status)",
                            )
                            throw ExitInvoked(status)
                        },
                    )
                }
            } finally {
                System.setOut(originalOut)
            }

        assertEquals(0, exit.status)
        assertEquals(listOf("headless", "icon", "frame"), events)
        assertEquals("", capturedOut.toString(Charsets.UTF_8))
        assertEquals(listOf("safe-db $expectedVersion smoke-check ok"), outputs)
        assertTrue(errors.isEmpty())
        assertEquals("disabled", SecretsManager.activeBackendLabel())
        val tempDir = checkNotNull(createdTempDir)
        assertFalse(Files.exists(tempDir))
    }

    @Test
    fun smokeCheckExitsOneAndCleansUpTempDirOnFailure() {
        val failureCases =
            listOf<() -> ExitInvoked>(
                {
                    val outputs = mutableListOf<String>()
                    val errors = mutableListOf<String>()
                    var tempDir: Path? = null
                    val exit =
                        assertFailsWith<ExitInvoked> {
                            runSmokeCheck(
                                platform = DesktopPlatform.Windows,
                                createTempDir = {
                                    Files.createTempDirectory("safe-db-smoke-fail-").also {
                                        tempDir = it
                                    }
                                },
                                configureHeadless = {},
                                loadVersion = { "   " },
                                loadIcon = { BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB) },
                                renderFrame = {},
                                reportOutput = { outputs += it },
                                reportError = { errors += it },
                                exit = { status ->
                                    assertFalse(
                                        Files.exists(checkNotNull(tempDir)),
                                        "temp dir must be deleted before exit($status)",
                                    )
                                    throw ExitInvoked(status)
                                },
                            )
                        }
                    assertTrue(outputs.isEmpty())
                    assertTrue(errors.single().startsWith("safe-db: smoke-check failed:"))
                    assertFalse(Files.exists(checkNotNull(tempDir)))
                    exit
                },
                {
                    val errors = mutableListOf<String>()
                    var tempDir: Path? = null
                    val exit =
                        assertFailsWith<ExitInvoked> {
                            runSmokeCheck(
                                platform = DesktopPlatform.MacOs,
                                createTempDir = {
                                    Files.createTempDirectory("safe-db-smoke-fail-").also {
                                        tempDir = it
                                    }
                                },
                                configureHeadless = {},
                                loadVersion = { "1.0.0" },
                                loadIcon = { error("missing icon") },
                                renderFrame = {},
                                reportOutput = {},
                                reportError = { errors += it },
                                exit = { status ->
                                    assertFalse(
                                        Files.exists(checkNotNull(tempDir)),
                                        "temp dir must be deleted before exit($status)",
                                    )
                                    throw ExitInvoked(status)
                                },
                            )
                        }
                    assertTrue(errors.single().contains("missing icon"))
                    assertFalse(Files.exists(checkNotNull(tempDir)))
                    exit
                },
                {
                    val errors = mutableListOf<String>()
                    var tempDir: Path? = null
                    val exit =
                        assertFailsWith<ExitInvoked> {
                            runSmokeCheck(
                                platform = DesktopPlatform.MacOs,
                                createTempDir = {
                                    Files.createTempDirectory("safe-db-smoke-fail-").also {
                                        tempDir = it
                                    }
                                },
                                configureHeadless = {},
                                loadVersion = { "1.0.0" },
                                loadIcon = { BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB) },
                                renderFrame = { throw UnsatisfiedLinkError("skiko missing") },
                                reportOutput = {},
                                reportError = { errors += it },
                                exit = { status ->
                                    assertFalse(
                                        Files.exists(checkNotNull(tempDir)),
                                        "temp dir must be deleted before exit($status)",
                                    )
                                    throw ExitInvoked(status)
                                },
                            )
                        }
                    assertTrue(errors.single().contains("skiko missing"))
                    assertFalse(Files.exists(checkNotNull(tempDir)))
                    exit
                },
                {
                    val outputs = mutableListOf<String>()
                    val errors = mutableListOf<String>()
                    val exit =
                        assertFailsWith<ExitInvoked> {
                            runSmokeCheck(
                                platform = DesktopPlatform.Windows,
                                createTempDir = { error("temp dir creation failed") },
                                configureHeadless = {},
                                loadVersion = { "1.0.0" },
                                loadIcon = { BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB) },
                                renderFrame = {},
                                reportOutput = { outputs += it },
                                reportError = { errors += it },
                                exit = { status -> throw ExitInvoked(status) },
                            )
                        }
                    assertTrue(outputs.isEmpty())
                    assertTrue(errors.single().contains("temp dir creation failed"))
                    exit
                },
                {
                    val outputs = mutableListOf<String>()
                    val errors = mutableListOf<String>()
                    var tempDir: Path? = null
                    val exit =
                        assertFailsWith<ExitInvoked> {
                            runSmokeCheck(
                                platform = DesktopPlatform.MacOs,
                                createTempDir = {
                                    Files.createTempDirectory("safe-db-smoke-fail-").also {
                                        tempDir = it
                                    }
                                },
                                configureHeadless = {},
                                loadVersion = { "1.0.0" },
                                loadIcon = { BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB) },
                                renderFrame = { throw IllegalStateException("") },
                                reportOutput = { outputs += it },
                                reportError = { errors += it },
                                exit = { status -> throw ExitInvoked(status) },
                            )
                        }
                    assertTrue(outputs.isEmpty())
                    assertEquals(
                        "safe-db: smoke-check failed: IllegalStateException",
                        errors.single(),
                    )
                    assertFalse(Files.exists(checkNotNull(tempDir)))
                    exit
                },
            )

        failureCases.forEach { runCase -> assertEquals(1, runCase().status) }
    }

    private class ExitInvoked(val status: Int) : RuntimeException()
}
