package com.safedb

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppVersionTest {
    @Test
    fun appVersionResourceIsOnTheClasspath() {
        val version = loadAppVersion()
        assertTrue(version.isNotBlank())
        assertFalse(version.contains("\${"))
    }
}
