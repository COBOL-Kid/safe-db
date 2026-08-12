package com.safedb.explore

import com.safedb.model.QueryResult
import com.safedb.model.ResultCell
import com.safedb.model.ResultColumn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExploreGroupingTest {
    @Test
    fun everyDateUnitBucketsIdenticallyInAllThreeEngines() {
        val sample = sample("2026-01-15", "2026-05-20", "2027-01-15 09:30:00")
        DateGroupUnit.entries.forEach { unit ->
            val grouping = PivotGrouping.Date(unit)
            val pivot = pivotBuckets(sample, "day", grouping)
            val worksheet = worksheetBuckets(sample, "day", grouping)
            val chart = chartBuckets(sample, "day", grouping)

            assertEquals(pivot, chart, "$unit keys and labels disagreed between pivot and chart")
            assertEquals(
                pivot.map { it.second },
                worksheet.map { it.second },
                "$unit labels disagreed between pivot and worksheet",
            )
            if (unit != DateGroupUnit.IsoWeek) {
                assertEquals(
                    pivot.map { it.first },
                    worksheet.map { it.first },
                    "$unit keys disagreed between pivot and worksheet",
                )
            }
        }
    }

    @Test
    fun isoWeekKeysKeepThePerEngineShapeSavedInCollapsePaths() {
        val sample = sample("2026-01-15")
        val grouping = PivotGrouping.Date(DateGroupUnit.IsoWeek)

        assertEquals(listOf("2026-W3" to "2026-W03"), pivotBuckets(sample, "day", grouping))
        assertEquals(listOf("2026-W3" to "2026-W03"), chartBuckets(sample, "day", grouping))
        assertEquals(listOf("2026-W03" to "2026-W03"), worksheetBuckets(sample, "day", grouping))
    }

    @Test
    fun whitespacePaddedDatesGroupInsteadOfFallingBackToInvalidDate() {
        val sample = sample(" 2026-01-15 ", "2026-01-20\t")
        val grouping = PivotGrouping.Date(DateGroupUnit.Month)
        val expected = listOf("2026-01" to "Jan 2026")

        assertEquals(expected, pivotBuckets(sample, "day", grouping))
        assertEquals(expected, worksheetBuckets(sample, "day", grouping))
        assertEquals(expected, chartBuckets(sample, "day", grouping))
        listOf(
                pivotPreview(sample, "day", grouping).warnings,
                worksheetPreview(sample, "day", grouping).warnings,
                chartPreview(sample, "day", grouping).warnings,
            )
            .forEach { warnings ->
                assertTrue(warnings.none { it.contains("grouped as dates") }, "$warnings")
            }
    }

    @Test
    fun unparseableDatesStillFallBackToOneInvalidBucketWithAWarning() {
        val sample = sample("not a date")
        val grouping = PivotGrouping.Date(DateGroupUnit.Day)
        val expected = listOf("<invalid-date>" to "(invalid date)")

        assertEquals(expected, pivotBuckets(sample, "day", grouping))
        assertEquals(expected, worksheetBuckets(sample, "day", grouping))
        assertEquals(expected, chartBuckets(sample, "day", grouping))
        listOf(
                pivotPreview(sample, "day", grouping).warnings,
                worksheetPreview(sample, "day", grouping).warnings,
                chartPreview(sample, "day", grouping).warnings,
            )
            .forEach { warnings ->
                assertEquals(
                    listOf("Field contains values that could not be grouped as dates"),
                    warnings,
                )
            }
    }

    @Test
    fun numberBinsBucketIdenticallyInAllThreeEngines() {
        val sample = sample("2026-01-15", "2026-01-16", "2026-01-17")
        val grouping = PivotGrouping.NumberBin("15", start = "5")
        val expected = listOf("20:15" to "20 – 35", "5:15" to "5 – 20")

        assertEquals(expected, pivotBuckets(sample, "amount", grouping))
        assertEquals(expected, worksheetBuckets(sample, "amount", grouping))
        assertEquals(expected, chartBuckets(sample, "amount", grouping))
    }

    private fun pivotPreview(sample: QueryResult, column: String, grouping: PivotGrouping) =
        applyExplore(
            sample,
            ExploreConfig(
                rowDimensions =
                    listOf(PivotDimension(column, "Field", id = "field", grouping = grouping)),
                showColumnTotals = false,
            ),
        )

    private fun worksheetPreview(sample: QueryResult, column: String, grouping: PivotGrouping) =
        applyWorksheet(
            sample,
            WorksheetConfig(groups = listOf(WorksheetGroup("g", column, "Field", grouping))),
        )

    private fun chartPreview(sample: QueryResult, column: String, grouping: PivotGrouping) =
        applyVisualization(
            sample,
            VisualizationConfig(
                chartType = ChartType.Bar,
                x = VisualizationField(column, "Field", grouping),
                values = listOf(VisualizationMeasure.countRows()),
            ),
        )

    private fun pivotBuckets(sample: QueryResult, column: String, grouping: PivotGrouping) =
        pivotPreview(sample, column, grouping)
            .layout
            .rowEntries
            .map { it.pathKey to it.label }
            .sortedBy { it.second }

    private fun worksheetBuckets(sample: QueryResult, column: String, grouping: PivotGrouping) =
        worksheetPreview(sample, column, grouping)
            .rows
            .filter { it.kind == WorksheetRowKind.Group }
            .map { it.pathKey to it.label.orEmpty().removePrefix("Field: ") }
            .sortedBy { it.second }

    private fun chartBuckets(sample: QueryResult, column: String, grouping: PivotGrouping) =
        chartPreview(sample, column, grouping)
            .marks
            .map { it.xKey to it.xLabel }
            .sortedBy { it.second }

    private fun sample(vararg days: String) =
        QueryResult(
            columns = listOf(ResultColumn("day", "date"), ResultColumn("amount", "decimal")),
            rows =
                days.mapIndexed { index, day ->
                    listOf(ResultCell.text(day), ResultCell.IntegerCell((index + 1) * 10L))
                },
            rowCount = days.size,
            truncated = false,
            warnings = emptyList(),
        )
}
