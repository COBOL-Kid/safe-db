package com.safedb.explore

import com.safedb.model.QueryResult
import com.safedb.model.ResultCell
import com.safedb.model.ResultColumn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExploreAggregateTest {
    @Test
    fun pivotAndChartAgreeOnEveryMeasureFunction() {
        MeasureFn.entries.forEach { fn ->
            val pivot =
                applyExplore(
                    sample(),
                    ExploreConfig(
                        rowDimensions = listOf(PivotDimension("status")),
                        measures = listOf(PivotMeasure("m", fn, "amount", "Amount")),
                        showColumnTotals = false,
                    ),
                )
            val chart =
                applyVisualization(
                    sample(),
                    VisualizationConfig(
                        chartType = ChartType.Bar,
                        x = VisualizationField("status"),
                        values = listOf(VisualizationMeasure("m", fn, "amount", "Amount")),
                    ),
                )

            val pivotValue = resultCellDecimal(pivot.result.rows.single()[1])?.toDouble()
            assertEquals(pivotValue, chart.marks.single().y, "$fn disagreed")
        }
    }

    @Test
    fun dateAndTextMinAggregateForThePivotButNeverPlot() {
        assertEquals(
            ResultCell.text("east"),
            aggregateMeasure(
                listOf(ResultCell.text("west"), ResultCell.text("east")),
                MeasureFn.Min,
            ),
        )

        val pivot =
            applyExplore(
                sample(),
                ExploreConfig(
                    rowDimensions = listOf(PivotDimension("status")),
                    measures = listOf(PivotMeasure("m", MeasureFn.Min, "created_at", "Earliest")),
                    showColumnTotals = false,
                ),
            )
        assertEquals(ResultCell.text("2026-01-05"), pivot.result.rows.single()[1])

        val chart =
            applyVisualization(
                sample(),
                VisualizationConfig(
                    chartType = ChartType.Bar,
                    x = VisualizationField("status"),
                    values =
                        listOf(VisualizationMeasure("m", MeasureFn.Min, "created_at", "Earliest")),
                ),
            )
        assertTrue(chart.marks.isEmpty())
        assertEquals("No plottable values were found.", chart.blockingMessage)
    }

    @Test
    fun averageRoundsToEightDecimalsOnBothSurfaces() {
        val pivot =
            applyExplore(
                sample(),
                ExploreConfig(
                    rowDimensions = listOf(PivotDimension("status")),
                    measures = listOf(PivotMeasure("m", MeasureFn.Avg, "amount", "Average")),
                    showColumnTotals = false,
                ),
            )
        assertEquals(ResultCell.FloatCell(23.33333333), pivot.result.rows.single()[1])

        val chart =
            applyVisualization(
                sample(),
                VisualizationConfig(
                    chartType = ChartType.Bar,
                    x = VisualizationField("status"),
                    values = listOf(VisualizationMeasure("m", MeasureFn.Avg, "amount", "Average")),
                ),
            )
        assertEquals(23.33333333, chart.marks.single().y)
        assertEquals("23.33333333", chart.marks.single().formattedY)
    }

    private fun sample() =
        QueryResult(
            columns =
                listOf(
                    ResultColumn("status", "varchar"),
                    ResultColumn("created_at", "date"),
                    ResultColumn("amount", "bigint"),
                ),
            rows =
                listOf(
                    row("pending", "2026-01-05", 10),
                    row("pending", "2026-02-01", 20),
                    row("pending", "2026-03-09", 40),
                ),
            rowCount = 3,
            truncated = false,
            warnings = emptyList(),
        )

    private fun row(status: String, createdAt: String, amount: Long) =
        listOf(ResultCell.text(status), ResultCell.text(createdAt), ResultCell.IntegerCell(amount))
}
