package com.safedb.query

import kotlin.test.Test
import kotlin.test.assertEquals

class LimitsTest {
    @Test
    fun defaultLimitStaysFiveHundred() {
        assertEquals(500, DEFAULT_LIMIT)
    }

    @Test
    fun parseLimitKeepsAnyPositiveInt() {
        assertEquals(DEFAULT_LIMIT, parseLimit(0))
        assertEquals(DEFAULT_LIMIT, parseLimit(-5))
        assertEquals(100, parseLimit(100))
        assertEquals(20_000, parseLimit(20_000))
        assertEquals(Int.MAX_VALUE, parseLimit(Int.MAX_VALUE))
    }

    @Test
    fun parseLimitParsesStringDigits() {
        assertEquals(DEFAULT_LIMIT, parseLimit(""))
        assertEquals(DEFAULT_LIMIT, parseLimit("   "))
        assertEquals(DEFAULT_LIMIT, parseLimit("abc"))
        assertEquals(250, parseLimit("250"))
        assertEquals(250, parseLimit("250 rows"))
        assertEquals(20_000, parseLimit("20000 rows"))
        assertEquals(Int.MAX_VALUE, parseLimit("2147483648"))
        assertEquals(Int.MAX_VALUE, parseLimit("999999999999"))
    }
}
