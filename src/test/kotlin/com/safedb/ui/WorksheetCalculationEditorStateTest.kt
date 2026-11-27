package com.safedb.ui

import com.safedb.explore.WorksheetAggregateFn
import com.safedb.explore.WorksheetCalculation
import com.safedb.explore.WorksheetGrain
import com.safedb.explore.displayColumnLabels
import com.safedb.model.ResultColumn
import com.safedb.model.TableRef
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

    @Test
    fun calculationSummaryUsesTableQualifiedLabelsForAggregates() {
        val tables =
            listOf(
                TableRef("safedb_test", "orders", "t0"),
                TableRef("safedb_test", "customers", "t1"),
            )
        val columns =
            listOf(
                ResultColumn("t0__id", "int"),
                ResultColumn("t1__id", "int"),
            )
        val labels = displayColumnLabels(columns, tables)

        assertEquals(
            "Sum · orders.id",
            calculationSummary(
                WorksheetCalculation.Aggregate(
                    "a1",
                    "Order Ids",
                    WorksheetAggregateFn.Sum,
                    "t0__id",
                ),
                columnLabels = labels,
                tables = tables,
            ),
        )
        assertEquals(
            "Count · rows",
            calculationSummary(
                WorksheetCalculation.Aggregate("a2", "Rows", WorksheetAggregateFn.Count, null),
                columnLabels = labels,
                tables = tables,
            ),
        )
    }
}
