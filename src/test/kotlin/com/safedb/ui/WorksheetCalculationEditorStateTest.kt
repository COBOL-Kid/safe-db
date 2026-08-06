package com.safedb.ui

import com.safedb.explore.WorksheetGrain
import kotlin.test.Test
import kotlin.test.assertEquals

class WorksheetCalculationEditorStateTest {
    @Test
    fun removedCalculationSourceIsClearedUntilTheUserChoosesAgain() {
        val reconciled =
            reconcileWorksheetCalculationEditorSelection(
                WorksheetCalculationEditorSelection(
                    source = "deleted_calculation",
                    groupColumn = "status",
                    grain = WorksheetGrain.GroupRows,
                ),
                sourceOptions = listOf("amount", "active_calculation"),
                groupColumns = listOf("status"),
            )

        assertEquals("", reconciled.source)
        assertEquals("status", reconciled.groupColumn)
        assertEquals(WorksheetGrain.GroupRows, reconciled.grain)
    }

    @Test
    fun removingLastGroupClearsGroupTargetAndReturnsToDetailGrain() {
        val reconciled =
            reconcileWorksheetCalculationEditorSelection(
                WorksheetCalculationEditorSelection(
                    source = "amount",
                    groupColumn = "status",
                    grain = WorksheetGrain.GroupRows,
                ),
                sourceOptions = listOf("amount"),
                groupColumns = emptyList(),
            )

        assertEquals("amount", reconciled.source)
        assertEquals("", reconciled.groupColumn)
        assertEquals(WorksheetGrain.DetailRows, reconciled.grain)
    }
}
