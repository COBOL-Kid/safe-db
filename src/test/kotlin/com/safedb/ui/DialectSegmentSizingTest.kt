package com.safedb.ui

import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals

class DialectSegmentSizingTest {
    @Test
    fun keepsThePreferredSizeWhenEveryLabelFits() {
        assertEquals(14.sp, fittedDialectLabelSize { true })
    }

    @Test
    fun stepsDownUntilTheLongestLabelFits() {
        val size = fittedDialectLabelSize { it <= 13.sp }
        assertEquals(13.sp, size)
    }

    @Test
    fun stopsAtTheMinimumWhenNothingFits() {
        assertEquals(11.sp, fittedDialectLabelSize { false })
    }
}
