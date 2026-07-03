package com.safedb.query

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ColumnKeysTest {
    @Test
    fun columnKeyRoundTrip() {
        val key = columnKey("t0", "email")
        assertEquals("t0" to "email", parseColumnKey(key))
    }

    @Test
    fun parseColumnKeySupportsLegacyDotSeparator() {
        assertEquals("t0" to "email", parseColumnKey("t0.email"))
    }

    @Test
    fun parseColumnKeyWithoutSeparatorReturnsEmptyColumn() {
        assertEquals("aliasOnly" to "", parseColumnKey("aliasOnly"))
    }

    @Test
    fun columnKeyPrefixMatchesAlias() {
        val key = columnKey("t1", "id")
        assertTrue(key.startsWith(columnKeyPrefix("t1")))
    }
}
