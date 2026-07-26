package com.safedb.query

import kotlin.test.Test
import kotlin.test.assertEquals

class LimitsTest {
    @Test
    fun interactiveMaximumIsFiveThousand() {
        assertEquals(5_000, MAX_LIMIT)
    }

    @Test
    fun parseLimitClampsIntRange() {
        assertEquals(1, parseLimit(0))
        assertEquals(1, parseLimit(-5))
        assertEquals(100, parseLimit(100))
        assertEquals(MAX_LIMIT, parseLimit(MAX_LIMIT))
        assertEquals(MAX_LIMIT, parseLimit(MAX_LIMIT + 1))
    }

    @Test
    fun parseLimitParsesStringDigits() {
        assertEquals(1, parseLimit(""))
        assertEquals(1, parseLimit("   "))
        assertEquals(1, parseLimit("abc"))
        assertEquals(250, parseLimit("250"))
        assertEquals(250, parseLimit("250 rows"))
        assertEquals(MAX_LIMIT, parseLimit("${MAX_LIMIT + 500}"))
    }
}
