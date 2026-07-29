package com.safedb.ui

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
}
