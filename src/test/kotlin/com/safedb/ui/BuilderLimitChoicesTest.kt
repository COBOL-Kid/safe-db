package com.safedb.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class BuilderLimitChoicesTest {
    @Test
    fun builderUsesFixedLimitChoicesUpToTenThousand() {
        assertEquals(listOf(500, 1_000, 5_000, 10_000), BUILDER_LIMIT_CHOICES)
    }
}
