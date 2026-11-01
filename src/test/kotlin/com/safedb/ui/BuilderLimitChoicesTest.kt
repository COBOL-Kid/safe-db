package com.safedb.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class BuilderLimitChoicesTest {
    @Test
    fun allChoiceUsesTheEngineMaximum() {
        assertEquals(listOf(500, 1_000, 5_000, Int.MAX_VALUE), BUILDER_LIMIT_CHOICES)
        assertEquals(Int.MAX_VALUE, BUILDER_ALL_ROWS_LIMIT)
        assertEquals("%,d".format(500), builderLimitChoiceLabel(500))
        assertEquals("%,d".format(1_000), builderLimitChoiceLabel(1_000))
        assertEquals("%,d".format(5_000), builderLimitChoiceLabel(5_000))
        assertEquals("%,d".format(10_000), builderLimitChoiceLabel(10_000))
        assertEquals("ALL", builderLimitChoiceLabel(BUILDER_ALL_ROWS_LIMIT))
    }

    @Test
    fun limitFieldShowsAllOnlyForTheUncappedChoice() {
        assertEquals("500", limitFieldText(500))
        assertEquals("10000", limitFieldText(10_000))
        assertEquals("ALL", limitFieldText(BUILDER_ALL_ROWS_LIMIT))
        assertEquals(10_000, parseBuilderLimitText("10000"))
        assertEquals(BUILDER_ALL_ROWS_LIMIT, parseBuilderLimitText("ALL"))
        assertEquals(BUILDER_ALL_ROWS_LIMIT, parseBuilderLimitText(" all "))
        assertEquals(500, parseBuilderLimitText(""))
    }
}
