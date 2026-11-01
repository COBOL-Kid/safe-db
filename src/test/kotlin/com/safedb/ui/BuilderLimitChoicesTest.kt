package com.safedb.ui

import com.safedb.ui.util.limitFieldText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BuilderLimitChoicesTest {
    @Test
    fun numericChipsStayGroupedAndAllIsASeparateLabel() {
        assertEquals(listOf(500, 1_000, 5_000), BUILDER_LIMIT_CHOICES)
        assertEquals("ALL", BUILDER_ALL_LIMIT_LABEL)
        assertEquals("%,d".format(500), builderLimitChoiceLabel(500))
        assertEquals("%,d".format(1_000), builderLimitChoiceLabel(1_000))
        assertEquals("%,d".format(5_000), builderLimitChoiceLabel(5_000))
        assertEquals("%,d".format(10_000), builderLimitChoiceLabel(10_000))
    }

    @Test
    fun limitFieldShowsAllOnlyWhenUncapped() {
        assertEquals("500", limitFieldText(500, false))
        assertEquals("10000", limitFieldText(10_000, false))
        assertEquals("2147483647", limitFieldText(Int.MAX_VALUE, false))
        assertEquals("ALL", limitFieldText(500, true))
        assertEquals("ALL", limitFieldText(Int.MAX_VALUE, true))
    }

    @Test
    fun draftCommitsANumericCapAndLeavesBlankOrZeroAlone() {
        assertEquals(10_000 to false, commitBuilderLimitDraft("10000"))
        assertEquals(Int.MAX_VALUE to false, commitBuilderLimitDraft("2147483647"))
        assertEquals(Int.MAX_VALUE to false, commitBuilderLimitDraft("2147483648"))
        assertEquals(Int.MAX_VALUE to false, commitBuilderLimitDraft("99999999999999999999"))
        assertNull(commitBuilderLimitDraft(""))
        assertNull(commitBuilderLimitDraft("0"))
        assertNull(commitBuilderLimitDraft("000"))
        assertNull(commitBuilderLimitDraft("ALL"))
    }
}
