package com.safedb.ui

import androidx.compose.ui.state.ToggleableState
import com.safedb.canvas.CANVAS_CARD_WIDTH
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TableCardStateTest {
    @Test
    fun joinActionDescriptionExplainsStartingAndCompletingAJoin() {
        assertEquals(
            "Join from customer_id; click to select or drag to another indexed column",
            joinActionDescription("customer_id", selectingTarget = false),
        )
        assertEquals(
            "Join to id; click to complete the join",
            joinActionDescription("id", selectingTarget = true),
        )
    }

    @Test
    fun tableColumnToggleStateRepresentsEmptyPartialAndCompleteSelection() {
        assertEquals(ToggleableState.Off, tableColumnToggleState(0, 3))
        assertEquals(ToggleableState.Indeterminate, tableColumnToggleState(1, 3))
        assertEquals(ToggleableState.On, tableColumnToggleState(3, 3))
        assertEquals(ToggleableState.Off, tableColumnToggleState(0, 0))
    }

    @Test
    fun columnActionClusterReservesStableSlotsForFilterGroupAndSort() {
        assertEquals(28, TABLE_CARD_COLUMN_ACTION_SLOT_DP)
        assertEquals(3, TABLE_CARD_COLUMN_ACTION_COUNT)
        assertEquals(84, tableColumnActionClusterWidthDp())
    }

    @Test
    fun defaultCanvasWidthHidesColumnTypesSoNamesStayReadable() {
        assertTrue(CANVAS_CARD_WIDTH < TABLE_CARD_TYPE_VISIBLE_MIN_WIDTH_DP)
        assertEquals(false, tableCardShowsColumnType(CANVAS_CARD_WIDTH))
        assertEquals(false, tableCardShowsColumnType(TABLE_CARD_TYPE_VISIBLE_MIN_WIDTH_DP - 1))
        assertEquals(true, tableCardShowsColumnType(TABLE_CARD_TYPE_VISIBLE_MIN_WIDTH_DP))
        assertEquals(true, tableCardShowsColumnType(320f))
    }
}
