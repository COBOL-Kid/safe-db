package com.safedb.ui

import com.safedb.explore.NumberFormatKind
import com.safedb.explore.PivotNumberFormat
import com.safedb.model.ResultCell
import kotlin.test.Test
import kotlin.test.assertEquals

class ExploreWorksheetCalculationsTest {
    @Test
    fun nonFiniteFloatsFormatAsStringsInsteadOfThrowing() {
        assertEquals("NaN", formatWorksheetValue(ResultCell.FloatCell(Double.NaN), null))
        assertEquals(
            "Infinity",
            formatWorksheetValue(
                ResultCell.FloatCell(Double.POSITIVE_INFINITY),
                PivotNumberFormat(kind = NumberFormatKind.Number),
            ),
        )
    }
}
