package com.safedb.ui

import com.safedb.explore.NumberFormatKind
import com.safedb.explore.PivotNumberFormat
import com.safedb.explore.formatExploreNumber
import com.safedb.model.ResultCell
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals

class WorksheetValueFormattingTest {
    // Postgres `double precision` really returns NaN and Infinity, and this formatting runs inside
    // composition, so a throw here takes the Explore window down rather than showing a bad cell.
    @Test
    fun nonFiniteDoublesFallBackToPlainTextAndFiniteOnesStillFormat() {
        val number =
            PivotNumberFormat(
                kind = NumberFormatKind.Number,
                decimals = 2,
                thousandsSeparator = false,
            )

        assertEquals("NaN", formatWorksheetValue(ResultCell.FloatCell(Double.NaN), number))
        assertEquals(
            "Infinity",
            formatWorksheetValue(ResultCell.FloatCell(Double.POSITIVE_INFINITY), number),
        )
        // No format at all must not even attempt the conversion.
        assertEquals(
            "-Infinity",
            formatWorksheetValue(ResultCell.FloatCell(Double.NEGATIVE_INFINITY), null),
        )

        // Finite values still delegate unchanged (locale-independent assertion).
        assertEquals(
            formatExploreNumber(BigDecimal("1234.5"), number),
            formatWorksheetValue(ResultCell.FloatCell(1234.5), number),
        )
        assertEquals(
            formatExploreNumber(BigDecimal.valueOf(7L), number),
            formatWorksheetValue(ResultCell.IntegerCell(7), number),
        )
    }
}
