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
        val plainScatter =
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
                ),
            )
        assertEquals(
            listOf("amount", "Score", "Source rows"),
            plainScatter.exportResult!!.columns.map { it.name },
        )

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
                    series = VisualizationField("region"),
                    size = VisualizationField("amount"),
                ),
            )

        assertEquals(4, preview.marks.size)
        assertEquals(listOf(0), preview.marks.first().sourceRowIndices)
        assertEquals(10.0, preview.marks.first().xValue)
        assertEquals(1.0, preview.marks.first().y)
        assertEquals(10.0, preview.marks.first().size)
        val scatterExport = preview.exportResult!!
        assertEquals(
            listOf("amount", "Score", "Series", "Size", "Source rows"),
            scatterExport.columns.map { it.name },
        )
        assertEquals(ResultCell.FloatCell(10.0), scatterExport.rows.first()[3])
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
        val export = preview.exportResult!!
        assertEquals("integer", export.columns[2].dataType)
        assertEquals(
            listOf(1L, 1L, 2L).map(ResultCell::IntegerCell),
            export.rows.map { it[2] },
        )
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

    @Test
    fun groupedBarsDefaultValueSortUsesCategoryTotalAndTopNPrunesSeries() {
        val scrambled =
            sample()
                .copy(
                    rows =
                        listOf(
                            row("Europe", "Enterprise", "2026-01-01", 50, 1),
                            row("Europe", "SMB", "2026-01-01", 10, 1),
                            row("Asia", "Enterprise", "2026-01-02", 20, 1),
                            row("Asia", "SMB", "2026-01-02", 80, 1),
                            row("Americas", "Startup", "2026-01-03", 30, 1),
                        ),
                    rowCount = 5,
                )
        val preview =
            applyVisualization(
                scrambled,
                VisualizationConfig(
                    chartType = ChartType.Bar,
                    x = VisualizationField("status"),
                    values = listOf(VisualizationMeasure("revenue", MeasureFn.Sum, "amount")),
                    series = VisualizationField("region"),
                    sort = VisualizationSort(VisualizationSortTarget.Value, SortDir.Desc),
                    topN = 2,
                ),
            )

        // Asia total = 100, Europe total = 60, Americas total = 30 (pruned by topN = 2).
        assertEquals(listOf("Asia", "Europe"), preview.categories)
        assertEquals(listOf("Enterprise", "SMB"), preview.series.map { it.label })
    }

    @Test
    fun mixedFormatMultiMeasureExportUsesTextColumnAndFormattedCells() {
        val preview =
            applyVisualization(
                sample(),
                VisualizationConfig(
                    chartType = ChartType.Bar,
                    x = VisualizationField("status"),
                    values =
                        listOf(
                            VisualizationMeasure("revenue", MeasureFn.Sum, "amount", "Revenue"),
                            VisualizationMeasure(
                                "score_fmt",
                                MeasureFn.Sum,
                                "score",
                                "Score",
                                numberFormat =
                                    PivotNumberFormat(NumberFormatKind.Number, decimals = 1),
                            ),
                        ),
                ),
            )
        val export = preview.exportResult!!
        assertEquals("text", export.columns[2].dataType)
        assertTrue(export.rows.all { it[2] is ResultCell.TextCell })
    }

    @Test
    fun dualAxisPropagatesOnGroupedBarAndLineAndFallsBackOnStackedOrAllSecondary() {
        val baseConfig =
            VisualizationConfig(
                chartType = ChartType.Bar,
                x = VisualizationField("status"),
                values =
                    listOf(
                        VisualizationMeasure("revenue", MeasureFn.Sum, "amount", "Revenue"),
                        VisualizationMeasure(
                            "score",
                            MeasureFn.Sum,
                            "score",
                            "Score",
                            axis = ValueAxis.Secondary,
                        ),
                    ),
            )
        val groupedBar = applyVisualization(sample(), baseConfig)
        assertTrue(groupedBar.hasSecondaryAxis)
        assertEquals(
            mapOf("revenue" to ValueAxis.Primary, "score" to ValueAxis.Secondary),
            groupedBar.series.associate { it.key to it.axis },
        )

        val line = applyVisualization(sample(), baseConfig.copy(chartType = ChartType.Line))
        assertTrue(line.hasSecondaryAxis)

        val stackedBar =
            applyVisualization(sample(), baseConfig.copy(barArrangement = BarArrangement.Stacked))
        assertFalse(stackedBar.hasSecondaryAxis)
        assertTrue(stackedBar.marks.all { it.axis == ValueAxis.Primary })

        val allSecondary =
            applyVisualization(
                sample(),
                baseConfig.copy(
                    values = baseConfig.values.map { it.copy(axis = ValueAxis.Secondary) },
                ),
            )
        assertFalse(allSecondary.hasSecondaryAxis)
        assertTrue(allSecondary.marks.all { it.axis == ValueAxis.Primary })

        val singleMeasure =
            applyVisualization(
                sample(),
                baseConfig.copy(values = listOf(baseConfig.values[1])),
            )
        assertFalse(singleMeasure.hasSecondaryAxis)
    }

    @Test
    fun dualAxisSortsByPrimaryAxisAndWarnsOnDivergentSingleAxisScales() {
        val skewed =
            QueryResult(
                columns =
                    listOf(
                        ResultColumn("category", "varchar"),
                        ResultColumn("revenue", "decimal"),
                        ResultColumn("items", "integer"),
                    ),
                rows =
                    listOf(
                        listOf(
                            ResultCell.text("SmallRev"),
                            ResultCell.IntegerCell(100),
                            ResultCell.IntegerCell(1_000_000),
                        ),
                        listOf(
                            ResultCell.text("BigRev"),
                            ResultCell.IntegerCell(3_600_000),
                            ResultCell.IntegerCell(1),
                        ),
                    ),
                rowCount = 2,
                truncated = false,
                warnings = emptyList(),
            )
        val singleAxisConfig =
            VisualizationConfig(
                chartType = ChartType.Bar,
                x = VisualizationField("category"),
                values =
                    listOf(
                        VisualizationMeasure("rev", MeasureFn.Sum, "revenue", "Revenue"),
                        VisualizationMeasure("items", MeasureFn.Sum, "items", "Items"),
                    ),
                sort = VisualizationSort(VisualizationSortTarget.Value, SortDir.Desc),
            )

        val singleAxisPreview = applyVisualization(skewed, singleAxisConfig)
        // SmallRev has smaller primary revenue (100) than BigRev (3.6M), so BigRev is first on
        // dual axis; on single axis they sum to 1,000,100 vs 3,600,001, and with 3.6M vs 1 on
        // distinct peaks it warns.
        val skewedReportD =
            QueryResult(
                columns =
                    listOf(
                        ResultColumn("category", "varchar"),
                        ResultColumn("revenue", "decimal"),
                    ),
                rows =
                    listOf(
                        listOf(ResultCell.text("SmallRev"), ResultCell.IntegerCell(100)),
                        listOf(ResultCell.text("BigRev"), ResultCell.IntegerCell(3_600_000)),
                    ),
                rowCount = 2,
                truncated = false,
                warnings = emptyList(),
            )
        val reportDConfig =
            VisualizationConfig(
                chartType = ChartType.Bar,
                x = VisualizationField("category"),
                values =
                    listOf(
                        VisualizationMeasure("rev", MeasureFn.Sum, "revenue", "Revenue"),
                        VisualizationMeasure(
                            "cat",
                            MeasureFn.CountDistinct,
                            "category",
                            "Distinct category",
                        ),
                    ),
                sort = VisualizationSort(VisualizationSortTarget.Value, SortDir.Desc),
            )
        val singleReportD = applyVisualization(skewedReportD, reportDConfig)
        assertTrue(
            singleReportD.warnings.any { it.contains("secondary axis") },
            "Expected scale-divergence warning: ${singleReportD.warnings}",
        )

        val dualReportD =
            applyVisualization(
                skewedReportD,
                reportDConfig.copy(
                    values =
                        listOf(
                            reportDConfig.values[0],
                            reportDConfig.values[1].copy(axis = ValueAxis.Secondary),
                        ),
                ),
            )
        assertTrue(dualReportD.warnings.none { it.contains("secondary axis") })

        // Verify value sort ignores secondary axis when dual-axis is active:
        // SmallRev has Revenue=100 (primary), Items=1_000_000 (secondary);
        // BigRev has Revenue=3_600_000 (primary), Items=1 (secondary).
        // Even with Items=10_000_000 on SmallRev, BigRev stays first when sorting Desc by Value.
        val sortSample =
            QueryResult(
                columns = skewed.columns,
                rows =
                    listOf(
                        listOf(
                            ResultCell.text("SmallRev"),
                            ResultCell.IntegerCell(100),
                            ResultCell.IntegerCell(10_000_000),
                        ),
                        listOf(
                            ResultCell.text("BigRev"),
                            ResultCell.IntegerCell(3_600_000),
                            ResultCell.IntegerCell(1),
                        ),
                    ),
                rowCount = 2,
                truncated = false,
                warnings = emptyList(),
            )
        val dualSorted =
            applyVisualization(
                sortSample,
                singleAxisConfig.copy(
                    values =
                        listOf(
                            singleAxisConfig.values[0],
                            singleAxisConfig.values[1].copy(axis = ValueAxis.Secondary),
                        ),
                ),
            )
        assertEquals(listOf("BigRev", "SmallRev"), dualSorted.categories)
        assertFalse(singleAxisPreview.hasSecondaryAxis)
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
