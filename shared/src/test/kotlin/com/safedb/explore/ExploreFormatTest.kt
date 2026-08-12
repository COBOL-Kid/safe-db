package com.safedb.explore

import java.math.BigDecimal
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExploreFormatTest {
    @Test
    fun autoRendersPlainDecimalInEveryLocale() {
        val format = PivotNumberFormat(kind = NumberFormatKind.Auto)
        assertEquals("1200.5", fmt("1200.500", format))
        assertEquals("1200.5", fmt("1200.500", format, Locale.GERMANY))
    }

    @Test
    fun numberHonorsThousandsSeparatorToggleUsingLocaleSymbols() {
        val grouped = PivotNumberFormat(kind = NumberFormatKind.Number, decimals = 2)
        assertEquals("1,234,567.89", fmt("1234567.89", grouped))
        assertEquals("1.234.567,89", fmt("1234567.89", grouped, Locale.GERMANY))
        assertEquals("1234567.89", fmt("1234567.89", grouped.copy(thousandsSeparator = false)))
    }

    @Test
    fun percentScalesByHundredAndRoundsTheExactValue() {
        val format = PivotNumberFormat(kind = NumberFormatKind.Percent, decimals = 2)
        assertEquals("12.34%", fmt("0.1234", format))
        assertEquals("-25.60%", fmt("-0.256", format))
        assertEquals("50%", fmt("0.5", format.copy(decimals = 0)))
        // 0.12345 scaled exactly is 12.345, which HALF_EVEN rounds down; the old Double
        // multiply drifted to 12.345000000000001 and rounded up to 12.35.
        assertEquals("12.34%", fmt("0.12345", format))
    }

    @Test
    fun currencyResolvesTheCodeAndFallsBackWhenItIsUnknown() {
        val format = PivotNumberFormat(kind = NumberFormatKind.Currency, decimals = 2)
        assertEquals("$1,234.50", fmt("1234.5", format.copy(currencyCode = "USD")))
        assertEquals("€1,234.50", fmt("1234.5", format.copy(currencyCode = "EUR")))
        val unknown = fmt("1234.5", format.copy(currencyCode = "ZZZ"))
        assertFalse(unknown.contains('¤'), unknown)
        assertTrue(unknown.contains("1,234.50"), unknown)
    }

    @Test
    fun scientificUsesTheUppercaseExponentForm() {
        val format = PivotNumberFormat(kind = NumberFormatKind.Scientific, decimals = 2)
        assertEquals("1.23E3", fmt("1234.5", format))
        assertEquals("1.23E-4", fmt("0.000123", format))
        // Zero decimals leaves the pattern as "0.E0", so the separator survives.
        assertEquals("1.E3", fmt("1234.5", format.copy(decimals = 0)))
    }

    @Test
    fun decimalsAreCoercedIntoZeroThroughEight() {
        val format = PivotNumberFormat(kind = NumberFormatKind.Number, thousandsSeparator = false)
        assertEquals("1.50000000", fmt("1.5", format.copy(decimals = 12)))
        assertEquals("1", fmt("1.4", format.copy(decimals = -3)))
    }

    @Test
    fun largeIntegersKeepPrecisionThatDoubleWouldLose() {
        val format =
            PivotNumberFormat(
                kind = NumberFormatKind.Number,
                decimals = 0,
                thousandsSeparator = false,
            )
        assertEquals("9007199254740993", fmt("9007199254740993", format))
    }

    private fun fmt(
        value: String,
        format: PivotNumberFormat,
        locale: Locale = Locale.US,
    ): String {
        val previous = Locale.getDefault()
        Locale.setDefault(locale)
        return try {
            formatExploreNumber(BigDecimal(value), format)
        } finally {
            Locale.setDefault(previous)
        }
    }
}
