package com.safedb.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class BuilderLimitChoicesTest {
    @Test
    fun builderUsesFixedLimitChoicesUpToFiveThousand() {
        assertEquals(listOf(100, 1_000, 5_000), BUILDER_LIMIT_CHOICES)
    }
}
