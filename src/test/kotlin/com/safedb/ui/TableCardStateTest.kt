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
        assertEquals(72, TABLE_CARD_COLUMN_NAME_MIN_WIDTH_DP)
    }

    @Test
    fun defaultCanvasWidthHidesColumnTypesSoNamesStayReadable() {
        assertTrue(CANVAS_CARD_WIDTH < 350f)
        assertEquals(false, tableCardShowsColumnType(349f))
        assertEquals(true, tableCardShowsColumnType(350f))
        assertEquals(true, tableCardShowsColumnType(351f))
    }
}
