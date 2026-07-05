package com.safedb.explore

import com.safedb.model.QueryResult
import com.safedb.model.ResultCell
import com.safedb.model.ResultColumn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApplyExploreTest {
    @Test
    fun groupsRowsByDimensionAndComputesMeasures() {
        val preview = applyExplore(
            sample = sampleResult(),
            config = ExploreConfig(
                rowDimensions = listOf(PivotDimension("t0__status")),
                measures = listOf(
                    PivotMeasure("count", MeasureFn.Count, label = "Orders"),
                    PivotMeasure("sum", MeasureFn.Sum, "t0__amount", "Revenue"),
                    PivotMeasure("avg", MeasureFn.Avg, "t0__amount", "Average"),
                ),
                showColumnTotals = false,
            ),
        )

        assertEquals(listOf("status", "Orders", "Revenue", "Average"), preview.result.columns.map { it.name })
        assertEquals(
            listOf(
                listOf(ResultCell.text("pending"), ResultCell.IntegerCell(4), ResultCell.IntegerCell(475), ResultCell.FloatCell(118.75)),
                listOf(ResultCell.text("shipped"), ResultCell.IntegerCell(2), ResultCell.IntegerCell(450), ResultCell.IntegerCell(225)),
            ),
            preview.result.rows,
        )
    }

    @Test
    fun pivotsOptionalColumnDimensionAndAddsTotals() {
        val preview = applyExplore(
            sample = sampleResult(),
            config = ExploreConfig(
                rowDimensions = listOf(PivotDimension("t0__region")),
                columnDimension = PivotDimension("t0__status"),
                measures = listOf(PivotMeasure("sum", MeasureFn.Sum, "t0__amount", "Revenue")),
                showRowTotals = true,
                showColumnTotals = true,
            ),
        )

        assertEquals(
            listOf("region", "pending Revenue", "shipped Revenue", "Total Revenue"),
            preview.result.columns.map { it.name },
        )
        assertEquals(
            listOf(
                listOf(ResultCell.text("East"), ResultCell.IntegerCell(125), ResultCell.IntegerCell(250), ResultCell.IntegerCell(375)),
                listOf(ResultCell.text("West"), ResultCell.IntegerCell(300), ResultCell.IntegerCell(200), ResultCell.IntegerCell(500)),
                listOf(ResultCell.text("(blank)"), ResultCell.IntegerCell(50), ResultCell.IntegerCell(0), ResultCell.IntegerCell(50)),
                listOf(ResultCell.text("Total"), ResultCell.IntegerCell(475), ResultCell.IntegerCell(450), ResultCell.IntegerCell(925)),
            ),
            preview.result.rows,
        )
    }

    @Test
    fun supportsCountDistinctMinMaxAndNullBuckets() {
        val preview = applyExplore(
            sample = sampleResult(),
            config = ExploreConfig(
                rowDimensions = listOf(PivotDimension("t0__region")),
                measures = listOf(
                    PivotMeasure("customers", MeasureFn.CountDistinct, "t0__customer", "Customers"),
                    PivotMeasure("min", MeasureFn.Min, "t0__amount", "Min amount"),
                    PivotMeasure("max", MeasureFn.Max, "t0__amount", "Max amount"),
                ),
                nullBucketLabel = "No region",
                showColumnTotals = false,
            ),
        )

        assertEquals(
            listOf(
                listOf(ResultCell.text("East"), ResultCell.IntegerCell(2), ResultCell.IntegerCell(25), ResultCell.IntegerCell(250)),
                listOf(ResultCell.text("West"), ResultCell.IntegerCell(2), ResultCell.IntegerCell(200), ResultCell.IntegerCell(300)),
                listOf(ResultCell.text("No region"), ResultCell.IntegerCell(1), ResultCell.IntegerCell(50), ResultCell.IntegerCell(50)),
            ),
            preview.result.rows,
        )
    }

    @Test
    fun countDistinctIgnoresNullSourceValues() {
        val sample = QueryResult(
            columns = listOf(ResultColumn("category", "varchar"), ResultColumn("code", "varchar")),
            rows = listOf(
                listOf(ResultCell.text("A"), ResultCell.text("x")),
                listOf(ResultCell.text("A"), ResultCell.text("x")),
                listOf(ResultCell.text("A"), ResultCell.Null),
                listOf(ResultCell.text("A"), ResultCell.Null),
            ),
            rowCount = 4,
            truncated = false,
            warnings = emptyList(),
        )

        val preview = applyExplore(
            sample = sample,
            config = ExploreConfig(
                rowDimensions = listOf(PivotDimension("category")),
                measures = listOf(PivotMeasure("codes", MeasureFn.CountDistinct, "code", "Codes")),
                showColumnTotals = false,
            ),
        )

        assertEquals(ResultCell.IntegerCell(1), preview.result.rows.single()[1])
    }

    @Test
    fun sortsByMeasureDescending() {
        val preview = applyExplore(
            sample = sampleResult(),
            config = ExploreConfig(
                rowDimensions = listOf(PivotDimension("t0__region")),
                measures = listOf(PivotMeasure("sum", MeasureFn.Sum, "t0__amount", "Revenue")),
                showColumnTotals = false,
                sort = ExploreSort(ExploreSortTarget.Measure("sum"), SortDir.Desc),
            ),
        )

        assertEquals(listOf("West", "East", "(blank)"), preview.result.rows.map { (it.first() as ResultCell.TextCell).value.text })
    }

    @Test
    fun warnsWhenNumericMeasureSkipsText() {
        val sample = QueryResult(
            columns = listOf(ResultColumn("category", "varchar"), ResultColumn("amount", "varchar")),
            rows = listOf(
                listOf(ResultCell.text("A"), ResultCell.text("10.5")),
                listOf(ResultCell.text("A"), ResultCell.text("not-a-number")),
            ),
            rowCount = 2,
            truncated = false,
            warnings = emptyList(),
        )

        val preview = applyExplore(
            sample = sample,
            config = ExploreConfig(
                rowDimensions = listOf(PivotDimension("category")),
                measures = listOf(PivotMeasure("sum", MeasureFn.Sum, "amount", "Revenue")),
            ),
        )

        assertEquals(ResultCell.FloatCell(10.5), preview.result.rows.first()[1])
        assertTrue(preview.warnings.any { it.contains("skipped 1 non-numeric cell") })
    }

    @Test
    fun emptySamplesStillExposeConfiguredColumns() {
        val preview = applyExplore(
            sample = QueryResult(
                columns = listOf(ResultColumn("t0__status", "varchar"), ResultColumn("t0__amount", "bigint")),
                rows = emptyList(),
                rowCount = 0,
                truncated = false,
                warnings = emptyList(),
            ),
            config = ExploreConfig(
                rowDimensions = listOf(PivotDimension("t0__status")),
                measures = listOf(PivotMeasure("sum", MeasureFn.Sum, "t0__amount", "Revenue")),
            ),
        )

        assertEquals(listOf("status", "Revenue"), preview.result.columns.map { it.name })
        assertTrue(preview.result.rows.isEmpty())
    }

    private fun sampleResult(): QueryResult = QueryResult(
        columns = listOf(
            ResultColumn("t0__region", "varchar"),
            ResultColumn("t0__status", "varchar"),
            ResultColumn("t0__customer", "varchar"),
            ResultColumn("t0__amount", "bigint"),
        ),
        rows = listOf(
            listOf(ResultCell.text("East"), ResultCell.text("pending"), ResultCell.text("Ada"), ResultCell.IntegerCell(100)),
            listOf(ResultCell.text("East"), ResultCell.text("pending"), ResultCell.text("Ada"), ResultCell.IntegerCell(25)),
            listOf(ResultCell.text("East"), ResultCell.text("shipped"), ResultCell.text("Lin"), ResultCell.IntegerCell(250)),
            listOf(ResultCell.text("West"), ResultCell.text("pending"), ResultCell.text("Max"), ResultCell.IntegerCell(300)),
            listOf(ResultCell.text("West"), ResultCell.text("shipped"), ResultCell.text("Nia"), ResultCell.IntegerCell(200)),
            listOf(ResultCell.Null, ResultCell.text("pending"), ResultCell.text("Noor"), ResultCell.IntegerCell(50)),
        ),
        rowCount = 6,
        truncated = false,
        warnings = emptyList(),
    )
}
