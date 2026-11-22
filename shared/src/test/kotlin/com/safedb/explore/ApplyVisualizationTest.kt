package com.safedb.explore

import com.safedb.model.QueryResult
import com.safedb.model.ResultCell
import com.safedb.model.ResultColumn
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApplyVisualizationTest {
    @Test
    fun autoSelectsLineAndAggregatesWithLineage() {
        val preview =
            applyVisualization(
                sample(),
                VisualizationConfig(
                    x =
                        VisualizationField(
                            "created_at",
                            grouping = PivotGrouping.Date(DateGroupUnit.Month),
                        ),
                    values =
                        listOf(VisualizationMeasure("revenue", MeasureFn.Sum, "amount", "Revenue")),
                    sort = VisualizationSort(VisualizationSortTarget.Source, SortDir.Asc),
                ),
            )

        assertEquals(ChartType.Line, preview.chartType)
        assertEquals(listOf("Jan 2026", "Feb 2026"), preview.marks.map { it.xLabel })
        assertEquals(listOf(30.0, 60.0), preview.marks.map { it.y })
        assertEquals(listOf(0, 1), preview.marks.first().sourceRowIndices)
        assertTrue(preview.ready)
    }

    @Test
    fun barSupportsSeriesTopNAndMemberFilters() {
        val preview =
            applyVisualization(
                sample(),
                VisualizationConfig(
                    chartType = ChartType.Bar,
                    x = VisualizationField("status"),
                    values =
                        listOf(VisualizationMeasure("revenue", MeasureFn.Sum, "amount", "Revenue")),
                    series = VisualizationField("region"),
                    filters =
                        listOf(
                            PivotFilter.Members(
                                "region",
                                "region",
                                "Region",
                                setOf("West"),
                            ),
                        ),
                    topN = 10,
                ),
            )

        assertEquals(setOf("pending", "shipped"), preview.marks.map { it.xLabel }.toSet())
        assertTrue(preview.marks.all { it.seriesLabel == "West" })
        assertEquals(2, preview.marks.sumOf { it.sourceRowIndices.size })
    }

    @Test
    fun scatterUsesRawRowsAndOptionalSize() {
        val preview =
            applyVisualization(
                sample(),
                VisualizationConfig(
                    chartType = ChartType.Scatter,
                    x = VisualizationField("amount"),
                    values =
                        listOf(
                            VisualizationMeasure(
                                "score",
                                MeasureFn.Sum,
                                "score",
                                "Score",
                                aggregate = false,
                            ),
                        ),
                    size = VisualizationField("amount"),
                ),
            )

        assertEquals(4, preview.marks.size)
        assertEquals(listOf(0), preview.marks.first().sourceRowIndices)
        assertEquals(10.0, preview.marks.first().xValue)
        assertEquals(1.0, preview.marks.first().y)
        assertEquals(10.0, preview.marks.first().size)
    }

    @Test
    fun histogramCreatesBinsAndPreservesRows() {
        val preview =
            applyVisualization(
                sample(),
                VisualizationConfig(
                    chartType = ChartType.Histogram,
                    x = VisualizationField("amount", grouping = PivotGrouping.NumberBin("10")),
                ),
            )

        assertEquals(listOf(10.0, 20.0, 30.0), preview.marks.map { it.xValue })
        assertEquals(listOf(1.0, 1.0, 2.0), preview.marks.map { it.y })
        assertEquals(4, preview.marks.sumOf { it.sourceRowIndices.size })
    }

    @Test
    fun kpiFormatsValueAndExportsTransformedRow() {
        val preview =
            applyVisualization(
                sample(),
                VisualizationConfig(
                    chartType = ChartType.Kpi,
                    values =
                        listOf(
                            VisualizationMeasure(
                                "revenue",
                                MeasureFn.Sum,
                                "amount",
                                "Revenue",
                                numberFormat =
                                    PivotNumberFormat(NumberFormatKind.Number, decimals = 0),
                            ),
                        ),
                ),
            )

        assertEquals(90.0, preview.marks.single().y)
        assertEquals("90", preview.marks.single().formattedY)
        assertEquals(1, preview.exportResult?.rowCount)
        assertEquals(4, preview.marks.single().sourceRowIndices.size)
    }

    @Test
    fun incompleteEmptyAndInvalidConfigurationsStaySafe() {
        val empty = applyVisualization(sample(), VisualizationConfig())
        assertFalse(empty.ready)
        assertEquals("Choose a template or add fields to build a chart.", empty.blockingMessage)
        assertNull(empty.chartType)

        val incomplete =
            applyVisualization(
                sample(),
                VisualizationConfig(
                    chartType = ChartType.Scatter,
                    x = VisualizationField("status"),
                ),
            )
        assertFalse(incomplete.ready)
        assertTrue(incomplete.blockingMessage.orEmpty().contains("numeric X"))

        val noRows =
            applyVisualization(
                sample().copy(rows = emptyList(), rowCount = 0),
                VisualizationConfig(
                    chartType = ChartType.Kpi,
                    values = listOf(VisualizationMeasure.countRows()),
                ),
            )
        assertEquals("No rows to plot.", noRows.blockingMessage)
    }

    @Test
    fun multipleValuesAndSeriesAreRejectedClearly() {
        val preview =
            applyVisualization(
                sample(),
                VisualizationConfig(
                    chartType = ChartType.Bar,
                    x = VisualizationField("status"),
                    values =
                        listOf(
                            VisualizationMeasure("amount", MeasureFn.Sum, "amount"),
                            VisualizationMeasure.countRows(),
                        ),
                    series = VisualizationField("region"),
                ),
            )

        assertEquals(
            "Use either multiple values or a Series field, not both.",
            preview.blockingMessage,
        )
    }

    @Test
    fun querySampleWarningsAreNotCopiedIntoChartPreview() {
        val preview =
            applyVisualization(
                sample()
                    .copy(warnings = listOf("No columns selected — query will select all columns")),
                VisualizationConfig(
                    chartType = ChartType.Bar,
                    x = VisualizationField("status"),
                    values = listOf(VisualizationMeasure.countRows()),
                ),
            )

        assertEquals(emptyList(), preview.warnings)
    }

    @Test
    fun labelFiltersAndMissingColumnsFilterMarksConsistently() {
        val labelPreview =
            applyVisualization(
                sample(),
                VisualizationConfig(
                    chartType = ChartType.Bar,
                    x = VisualizationField("status"),
                    values = listOf(VisualizationMeasure("revenue", MeasureFn.Sum, "amount")),
                    filters =
                        listOf(
                            PivotFilter.Label(
                                "f-region",
                                "region",
                                "Region",
                                LabelFilterOp.Equals,
                                "west",
                            ),
                            PivotFilter.Label(
                                "f-status",
                                "status",
                                "Status",
                                LabelFilterOp.Contains,
                                "HIP",
                            ),
                        ),
                ),
            )

        assertEquals(listOf("shipped"), labelPreview.marks.map { it.xLabel })
        assertEquals(listOf(30.0), labelPreview.marks.map { it.y })

        val missingColumn =
            applyVisualization(
                sample(),
                VisualizationConfig(
                    chartType = ChartType.Bar,
                    x = VisualizationField("status"),
                    values = listOf(VisualizationMeasure.countRows()),
                    filters =
                        listOf(
                            PivotFilter.Members("f-missing", "missing", "Missing", setOf("x")),
                        ),
                ),
            )
        assertEquals("No rows to plot.", missingColumn.blockingMessage)
    }

    @Test
    fun autoFormatsNumbersWithGroupingAndOmitsDuplicateSeriesColumn() {
        val large =
            sample()
                .copy(
                    rows =
                        listOf(
                            row("pending", "West", "2026-01-01", 3_624_326, 1),
                            row("shipped", "East", "2026-02-02", 1_620_951, 2),
                        ),
                    rowCount = 2,
                )
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.US)
        try {
            val preview =
                applyVisualization(
                    large,
                    VisualizationConfig(
                        chartType = ChartType.Bar,
                        x = VisualizationField("status"),
                        values =
                            listOf(
                                VisualizationMeasure(
                                    "revenue",
                                    MeasureFn.Sum,
                                    "amount",
                                    "Sum amount",
                                ),
                            ),
                    ),
                )

            assertEquals(listOf("3,624,326", "1,620,951"), preview.marks.map { it.formattedY })
            val export = preview.exportResult!!
            assertEquals(
                listOf("status", "Measure", "Value", "Source rows"),
                export.columns.map { it.name },
            )
            assertEquals(ResultCell.FloatCell(3_624_326.0), export.rows.first()[2])
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun groupedBarsSortSeriesConsistentlyAcrossCategoriesAndKeepSeriesInExport() {
        val scrambled =
            sample()
                .copy(
                    rows =
                        listOf(
                            row("Europe", "SMB", "2026-01-01", 10, 1),
                            row("Europe", "Enterprise", "2026-01-01", 30, 1),
                            row("Asia", "Enterprise", "2026-01-02", 40, 1),
                            row("Asia", "Mid-Market", "2026-01-02", 20, 1),
                            row("Asia", "SMB", "2026-01-02", 50, 1),
                            row("Europe", "Mid-Market", "2026-01-01", 25, 1),
                        ),
                    rowCount = 6,
                )
        val preview =
            applyVisualization(
                scrambled,
                VisualizationConfig(
                    chartType = ChartType.Bar,
                    x = VisualizationField("status"),
                    values = listOf(VisualizationMeasure("revenue", MeasureFn.Sum, "amount")),
                    series = VisualizationField("region"),
                    sort = VisualizationSort(VisualizationSortTarget.Category, SortDir.Asc),
                ),
            )

        assertEquals(listOf("Enterprise", "Mid-Market", "SMB"), preview.series.map { it.label })
        assertEquals(
            listOf("Enterprise", "Mid-Market", "SMB", "Enterprise", "Mid-Market", "SMB"),
            preview.marks.map { it.seriesLabel },
        )
        assertEquals(
            listOf("status", "Series", "Measure", "Value", "Source rows"),
            preview.exportResult!!.columns.map { it.name },
        )
    }

    private fun sample() =
        QueryResult(
            columns =
                listOf(
                    ResultColumn("status", "varchar"),
                    ResultColumn("region", "varchar"),
                    ResultColumn("created_at", "datetime"),
                    ResultColumn("amount", "decimal"),
                    ResultColumn("score", "decimal"),
                ),
            rows =
                listOf(
                    row("pending", "West", "2026-01-01", 10, 1),
                    row("pending", "East", "2026-01-12", 20, 4),
                    row("shipped", "West", "2026-02-02", 30, 9),
                    row("shipped", "East", "2026-02-18", 30, 16),
                ),
            rowCount = 4,
            truncated = false,
            warnings = emptyList(),
        )

    private fun row(status: String, region: String, date: String, amount: Long, score: Long) =
        listOf(
            ResultCell.text(status),
            ResultCell.text(region),
            ResultCell.text(date),
            ResultCell.IntegerCell(amount),
            ResultCell.IntegerCell(score),
        )
}
