package com.safedb.ui

import com.safedb.explore.WorksheetColumnLayout
import com.safedb.explore.WorksheetValueRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class WorksheetColumnInteractionsTest {
    private val a = WorksheetValueRef.Column("a")
    private val hidden = WorksheetValueRef.Column("hidden")
    private val b = WorksheetValueRef.Column("b")
    private val calculation = WorksheetValueRef.Calculation("calculation")
    private val layout =
        listOf(
            WorksheetColumnLayout(a),
            WorksheetColumnLayout(hidden, visible = false),
            WorksheetColumnLayout(b),
            WorksheetColumnLayout(calculation),
        )

    @Test
    fun headerArrowsMoveOneVisibleColumnWhileHiddenSlotStaysFixed() {
        val right = moveVisibleWorksheetColumn(layout, fromVisibleIndex = 0, toVisibleIndex = 1)
        assertEquals(listOf(b, hidden, a, calculation), right.map { it.ref })
        assertFalse(right[1].visible)

        val left = moveVisibleWorksheetColumn(right, fromVisibleIndex = 1, toVisibleIndex = 0)
        assertEquals(layout, left)

        assertEquals(
            layout,
            moveVisibleWorksheetColumn(layout, fromVisibleIndex = 0, toVisibleIndex = -1),
        )
        assertEquals(
            layout,
            moveVisibleWorksheetColumn(layout, fromVisibleIndex = 2, toVisibleIndex = 3),
        )
    }

    @Test
    fun eyeActionHidesSourceAndCalculatedColumnsWithoutChangingOrder() {
        val sourceHidden = setWorksheetColumnVisibility(layout, b, visible = false)
        val calculationHidden =
            setWorksheetColumnVisibility(sourceHidden, calculation, visible = false)

        assertEquals(layout.map { it.ref }, calculationHidden.map { it.ref })
        assertFalse(calculationHidden.first { it.ref == b }.visible)
        assertFalse(calculationHidden.first { it.ref == calculation }.visible)
    }
}
