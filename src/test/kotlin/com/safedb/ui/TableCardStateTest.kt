package com.safedb.ui

import androidx.compose.ui.state.ToggleableState
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
