package com.safedb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MainTest {
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

    private class ExitInvoked(val status: Int) : RuntimeException()
}
