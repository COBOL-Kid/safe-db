package com.safedb.ui

import com.safedb.explore.ChartType
import com.safedb.explore.ExploreColumnGroup
import com.safedb.explore.ExploreConfig
import com.safedb.explore.ExplorePivotLayout
import com.safedb.explore.ExploreSortTarget
import com.safedb.explore.MeasureFn
import com.safedb.explore.PivotDimension
import com.safedb.explore.PivotFilter
import com.safedb.explore.PivotMeasure
import com.safedb.explore.PivotShowAs
import com.safedb.explore.ShowAsMode
import com.safedb.explore.SortDir
import com.safedb.explore.ValueAxis
import com.safedb.explore.VisualizationConfig
import com.safedb.explore.VisualizationField
import com.safedb.explore.VisualizationMeasure
import com.safedb.model.QueryResult
import com.safedb.model.ResultColumn
import com.safedb.model.TableRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExploreUiModelTest {
    @Test
    fun truncationExplanationIsSelectedOnlyForTruncatedSamples() {
        assertEquals(
            "You’re viewing a sample, so totals may not represent the full result.",
            exploreTruncationExplanation(truncated = true),
        )
        assertNull(exploreTruncationExplanation(truncated = false))
    }

    @Test
    fun fieldOptionsQualifyOnlyDuplicateJoinedLabels() {
        val options =
            buildExploreFieldOptions(
                sample =
                    QueryResult(
                        columns =
                            listOf(
                                ResultColumn("t0__id", "bigint"),
                                ResultColumn("t0__status", "varchar"),
                                ResultColumn("t1__status", "varchar"),
                            ),
                        rows = emptyList(),
                        rowCount = 0,
                        truncated = false,
                        warnings = emptyList(),
                    ),
                tables =
                    listOf(
                        TableRef("public", "orders", "t0"),
                        TableRef("public", "shipments", "t1"),
                    ),
            )

        assertEquals(listOf("id", "orders.status", "shipments.status"), options.map { it.label })
        assertEquals(listOf("orders", "orders", "shipments"), options.map { it.sourceTableLabel })
        assertEquals(
            listOf("orders · bigint", "orders · varchar", "shipments · varchar"),
            options.map { it.supportingText() },
        )
    }

    @Test
    fun fieldOptionsDisambiguateRepeatedTableNamesWithSchema() {
        val options =
            buildExploreFieldOptions(
                sample =
                    QueryResult(
                        columns =
                            listOf(
                                ResultColumn("t0__occurred_at", "timestamp"),
                                ResultColumn("t1__occurred_at", "timestamp"),
                                ResultColumn("t2__status", "varchar"),
                            ),
                        rows = emptyList(),
                        rowCount = 0,
                        truncated = false,
                        warnings = emptyList(),
                    ),
                tables =
                    listOf(
                        TableRef("analytics", "events", "t0"),
                        TableRef("archive", "events", "t1"),
                        TableRef("public", "orders", "t2"),
                    ),
            )

        assertEquals(
            listOf("analytics.events", "archive.events", "orders"),
            options.map { it.sourceTableLabel },
        )
        assertTrue(options[0].matchesSearch("analytics"))
        assertTrue(options[1].matchesSearch("archive.events"))
        assertTrue(options[2].matchesSearch("orders"))
    }

    @Test
    fun unmappedFieldsRemainTypeOnlyAndAreGroupedSeparately() {
        val options =
            buildExploreFieldOptions(
                sample =
                    QueryResult(
                        columns =
                            listOf(
                                ResultColumn("t0__status", "varchar"),
                                ResultColumn("expression_total", "decimal"),
                            ),
                        rows = emptyList(),
                        rowCount = 0,
                        truncated = false,
                        warnings = emptyList(),
                    ),
                tables = listOf(TableRef("public", "orders", "t0")),
            )

        assertEquals("decimal", options[1].supportingText())
        assertEquals(listOf("orders", "Other fields"), groupExploreFields(options).map { it.label })
        assertTrue(options[1].matchesSearch("expression"))
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
        assertEquals(
            availableMeasureFunctions(numeric),
            availablePlottableMeasureFunctions(numeric),
        )
        assertEquals(listOf(MeasureFn.CountDistinct), availablePlottableMeasureFunctions(text))
        assertEquals(
            listOf(MeasureFn.CountDistinct),
            availablePlottableMeasureFunctions(buildField("created_at", "date")),
        )
        assertEquals("Avg amount", measureFor(numeric, MeasureFn.Avg).label)
        assertEquals("StdDevP amount", measureFor(numeric, MeasureFn.StdDevPopulation).label)
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
        val layout =
            ExplorePivotLayout(
                rowDimensions = listOf(PivotDimension("region", "Region")),
                columnDimension = PivotDimension("status", "Status"),
                measures = listOf(revenue),
                columnGroups =
                    listOf(
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

    @Test
    fun runningTotalHeadersKeepTheirLabelButOfferNoSort() {
        val running =
            PivotMeasure(
                "revenue",
                MeasureFn.Sum,
                "amount",
                "Revenue",
                showAs = PivotShowAs(ShowAsMode.RunningTotal),
            )
        val layout =
            ExplorePivotLayout(
                rowDimensions = listOf(PivotDimension("region", "Region")),
                columnDimension = null,
                measures = listOf(running),
                columnGroups = emptyList(),
                hasGrandTotalRow = false,
            )

        assertEquals(ExploreSortTarget.Dimension("region"), pivotSortTarget(layout, 0))
        assertNull(pivotSortTarget(layout, 1))
        assertEquals("Revenue", pivotLeafLabel(layout, 1, "Running revenue"))
        assertEquals("Region", pivotLeafLabel(layout, 0, "Region"))
    }

    @Test
    fun visualizationExportStemUsesTitleFallsBackCleanlyAndAvoidsDoubleExplorePrefix() {
        assertEquals(
            "sum-line-total-by-category",
            visualizationExportStem("Sum line_total by category", "ShopDB"),
        )
        assertEquals(
            "sum-line-total-by-category-data",
            visualizationExportStem("Sum line_total by category", "ShopDB", dataExport = true),
        )
        assertEquals(
            "shopdb-chart",
            visualizationExportStem("   ", "ShopDB"),
        )
        assertEquals(
            "shopdb-chart-data",
            visualizationExportStem("!!!", "ShopDB", dataExport = true),
        )
        val longTitle = "word-".repeat(20)
        val capped = visualizationExportStem(longTitle, "ShopDB")
        assertTrue(capped.length <= 64)
        assertFalse(capped.endsWith("-"))

        assertEquals(
            "explore-sum-line-total-by-category.png",
            exploreExportFileName(
                visualizationExportStem("Sum line_total by category", "ShopDB"),
                "png",
            ),
        )
        assertEquals(
            "explore-shopdb-details.csv",
            exploreExportFileName("ShopDB-details", "csv"),
        )
    }

    @Test
    fun forChartTypeRetainsQualifiedNumericMeasureForScatterAndRestoresAggregatedLabel() {
        val sample =
            QueryResult(
                columns =
                    listOf(
                        ResultColumn("t0__quantity", "integer"),
                        ResultColumn("t0__amount", "decimal"),
                        ResultColumn("t1__amount", "decimal"),
                    ),
                rows = emptyList(),
                rowCount = 0,
                truncated = false,
                warnings = emptyList(),
            )
        val fields =
            buildExploreFieldOptions(
                sample = sample,
                tables =
                    listOf(
                        TableRef("public", "orders", "t0"),
                        TableRef("public", "refunds", "t1"),
                    ),
            )
        val bar =
            VisualizationConfig(
                chartType = ChartType.Bar,
                x = VisualizationField("t0__quantity", "quantity"),
                values =
                    listOf(
                        VisualizationMeasure.countRows(),
                        VisualizationMeasure(
                            alias = "sum_orders_amount",
                            fn = MeasureFn.Sum,
                            sourceColumn = "t0__amount",
                            label = "Sum orders.amount",
                            axis = ValueAxis.Secondary,
                        ),
                    ),
            )

        val scatter = bar.forChartType(ChartType.Scatter, fields)
        val scatterY = scatter.values.single()
        assertEquals("t0__amount", scatterY.sourceColumn)
        assertEquals("orders.amount", scatterY.label)
        assertFalse(scatterY.aggregate)
        assertEquals(ValueAxis.Primary, scatterY.axis)

        listOf(ChartType.Bar, ChartType.Line, ChartType.Kpi).forEach { target ->
            val restored = scatter.forChartType(target, fields)
            val measure = restored.values.single()
            assertTrue(measure.aggregate, "Expected aggregate=true for $target")
            assertEquals("Sum orders.amount", measure.label, "Expected restored label for $target")
            if (target == ChartType.Kpi) {
                assertNull(restored.x)
                assertEquals(ValueAxis.Primary, measure.axis)
            }
        }

        val customLabeled =
            bar.copy(
                values =
                    listOf(
                        VisualizationMeasure(
                            alias = "rev",
                            fn = MeasureFn.Sum,
                            sourceColumn = "t0__amount",
                            label = "Gross Revenue",
                        ),
                    ),
            )
        assertEquals(
            "Gross Revenue",
            customLabeled.forChartType(ChartType.Line, fields).values.single().label,
        )
    }

    @Test
    fun filterSupportingTextHidesPinnedBadgeWhenDisabled() {
        val allPinned = PivotFilter.Members("f1", "col_bool", "col_bool", pinned = true)
        val onePinned =
            PivotFilter.Members(
                "f2",
                "col_bool",
                "col_bool",
                includedKeys = setOf("true"),
                pinned = true,
            )

        assertEquals("All values · Pinned", filterSupportingText(allPinned, memberCount = 2))
        assertEquals(
            "All values",
            filterSupportingText(allPinned, memberCount = 2, showPinned = false),
        )
        assertEquals("1 selected · Pinned", filterSupportingText(onePinned, memberCount = 2))
        assertEquals(
            "1 selected",
            filterSupportingText(onePinned, memberCount = 2, showPinned = false),
        )
    }

    @Test
    fun chartTypeSwitchRestoresDroppedXWhenReturningFromKpiToBar() {
        val createdAt = buildField("created_at", "timestamp")
        val amount = buildField("amount", "decimal")
        val fields = listOf(createdAt, amount)
        val initial =
            VisualizationConfig(
                chartType = ChartType.Line,
                x = VisualizationField("created_at", "created_at"),
                values =
                    listOf(
                        VisualizationMeasure(
                            alias = "sum_amount",
                            fn = MeasureFn.Sum,
                            sourceColumn = "amount",
                            label = "Sum amount",
                        ),
                    ),
            )
        val (kpi, mem1) =
            transitionVisualizationChartType(
                initial,
                ChartType.Kpi,
                fields,
                VisualizationChartSwitchMemory(),
            )
        assertNull(kpi.x)
        assertEquals("created_at", mem1.x?.column)

        val (bar, mem2) = transitionVisualizationChartType(kpi, ChartType.Bar, fields, mem1)
        assertEquals("created_at", bar.x?.column)
        assertNull(mem2.x)
    }

    @Test
    fun chartTypeSwitchPreservesMultipleMeasuresAcrossKpiAndScatter() {
        val createdAt = buildField("created_at", "timestamp")
        val amount = buildField("amount", "decimal")
        val qty = buildField("qty", "int")
        val fields = listOf(createdAt, amount, qty)
        val m1 =
            VisualizationMeasure(
                alias = "sum_amount",
                fn = MeasureFn.Sum,
                sourceColumn = "amount",
                label = "Sum amount",
            )
        val m2 =
            VisualizationMeasure(
                alias = "sum_qty",
                fn = MeasureFn.Sum,
                sourceColumn = "qty",
                label = "Sum qty",
            )
        val initial =
            VisualizationConfig(
                chartType = ChartType.Bar,
                x = VisualizationField("created_at", "created_at"),
                values = listOf(m1, m2),
            )
        val (kpi, mem1) =
            transitionVisualizationChartType(
                initial,
                ChartType.Kpi,
                fields,
                VisualizationChartSwitchMemory(),
            )
        assertEquals(1, kpi.values.size)
        assertEquals(2, mem1.values.size)

        val (scatter, mem2) = transitionVisualizationChartType(kpi, ChartType.Scatter, fields, mem1)
        assertEquals(1, scatter.values.size)
        assertEquals(2, mem2.values.size)

        val (bar, mem3) = transitionVisualizationChartType(scatter, ChartType.Bar, fields, mem2)
        assertEquals(listOf("sum_amount", "sum_qty"), bar.values.map { it.alias })
        assertEquals(emptyList(), mem3.values)
    }

    @Test
    fun chartTypeSwitchPreservesMultiMeasuresAcrossHistogramNonFirstScatterAndSeriesGuard() {
        val createdAt = buildField("created_at", "timestamp")
        val region = buildField("region", "varchar")
        val amount = buildField("amount", "decimal")
        val qty = buildField("qty", "int")
        val fields = listOf(createdAt, region, amount, qty)
        val countRows = VisualizationMeasure.countRows("count_rows")
        val sumAmount =
            VisualizationMeasure(
                alias = "sum_amount",
                fn = MeasureFn.Sum,
                sourceColumn = "amount",
                label = "Sum amount",
            )
        val sumQty =
            VisualizationMeasure(
                alias = "sum_qty",
                fn = MeasureFn.Sum,
                sourceColumn = "qty",
                label = "Sum qty",
            )

        // Bar([m1, m2]) -> KPI -> Histogram -> Bar keeps both measures instead of overwriting with
        // [m1].
        val multiBar =
            VisualizationConfig(
                chartType = ChartType.Bar,
                x = VisualizationField("created_at", "created_at"),
                values = listOf(sumAmount, sumQty),
            )
        val (kpi1, kpiMem1) =
            transitionVisualizationChartType(
                multiBar,
                ChartType.Kpi,
                fields,
                VisualizationChartSwitchMemory(),
            )
        val (histogram, histMem) =
            transitionVisualizationChartType(kpi1, ChartType.Histogram, fields, kpiMem1)
        assertEquals(emptyList(), histogram.values)
        assertEquals(listOf("sum_amount", "sum_qty"), histMem.values.map { it.alias })
        val (restoredFromHist, restoredHistMem) =
            transitionVisualizationChartType(histogram, ChartType.Bar, fields, histMem)
        assertEquals(listOf("sum_amount", "sum_qty"), restoredFromHist.values.map { it.alias })
        assertEquals(emptyList(), restoredHistMem.values)

        // Bar([Count rows, Sum amount]) -> Scatter -> Bar restores both even though Scatter kept
        // the 2nd measure.
        val countAndSumBar =
            VisualizationConfig(
                chartType = ChartType.Bar,
                x = VisualizationField("created_at", "created_at"),
                values = listOf(countRows, sumAmount),
            )
        val (scatterDirect, scatterDirectMem) =
            transitionVisualizationChartType(
                countAndSumBar,
                ChartType.Scatter,
                fields,
                VisualizationChartSwitchMemory(),
            )
        assertEquals(listOf("sum_amount"), scatterDirect.values.map { it.alias })
        val (barFromScatterDirect, barFromScatterDirectMem) =
            transitionVisualizationChartType(
                scatterDirect,
                ChartType.Bar,
                fields,
                scatterDirectMem,
            )
        assertEquals(
            listOf("count_rows", "sum_amount"),
            barFromScatterDirect.values.map { it.alias },
        )
        assertEquals(emptyList(), barFromScatterDirectMem.values)

        // Bar([Count rows, Sum amount]) -> KPI -> Scatter -> Bar preserves both measures and
        // recovers Scatter Y.
        val (kpi2, kpiMem2) =
            transitionVisualizationChartType(
                countAndSumBar,
                ChartType.Kpi,
                fields,
                VisualizationChartSwitchMemory(),
            )
        assertEquals(listOf("count_rows"), kpi2.values.map { it.alias })
        val (scatterViaKpi, scatterViaKpiMem) =
            transitionVisualizationChartType(kpi2, ChartType.Scatter, fields, kpiMem2)
        assertEquals(listOf("sum_amount"), scatterViaKpi.values.map { it.alias })
        val (barViaKpiScatter, barViaKpiScatterMem) =
            transitionVisualizationChartType(
                scatterViaKpi,
                ChartType.Bar,
                fields,
                scatterViaKpiMem,
            )
        assertEquals(
            listOf("count_rows", "sum_amount"),
            barViaKpiScatter.values.map { it.alias },
        )
        assertEquals(emptyList(), barViaKpiScatterMem.values)

        // Active Series on Scatter must not restore multiple measures alongside Series on Bar,
        // and must clear stale multi-measure memory so removing Series later does not resurrect it.
        val scatterWithSeries =
            VisualizationConfig(
                chartType = ChartType.Scatter,
                x = VisualizationField("qty", "qty"),
                values = listOf(sumAmount.copy(aggregate = false, label = "amount")),
                series = VisualizationField("region", "region"),
            )
        val (barWithSeries, barWithSeriesMem) =
            transitionVisualizationChartType(
                scatterWithSeries,
                ChartType.Bar,
                fields,
                VisualizationChartSwitchMemory(values = listOf(sumAmount, sumQty)),
            )
        assertEquals(listOf("sum_amount"), barWithSeries.values.map { it.alias })
        assertEquals("region", barWithSeries.series?.column)
        assertEquals(emptyList(), barWithSeriesMem.values)

        // Empty-values Scatter with active Series caps restored values to 1, normalizes its axis
        // to Primary, and clears stale multi-measure memory.
        val (barEmptyWithSeries, barEmptyWithSeriesMem) =
            transitionVisualizationChartType(
                scatterWithSeries.copy(values = emptyList()),
                ChartType.Bar,
                fields,
                VisualizationChartSwitchMemory(
                    values = listOf(sumAmount.copy(axis = ValueAxis.Secondary), sumQty),
                ),
            )
        assertEquals(listOf("sum_amount"), barEmptyWithSeries.values.map { it.alias })
        assertEquals(ValueAxis.Primary, barEmptyWithSeries.values.single().axis)
        assertEquals("region", barEmptyWithSeries.series?.column)
        assertEquals(emptyList(), barEmptyWithSeriesMem.values)

        // Bar([Count rows], series=region) -> Scatter -> Bar still remembers Count rows while
        // Scatter temporarily has empty values alongside active Series.
        val countWithSeriesBar =
            VisualizationConfig(
                chartType = ChartType.Bar,
                x = VisualizationField("created_at", "created_at"),
                values = listOf(countRows),
                series = VisualizationField("region", "region"),
            )
        val (scatterEmptyWithSeries, scatterEmptyWithSeriesMem) =
            transitionVisualizationChartType(
                countWithSeriesBar,
                ChartType.Scatter,
                fields,
                VisualizationChartSwitchMemory(),
            )
        assertEquals(emptyList(), scatterEmptyWithSeries.values)
        assertEquals("region", scatterEmptyWithSeries.series?.column)
        assertEquals(listOf("count_rows"), scatterEmptyWithSeriesMem.values.map { it.alias })
        val (restoredCountWithSeries, restoredCountWithSeriesMem) =
            transitionVisualizationChartType(
                scatterEmptyWithSeries,
                ChartType.Bar,
                fields,
                scatterEmptyWithSeriesMem,
            )
        assertEquals(listOf("count_rows"), restoredCountWithSeries.values.map { it.alias })
        assertEquals("region", restoredCountWithSeries.series?.column)
        assertEquals(emptyList(), restoredCountWithSeriesMem.values)
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
            )
            .single()
}
