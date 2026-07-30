package com.safedb.ui

import com.safedb.model.GroupSpec
import com.safedb.model.SortDirection
import com.safedb.model.SortSpec
import androidx.compose.ui.unit.Density
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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

    @Test
    fun sortOrderLabelsPreservePriorityDirectionAndResolveTableNames() {
        val labels = sortOrderLabels(
            sorts = listOf(
                SortSpec("t0", "status", SortDirection.Asc),
                SortSpec("missing", "created_at", SortDirection.Desc),
            ),
            tableNamesByAlias = mapOf("t0" to "orders"),
        )

        assertEquals(
            listOf("orders.status ascending", "missing.created_at descending"),
            labels,
        )
    }

    @Test
    fun queryOptionsShowNoneOnlyForEmptySections() {
        assertEquals("None", queryOptionEmptyLabel(emptyList()))
        assertNull(queryOptionEmptyLabel(listOf("orders.status")))
    }

    @Test
    fun queryControlsCanvasInsetScalesWithDisplayDensity() {
        val insetPx = with(Density(2f)) { QueryControlsCanvasInset.toPx() }

        assertEquals(464f, insetPx)
        assertEquals(464f, canvasDisplayY(tableY = 0f, contentTopInsetPx = insetPx))
        assertEquals(494f, canvasDisplayY(tableY = 30f, contentTopInsetPx = insetPx))
    }

    @Test
    fun queryOrderMoveTargetsRespectCurrentListBounds() {
        assertEquals(2, queryOrderMoveTarget(index = 1, offset = 1, lastIndex = 2))
        assertNull(queryOrderMoveTarget(index = 1, offset = 1, lastIndex = 1))
        assertEquals(1, queryOrderMoveTarget(index = 2, offset = -1, lastIndex = 2))
        assertNull(queryOrderMoveTarget(index = 0, offset = -1, lastIndex = 2))
    }
}
