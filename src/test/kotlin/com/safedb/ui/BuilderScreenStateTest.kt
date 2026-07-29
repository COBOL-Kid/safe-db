package com.safedb.ui

import com.safedb.model.GroupSpec
import kotlin.test.Test
import kotlin.test.assertEquals

class BuilderScreenStateTest {
    @Test
    fun downArrowRestoresMaximizedResultsPaneToMinimumHeight() {
        val state = toggleResultsPane(ResultsPaneMode.Maximized, height = 240f)

        assertEquals(ResultsPaneMode.Normal, state.mode)
        assertEquals(128f, state.height)
    }

    @Test
    fun upArrowMaximizesResultsPaneWithoutChangingItsResizeHeight() {
        val state = toggleResultsPane(ResultsPaneMode.Normal, height = 320f)

        assertEquals(ResultsPaneMode.Maximized, state.mode)
        assertEquals(320f, state.height)
    }

    @Test
    fun groupingOrderLabelsPreservePriorityAndResolveTableNames() {
        val labels = groupingOrderLabels(
            groups = listOf(
                GroupSpec("t1", "region"),
                GroupSpec("t0", "status"),
                GroupSpec("missing", "category"),
            ),
            tableNamesByAlias = mapOf("t0" to "orders", "t1" to "customers"),
        )

        assertEquals(
            listOf("customers.region", "orders.status", "missing.category"),
            labels,
        )
    }
}
