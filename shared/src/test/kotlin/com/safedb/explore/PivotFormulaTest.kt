package com.safedb.explore

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PivotFormulaTest {
    @Test
    fun evaluatesMeasureReferencesWithArithmeticPrecedence() {
        val result =
            evaluatePivotFormula(
                "([revenue] - [cost]) / [revenue]",
                mapOf("revenue" to BigDecimal("200"), "cost" to BigDecimal("50")),
            )

        assertEquals(BigDecimal("0.75"), result.value?.stripTrailingZeros())
        assertNull(result.error)
    }

    @Test
    fun reportsUnknownReferencesAndDivisionByZero() {
        assertNotNull(evaluatePivotFormula("[missing] + 1", emptyMap()).error)
        assertEquals("Division by zero", evaluatePivotFormula("1 / 0", emptyMap()).error)
    }
}
