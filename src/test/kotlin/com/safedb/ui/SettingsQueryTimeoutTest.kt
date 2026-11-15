package com.safedb.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SettingsQueryTimeoutTest {
    @Test
    fun timeoutDraftParsesOnlyWholeSecondsInRange() {
        assertEquals(30, parseQueryTimeoutSeconds("30"))
        assertEquals(45, parseQueryTimeoutSeconds(" 45 "))
        assertEquals(1, parseQueryTimeoutSeconds("1"))
        assertEquals(120, parseQueryTimeoutSeconds("120"))
        assertNull(parseQueryTimeoutSeconds("0"))
        assertNull(parseQueryTimeoutSeconds("121"))
        assertNull(parseQueryTimeoutSeconds(""))
        assertNull(parseQueryTimeoutSeconds("10.5"))
        assertNull(parseQueryTimeoutSeconds("slow"))
    }
}
