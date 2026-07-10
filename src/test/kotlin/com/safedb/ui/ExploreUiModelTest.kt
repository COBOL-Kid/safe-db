package com.safedb.ui

import com.safedb.explore.ExploreColumnGroup
import com.safedb.explore.ExploreConfig
import com.safedb.explore.ExplorePivotLayout
import com.safedb.explore.ExploreSortTarget
import com.safedb.explore.MeasureFn
import com.safedb.explore.PivotDimension
import com.safedb.explore.PivotMeasure
import com.safedb.explore.SortDir
import com.safedb.model.QueryResult
import com.safedb.model.ResultColumn
import com.safedb.model.TableRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExploreUiModelTest {
    @Test
    fun fieldOptionsQualifyOnlyDuplicateJoinedLabels() {
        val options = buildExploreFieldOptions(
            sample = QueryResult(
                columns = listOf(
                    ResultColumn("t0__id", "bigint"),
                    ResultColumn("t0__status", "varchar"),
                    ResultColumn("t1__status", "varchar"),
                ),
                rows = emptyList(),
                rowCount = 0,
                truncated = false,
                warnings = emptyList(),
            ),
            tables = listOf(
                TableRef("public", "orders", "t0"),
                TableRef("public", "shipments", "t1"),
            ),
        )

        assertEquals(listOf("id", "orders.status", "shipments.status"), options.map { it.label })
    }

    @Test
    fun valueFunctionsFollowFieldType() {
        val numeric = buildField("amount", "decimal")
        val text = buildField("status", "varchar")

        assertEquals(
            listOf(
                MeasureFn.Sum,
                MeasureFn.Avg,
                MeasureFn.Min,
                MeasureFn.Max,
                MeasureFn.CountNumbers,
                MeasureFn.CountDistinct,
                MeasureFn.Product,
                MeasureFn.StdDev,
                MeasureFn.StdDevPopulation,
                MeasureFn.Variance,
                MeasureFn.VariancePopulation,
            ),
            availableMeasureFunctions(numeric),
        )
        assertEquals(
            listOf(MeasureFn.CountDistinct, MeasureFn.Min, MeasureFn.Max),
            availableMeasureFunctions(text),
        )
        assertEquals("Average amount", measureFor(numeric, MeasureFn.Avg).label)
    }

    @Test
    fun clickingSameHeaderTogglesDirectionAndMeasuresStartDescending() {
        val target = ExploreSortTarget.Measure("revenue")
        val first = toggleExploreSort(ExploreConfig(), target)
        val second = toggleExploreSort(first, target)

        assertEquals(SortDir.Desc, first.sort?.dir)
        assertEquals(SortDir.Asc, second.sort?.dir)
    }

    @Test
    fun dimensionsCanBeReorderedWithoutChangingMembers() {
        val region = PivotDimension("region")
        val status = PivotDimension("status")

        assertEquals(listOf(status, region), moveDimension(listOf(region, status), status, -1))
        assertEquals(listOf(region, status), moveDimension(listOf(region, status), region, -1))
    }

    @Test
    fun groupedHeadersMapRepeatedMeasureColumnsBackToSortTargets() {
        val revenue = PivotMeasure("revenue", MeasureFn.Sum, "amount", "Revenue")
        val layout = ExplorePivotLayout(
            rowDimensions = listOf(PivotDimension("region", "Region")),
            columnDimension = PivotDimension("status", "Status"),
            measures = listOf(revenue),
            columnGroups = listOf(
                ExploreColumnGroup("pending", 1, listOf("revenue")),
                ExploreColumnGroup("shipped", 2, listOf("revenue")),
                ExploreColumnGroup("Total", 3, listOf("revenue"), isTotal = true),
            ),
            hasGrandTotalRow = true,
        )

        assertEquals(ExploreSortTarget.Dimension("region"), pivotSortTarget(layout, 0))
        assertEquals(ExploreSortTarget.Measure("revenue"), pivotSortTarget(layout, 2))
        assertEquals("Revenue", pivotLeafLabel(layout, 3, "Total Revenue"))
        assertTrue(layout.hasGrandTotalRow)
        assertFalse(layout.columnGroups.first().isTotal)
    }

    private fun buildField(name: String, type: String): ExploreFieldOption =
        buildExploreFieldOptions(
            QueryResult(
                columns = listOf(ResultColumn(name, type)),
                rows = emptyList(),
                rowCount = 0,
                truncated = false,
                warnings = emptyList(),
            ),
            tables = emptyList(),
        ).single()
}
