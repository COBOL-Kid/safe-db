package com.safedb.query

import kotlin.test.Test
import kotlin.test.assertEquals

class LimitsTest {
    @Test
    fun builderLimitBoundsUseFiveHundredByDefaultAndTenThousandMaximum() {
        assertEquals(500, DEFAULT_LIMIT)
        assertEquals(10_000, MAX_LIMIT)
    }

    @Test
    fun parseLimitClampsIntRange() {
        assertEquals(DEFAULT_LIMIT, parseLimit(0))
        assertEquals(DEFAULT_LIMIT, parseLimit(-5))
        assertEquals(100, parseLimit(100))
        assertEquals(MAX_LIMIT, parseLimit(MAX_LIMIT))
        assertEquals(MAX_LIMIT, parseLimit(MAX_LIMIT + 1))
    }

    @Test
    fun parseLimitParsesStringDigits() {
        assertEquals(DEFAULT_LIMIT, parseLimit(""))
        assertEquals(DEFAULT_LIMIT, parseLimit("   "))
        assertEquals(DEFAULT_LIMIT, parseLimit("abc"))
        assertEquals(250, parseLimit("250"))
        assertEquals(250, parseLimit("250 rows"))
        assertEquals(MAX_LIMIT, parseLimit("${MAX_LIMIT + 500}"))
    }
}
