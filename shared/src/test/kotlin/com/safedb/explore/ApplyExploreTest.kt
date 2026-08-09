package com.safedb.explore

import com.safedb.model.QueryResult
import com.safedb.model.ResultCell
import com.safedb.model.ResultColumn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ApplyExploreTest {
    @Test
    fun groupsRowsByDimensionAndComputesMeasures() {
        val preview =
            applyExplore(
                sample = sampleResult(),
                config =
                    ExploreConfig(
                        rowDimensions = listOf(PivotDimension("t0__status")),
                        measures =
                            listOf(
                                PivotMeasure("count", MeasureFn.Count, label = "Orders"),
                                PivotMeasure("sum", MeasureFn.Sum, "t0__amount", "Revenue"),
                                PivotMeasure("avg", MeasureFn.Avg, "t0__amount", "Average"),
                            ),
                        showColumnTotals = false,
                    ),
            )

        assertEquals(
            listOf("status", "Orders", "Revenue", "Average"),
            preview.result.columns.map { it.name },
        )
        assertEquals(
            listOf(
                listOf(
                    ResultCell.text("pending"),
                    ResultCell.IntegerCell(4),
                    ResultCell.IntegerCell(475),
                    ResultCell.FloatCell(118.75),
                ),
                listOf(
                    ResultCell.text("shipped"),
                    ResultCell.IntegerCell(2),
                    ResultCell.IntegerCell(450),
                    ResultCell.IntegerCell(225),
                ),
            ),
            preview.result.rows,
        )
    }

    @Test
    fun pivotsOptionalColumnDimensionAndAddsTotals() {
        val preview =
            applyExplore(
                sample = sampleResult(),
                config =
                    ExploreConfig(
                        rowDimensions = listOf(PivotDimension("t0__region")),
                        columnDimension = PivotDimension("t0__status"),
                        measures =
                            listOf(PivotMeasure("sum", MeasureFn.Sum, "t0__amount", "Revenue")),
                        showRowTotals = true,
                        showColumnTotals = true,
                    ),
            )

        assertEquals(
            listOf("region", "pending Revenue", "shipped Revenue", "Total Revenue"),
            preview.result.columns.map { it.name },
        )
        assertEquals(
            listOf("pending", "shipped", "Total"),
            preview.layout.columnGroups.map { it.label },
        )
        assertEquals(listOf(1, 2, 3), preview.layout.columnGroups.map { it.startColumnIndex })
        assertTrue(preview.layout.columnGroups.last().isTotal)
        assertTrue(preview.layout.hasGrandTotalRow)
        assertEquals(
            listOf(
                listOf(
                    ResultCell.text("East"),
                    ResultCell.IntegerCell(125),
                    ResultCell.IntegerCell(250),
                    ResultCell.IntegerCell(375),
                ),
                listOf(
                    ResultCell.text("West"),
                    ResultCell.IntegerCell(300),
                    ResultCell.IntegerCell(200),
                    ResultCell.IntegerCell(500),
                ),
                listOf(
                    ResultCell.text("(blank)"),
                    ResultCell.IntegerCell(50),
                    ResultCell.IntegerCell(0),
                    ResultCell.IntegerCell(50),
                ),
                listOf(
                    ResultCell.text("Total"),
                    ResultCell.IntegerCell(475),
                    ResultCell.IntegerCell(450),
                    ResultCell.IntegerCell(925),
                ),
            ),
            preview.result.rows,
        )
    }

    @Test
    fun supportsCountDistinctMinMaxAndNullBuckets() {
        val preview =
            applyExplore(
                sample = sampleResult(),
                config =
                    ExploreConfig(
                        rowDimensions = listOf(PivotDimension("t0__region")),
                        measures =
                            listOf(
                                PivotMeasure(
                                    "customers",
                                    MeasureFn.CountDistinct,
                                    "t0__customer",
                                    "Customers",
                                ),
                                PivotMeasure("min", MeasureFn.Min, "t0__amount", "Min amount"),
                                PivotMeasure("max", MeasureFn.Max, "t0__amount", "Max amount"),
                            ),
                        nullBucketLabel = "No region",
                        showColumnTotals = false,
                    ),
            )

        assertEquals(
            listOf(
                listOf(
                    ResultCell.text("East"),
                    ResultCell.IntegerCell(2),
                    ResultCell.IntegerCell(25),
                    ResultCell.IntegerCell(250),
                ),
                listOf(
                    ResultCell.text("West"),
                    ResultCell.IntegerCell(2),
                    ResultCell.IntegerCell(200),
                    ResultCell.IntegerCell(300),
                ),
                listOf(
                    ResultCell.text("No region"),
                    ResultCell.IntegerCell(1),
                    ResultCell.IntegerCell(50),
                    ResultCell.IntegerCell(50),
                ),
            ),
            preview.result.rows,
        )
    }

    @Test
    fun countDistinctIgnoresNullSourceValues() {
        val sample =
            QueryResult(
                columns =
                    listOf(ResultColumn("category", "varchar"), ResultColumn("code", "varchar")),
                rows =
                    listOf(
                        listOf(ResultCell.text("A"), ResultCell.text("x")),
                        listOf(ResultCell.text("A"), ResultCell.text("x")),
                        listOf(ResultCell.text("A"), ResultCell.Null),
                        listOf(ResultCell.text("A"), ResultCell.Null),
                    ),
                rowCount = 4,
                truncated = false,
                warnings = emptyList(),
            )

        val preview =
            applyExplore(
                sample = sample,
                config =
                    ExploreConfig(
                        rowDimensions = listOf(PivotDimension("category")),
                        measures =
                            listOf(PivotMeasure("codes", MeasureFn.CountDistinct, "code", "Codes")),
                        showColumnTotals = false,
                    ),
            )

        assertEquals(ResultCell.IntegerCell(1), preview.result.rows.single()[1])
    }

    @Test
    fun sortsByMeasureDescending() {
        val preview =
            applyExplore(
                sample = sampleResult(),
                config =
                    ExploreConfig(
                        rowDimensions = listOf(PivotDimension("t0__region")),
                        measures =
                            listOf(PivotMeasure("sum", MeasureFn.Sum, "t0__amount", "Revenue")),
                        showColumnTotals = false,
                        sort = ExploreSort(ExploreSortTarget.Measure("sum"), SortDir.Desc),
                    ),
            )

        assertEquals(
            listOf("West", "East", "(blank)"),
            preview.result.rows.map { (it.first() as ResultCell.TextCell).value.text },
        )
    }

    @Test
    fun warnsWhenNumericMeasureSkipsText() {
        val sample =
            QueryResult(
                columns =
                    listOf(ResultColumn("category", "varchar"), ResultColumn("amount", "varchar")),
                rows =
                    listOf(
                        listOf(ResultCell.text("A"), ResultCell.text("10.5")),
                        listOf(ResultCell.text("A"), ResultCell.text("not-a-number")),
                    ),
                rowCount = 2,
                truncated = false,
                warnings = emptyList(),
            )

        val preview =
            applyExplore(
                sample = sample,
                config =
                    ExploreConfig(
                        rowDimensions = listOf(PivotDimension("category")),
                        measures = listOf(PivotMeasure("sum", MeasureFn.Sum, "amount", "Revenue")),
                    ),
            )

        assertEquals(ResultCell.FloatCell(10.5), preview.result.rows.first()[1])
        assertTrue(preview.warnings.any { it.contains("skipped 1 non-numeric cell") })
    }

    @Test
    fun emptySamplesStillExposeConfiguredColumns() {
        val preview =
            applyExplore(
                sample =
                    QueryResult(
                        columns =
                            listOf(
                                ResultColumn("t0__status", "varchar"),
                                ResultColumn("t0__amount", "bigint"),
                            ),
                        rows = emptyList(),
                        rowCount = 0,
                        truncated = false,
                        warnings = emptyList(),
                    ),
                config =
                    ExploreConfig(
                        rowDimensions = listOf(PivotDimension("t0__status")),
                        measures =
                            listOf(PivotMeasure("sum", MeasureFn.Sum, "t0__amount", "Revenue")),
                    ),
            )

        assertEquals(listOf("status", "Revenue"), preview.result.columns.map { it.name })
        assertTrue(preview.result.rows.isEmpty())
    }

    @Test
    fun buildsExpandableRowHierarchyWithBottomSubtotals() {
        val preview =
            applyExplore(
                sample = sampleResult(),
                config =
                    ExploreConfig(
                        rowDimensions =
                            listOf(
                                PivotDimension("t0__region", "Region", id = "region"),
                                PivotDimension("t0__status", "Status", id = "status"),
                            ),
                        measures = listOf(PivotMeasure("count", MeasureFn.Count, label = "Orders")),
                        showColumnTotals = false,
                    ),
            )

        assertEquals(PivotRowKind.Group, preview.layout.rowEntries.first().kind)
        assertEquals("East", preview.layout.rowEntries.first().label)
        assertTrue(preview.layout.rowEntries.first().hasChildren)
        assertTrue(
            preview.layout.rowEntries.any {
                it.label == "East total" && it.kind == PivotRowKind.Subtotal
            }
        )

        val eastPath = preview.layout.rowEntries.first().pathKey
        val collapsed =
            applyExplore(
                sampleResult(),
                ExploreConfig(
                    rowDimensions =
                        listOf(
                            PivotDimension("t0__region", "Region", id = "region"),
                            PivotDimension("t0__status", "Status", id = "status"),
                        ),
                    measures = listOf(PivotMeasure("count", MeasureFn.Count, label = "Orders")),
                    showColumnTotals = false,
                    collapsedRowPaths = setOf(eastPath),
                ),
            )
        val east = collapsed.layout.rowEntries.first { it.pathKey == eastPath }
        assertFalse(east.expanded)
        assertEquals(PivotRowKind.Subtotal, east.kind)
        assertFalse(collapsed.layout.rowEntries.any { it.pathKey.startsWith("$eastPath/") })
    }

    @Test
    fun supportsNestedColumnFieldsAndHeaderRows() {
        val preview =
            applyExplore(
                sampleResult(),
                ExploreConfig(
                    rowDimensions = listOf(PivotDimension("t0__region")),
                    columnDimensions =
                        listOf(
                            PivotDimension("t0__status", id = "status"),
                            PivotDimension("t0__customer", id = "customer"),
                        ),
                    measures = listOf(PivotMeasure("count", MeasureFn.Count, label = "Orders")),
                    showColumnTotals = false,
                ),
            )

        assertEquals(2, preview.layout.columnHeaderRows.size)
        assertTrue(
            preview.layout.columnHeaderRows.first().any { it.label == "pending" && it.hasChildren }
        )
        assertTrue(preview.layout.columnLeaves.any { it.labels.size == 2 })
    }

    @Test
    fun dimensionsCanSortMembersByAnAggregateValue() {
        val revenue = PivotMeasure("revenue", MeasureFn.Sum, "t0__amount", "Revenue")
        val preview =
            applyExplore(
                sampleResult(),
                ExploreConfig(
                    rowDimensions =
                        listOf(
                            PivotDimension(
                                "t0__region",
                                id = "region",
                                sortMode = DimensionSortMode.ValueDescending,
                                sortMeasureAlias = "revenue",
                            )
                        ),
                    measures = listOf(revenue),
                    showColumnTotals = false,
                ),
            )

        assertEquals(listOf("West", "East", "(blank)"), preview.layout.rowEntries.map { it.label })
    }

    @Test
    fun groupsDatesAndNumericValues() {
        val sample =
            QueryResult(
                columns =
                    listOf(ResultColumn("placed", "timestamp"), ResultColumn("amount", "decimal")),
                rows =
                    listOf(
                        listOf(ResultCell.text("2026-01-15 10:00:00"), ResultCell.IntegerCell(12)),
                        listOf(ResultCell.text("2026-02-01 10:00:00"), ResultCell.IntegerCell(67)),
                    ),
                rowCount = 2,
                truncated = false,
                warnings = emptyList(),
            )
        val preview =
            applyExplore(
                sample,
                ExploreConfig(
                    rowDimensions =
                        listOf(
                            PivotDimension(
                                "placed",
                                "Placed",
                                id = "year",
                                grouping = PivotGrouping.Date(DateGroupUnit.Year),
                            ),
                            PivotDimension(
                                "amount",
                                "Amount",
                                id = "bin",
                                grouping = PivotGrouping.NumberBin("50"),
                            ),
                        ),
                    showColumnTotals = false,
                ),
            )

        assertEquals("2026", preview.layout.rowEntries.first().label)
        assertTrue(preview.layout.rowEntries.any { it.label == "0 – 50" })
        assertTrue(preview.layout.rowEntries.any { it.label == "50 – 100" })
    }

    @Test
    fun appliesMemberAndTopValueFiltersBeforeTotals() {
        val count = PivotMeasure("count", MeasureFn.Count, label = "Orders")
        val memberFiltered =
            applyExplore(
                sampleResult(),
                ExploreConfig(
                    rowDimensions = listOf(PivotDimension("t0__region", id = "region")),
                    measures = listOf(count),
                    filters =
                        listOf(
                            PivotFilter.Members(
                                id = "f1",
                                column = "t0__region",
                                label = "Region",
                                includedKeys = setOf(pivotCellKey(ResultCell.text("East"))),
                            )
                        ),
                ),
            )
        assertEquals(listOf("East", "Total"), memberFiltered.layout.rowEntries.map { it.label })
        assertEquals(ResultCell.IntegerCell(3), memberFiltered.result.rows.last()[1])

        val top =
            applyExplore(
                sampleResult(),
                ExploreConfig(
                    rowDimensions = listOf(PivotDimension("t0__region", id = "region")),
                    measures = listOf(count),
                    filters =
                        listOf(
                            PivotFilter.Value(
                                "f2",
                                "t0__region",
                                "Region",
                                "count",
                                ValueFilterOp.Top,
                                count = 1,
                            )
                        ),
                ),
            )
        assertEquals(listOf("East", "Total"), top.layout.rowEntries.map { it.label })
        assertEquals(ResultCell.IntegerCell(3), top.result.rows.last()[1])
    }

    @Test
    fun columnValueFiltersAlsoRecomputeGrandTotals() {
        val count = PivotMeasure("count", MeasureFn.Count, label = "Orders")
        val preview =
            applyExplore(
                sampleResult(),
                ExploreConfig(
                    rowDimensions = listOf(PivotDimension("t0__region", id = "region")),
                    columnDimensions = listOf(PivotDimension("t0__status", id = "status")),
                    measures = listOf(count),
                    filters =
                        listOf(
                            PivotFilter.Value(
                                "f",
                                "t0__status",
                                "Status",
                                "count",
                                ValueFilterOp.Top,
                                count = 1,
                            )
                        ),
                ),
            )

        assertEquals(
            listOf("pending", "Total"),
            preview.layout.columnLeaves.map { it.labels.last() },
        )
        val totalRow = preview.result.rows.last()
        assertEquals(ResultCell.IntegerCell(4), totalRow[1])
        assertEquals(ResultCell.IntegerCell(4), totalRow[2])
    }

    @Test
    fun valueFilterStillAppliesAfterItsFieldLeavesBothAxes() {
        val count = PivotMeasure("count", MeasureFn.Count, label = "Orders")
        val preview =
            applyExplore(
                sampleResult(),
                ExploreConfig(
                    rowDimensions = listOf(PivotDimension("t0__status", id = "status")),
                    measures = listOf(count),
                    filters =
                        listOf(
                            PivotFilter.Value(
                                id = "f",
                                column = "t0__region",
                                label = "Region",
                                measureAlias = "count",
                                op = ValueFilterOp.Top,
                                count = 1,
                            )
                        ),
                ),
            )

        assertEquals(
            listOf("pending", "shipped", "Total"),
            preview.layout.rowEntries.map { it.label },
        )
        assertEquals(
            listOf(ResultCell.IntegerCell(2), ResultCell.IntegerCell(1), ResultCell.IntegerCell(3)),
            preview.result.rows.map { it[1] },
        )
    }

    @Test
    fun runningTotalFollowsSortedDisplayOrder() {
        val revenue =
            PivotMeasure(
                alias = "sum",
                fn = MeasureFn.Sum,
                sourceColumn = "t0__amount",
                label = "Revenue",
                showAs = PivotShowAs(ShowAsMode.RunningTotal, baseDimensionId = "region"),
            )
        val preview =
            applyExplore(
                sample = sampleResult(),
                config =
                    ExploreConfig(
                        rowDimensions =
                            listOf(
                                PivotDimension(
                                    "t0__region",
                                    id = "region",
                                    sortMode = DimensionSortMode.LabelDescending,
                                )
                            ),
                        measures = listOf(revenue),
                        showColumnTotals = false,
                    ),
            )

        assertEquals(
            listOf("West", "East", "(blank)"),
            preview.result.rows.map { (it.first() as ResultCell.TextCell).value.text },
        )
        assertEquals(
            listOf(
                ResultCell.IntegerCell(500),
                ResultCell.IntegerCell(875),
                ResultCell.IntegerCell(925),
            ),
            preview.result.rows.map { it[1] },
        )
    }

    @Test
    fun calculatesMeasuresFormatsPercentagesAndTracksLineage() {
        val revenue = PivotMeasure("revenue", MeasureFn.Sum, "t0__amount", "Revenue")
        val count = PivotMeasure("orders", MeasureFn.Count, label = "Orders")
        val average =
            PivotMeasure(
                alias = "calc_average",
                fn = MeasureFn.Sum,
                label = "Revenue per order",
                formula = "[revenue] / [orders]",
            )
        val share =
            count.copy(
                alias = "share",
                label = "Share",
                showAs = PivotShowAs(ShowAsMode.PercentGrandTotal),
            )
        val preview =
            applyExplore(
                sampleResult(),
                ExploreConfig(
                    rowDimensions = listOf(PivotDimension("t0__region", id = "region")),
                    columnDimensions = listOf(PivotDimension("t0__status", id = "status")),
                    measures = listOf(revenue, count, average, share),
                    showColumnTotals = false,
                ),
            )

        val eastRow = preview.layout.rowEntries.indexOfFirst { it.label == "East" }
        assertTrue(eastRow >= 0)
        assertTrue(preview.layout.formattedRows[eastRow].any { it.contains("50") })
        val leaf = preview.layout.columnLeaves.first { it.labels.last() == "pending" }
        val lineage =
            preview.layout.cellLineage[
                    pivotCellLineageKey(
                        preview.layout.rowEntries[eastRow].pathKey,
                        leaf.pathKey,
                        "revenue",
                    )]
        assertNotNull(lineage)
        assertEquals(2, lineage.size)
    }

    @Test
    fun supportsExcelStyleStatisticalAggregations() {
        val preview =
            applyExplore(
                sampleResult(),
                ExploreConfig(
                    rowDimensions = listOf(PivotDimension("t0__region")),
                    measures =
                        listOf(
                            PivotMeasure(
                                "numbers",
                                MeasureFn.CountNumbers,
                                "t0__amount",
                                "Numeric rows",
                            ),
                            PivotMeasure("product", MeasureFn.Product, "t0__amount", "Product"),
                            PivotMeasure(
                                "variance",
                                MeasureFn.VariancePopulation,
                                "t0__amount",
                                "Variance",
                            ),
                        ),
                    showColumnTotals = false,
                ),
            )

        assertEquals(ResultCell.IntegerCell(3), preview.result.rows.first()[1])
        assertEquals(ResultCell.IntegerCell(625_000), preview.result.rows.first()[2])
        assertTrue(preview.result.rows.first()[3] is ResultCell.FloatCell)
    }

    @Test
    fun calculatedMeasureCyclesAndDivisionByZeroBecomeWarningsAndBlankCells() {
        val preview =
            applyExplore(
                sampleResult(),
                ExploreConfig(
                    measures =
                        listOf(
                            PivotMeasure("a", MeasureFn.Sum, label = "A", formula = "[b] + 1"),
                            PivotMeasure("b", MeasureFn.Sum, label = "B", formula = "[a] + 1"),
                            PivotMeasure("zero", MeasureFn.Sum, label = "Zero", formula = "1 / 0"),
                        ),
                    showColumnTotals = false,
                ),
            )

        assertTrue(preview.result.rows.single().all { it is ResultCell.Null })
        assertTrue(preview.result.warnings.any { it.contains("cycle") })
        assertTrue(preview.result.warnings.any { it.contains("Division by zero") })
    }

    @Test
    fun guardsExcessiveVisibleColumnCardinality() {
        val rows =
            (1..510).map { index ->
                listOf(ResultCell.text("row"), ResultCell.text("column-$index"))
            }
        val preview =
            applyExplore(
                QueryResult(
                    columns =
                        listOf(ResultColumn("row", "varchar"), ResultColumn("column", "varchar")),
                    rows = rows,
                    rowCount = rows.size,
                    truncated = false,
                    warnings = emptyList(),
                ),
                ExploreConfig(
                    rowDimensions = listOf(PivotDimension("row")),
                    columnDimensions = listOf(PivotDimension("column")),
                    showRowTotals = false,
                    showColumnTotals = false,
                ),
            )

        assertEquals(MAX_VISIBLE_COLUMN_LEAVES, preview.layout.columnLeaves.size)
        assertNotNull(preview.layout.overflowMessage)
        assertTrue(preview.result.warnings.any { it.contains("visible column groups") })
    }

    private fun sampleResult(): QueryResult =
        QueryResult(
            columns =
                listOf(
                    ResultColumn("t0__region", "varchar"),
                    ResultColumn("t0__status", "varchar"),
                    ResultColumn("t0__customer", "varchar"),
                    ResultColumn("t0__amount", "bigint"),
                ),
            rows =
                listOf(
                    listOf(
                        ResultCell.text("East"),
                        ResultCell.text("pending"),
                        ResultCell.text("Ada"),
                        ResultCell.IntegerCell(100),
                    ),
                    listOf(
                        ResultCell.text("East"),
                        ResultCell.text("pending"),
                        ResultCell.text("Ada"),
                        ResultCell.IntegerCell(25),
                    ),
                    listOf(
                        ResultCell.text("East"),
                        ResultCell.text("shipped"),
                        ResultCell.text("Lin"),
                        ResultCell.IntegerCell(250),
                    ),
                    listOf(
                        ResultCell.text("West"),
                        ResultCell.text("pending"),
                        ResultCell.text("Max"),
                        ResultCell.IntegerCell(300),
                    ),
                    listOf(
                        ResultCell.text("West"),
                        ResultCell.text("shipped"),
                        ResultCell.text("Nia"),
                        ResultCell.IntegerCell(200),
                    ),
                    listOf(
                        ResultCell.Null,
                        ResultCell.text("pending"),
                        ResultCell.text("Noor"),
                        ResultCell.IntegerCell(50),
                    ),
                ),
            rowCount = 6,
            truncated = false,
            warnings = emptyList(),
        )
}
