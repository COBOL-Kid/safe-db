package com.safedb.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class ConnectionsScreenStateTest {
    @Test
    fun connectionCardsSurfaceActiveAndDefaultBadges() {
        assertEquals(emptyList(), connectionCardBadges("c1", null, null))
        assertEquals(listOf("Active"), connectionCardBadges("c1", "c1", null))
        assertEquals(listOf("Default"), connectionCardBadges("c1", null, "c1"))
        assertEquals(listOf("Active", "Default"), connectionCardBadges("c1", "c1", "c1"))
        assertEquals(emptyList(), connectionCardBadges("c1", "c2", "c3"))
    }
}
