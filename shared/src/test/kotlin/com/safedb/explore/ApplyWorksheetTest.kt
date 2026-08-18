package com.safedb.explore

import com.safedb.model.QueryResult
import com.safedb.model.ResultCell
import com.safedb.model.ResultColumn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApplyWorksheetTest {
    @Test
    fun textSortsRemainCaseInsensitiveForRowsAndGroups() {
        val mixedCase =
            QueryResult(
                columns = listOf(ResultColumn("name", "varchar")),
                rows =
                    listOf(
                        listOf(ResultCell.text("Banana")),
                        listOf(ResultCell.text("apple")),
                    ),
                rowCount = 2,
                truncated = false,
                warnings = emptyList(),
            )
        val sort = WorksheetSort(WorksheetValueRef.Column("name"), SortDir.Asc)

        val rows = applyWorksheet(mixedCase, WorksheetConfig(sorts = listOf(sort)))
        val groups =
            applyWorksheet(
                mixedCase,
                WorksheetConfig(
                    groups = listOf(WorksheetGroup("name-group", "name")),
                    sorts = listOf(sort),
                ),
            )

        assertEquals(listOf("apple", "Banana"), rows.detailValues("name"))
        assertEquals(
            listOf("name: apple", "name: Banana"),
            groups.rows.filter { it.kind == WorksheetRowKind.Group }.map { it.label },
        )
    }

    @Test
    fun nullSortsBeforeEmptyText() {
        val nullAndEmpty =
            QueryResult(
                columns = listOf(ResultColumn("name", "varchar")),
                rows =
                    listOf(
                        listOf(ResultCell.text("")),
                        listOf(ResultCell.Null),
                    ),
                rowCount = 2,
                truncated = false,
                warnings = emptyList(),
            )

        val preview =
            applyWorksheet(
                nullAndEmpty,
                WorksheetConfig(
                    sorts = listOf(WorksheetSort(WorksheetValueRef.Column("name"), SortDir.Asc))
                ),
            )
        val values =
            preview.rows
                .filter { it.kind == WorksheetRowKind.Detail }
                .map { it.cells.single().value }

        assertTrue(values[0] is ResultCell.Null)
        assertEquals("", (values[1] as ResultCell.TextCell).value.text)
    }

    @Test
    fun filtersAndStableMultiSortSourceRows() {
        val preview =
            applyWorksheet(
                sample(),
                WorksheetConfig(
                    filters =
                        listOf(
                            WorksheetFilter(
                                "f",
                                "region",
                                op = WorksheetFilterOp.Equals,
                                value = "East",
                            )
                        ),
                    sorts =
                        listOf(
                            WorksheetSort(WorksheetValueRef.Column("amount"), SortDir.Desc),
                            WorksheetSort(WorksheetValueRef.Column("name"), SortDir.Asc),
                        ),
                ),
            )

        assertEquals(listOf("Bravo", "Acme"), preview.detailValues("name"))
    }

    @Test
    fun numericEqualityIgnoresEquivalentDecimalFormatting() {
        val equals =
            applyWorksheet(
                sample(),
                WorksheetConfig(
                    filters =
                        listOf(
                            WorksheetFilter(
                                "f",
                                "amount",
                                op = WorksheetFilterOp.Equals,
                                value = "10.00",
                            )
                        )
                ),
            )
        val notEquals =
            applyWorksheet(
                sample(),
                WorksheetConfig(
                    filters =
                        listOf(
                            WorksheetFilter(
                                "f",
                                "amount",
                                op = WorksheetFilterOp.NotEquals,
                                value = "20.0",
                            )
                        )
                ),
            )

        assertEquals(listOf("Acme"), equals.detailValues("name"))
        assertEquals(listOf("Acme"), notEquals.detailValues("name"))
    }

    @Test
    fun nestedGroupsCollapseAndAggregateAtSelectedLevel() {
        val aggregate =
            WorksheetCalculation.Aggregate(
                id = "sum",
                label = "Revenue",
                fn = WorksheetAggregateFn.Sum,
                sourceColumn = "amount",
                groupColumn = "region",
            )
        val base =
            WorksheetConfig(
                groups =
                    listOf(
                        WorksheetGroup("g1", "region"),
                        WorksheetGroup(
                            "g2",
                            "day",
                            grouping = PivotGrouping.Date(DateGroupUnit.Month),
                        ),
                    ),
                calculations = listOf(aggregate),
            )
        val preview = applyWorksheet(sample(), base)
        val east =
            preview.rows.first { it.kind == WorksheetRowKind.Group && it.label == "region: East" }

        assertEquals(2, preview.rows.count { it.kind == WorksheetRowKind.Group && it.depth == 0 })
        assertEquals(30.0, (east.cells.last().value as ResultCell.FloatCell).value)

        val collapsed =
            applyWorksheet(sample(), base.copy(collapsedGroupPaths = setOf(east.pathKey)))
        assertTrue(
            collapsed.rows.none { it.kind == WorksheetRowKind.Detail && it.pathKey == "row:0" }
        )
    }

    @Test
    fun numericBinsAndMemberFiltersUseTypedBuckets() {
        val included = pivotCellKey(ResultCell.text("East"))
        val preview =
            applyWorksheet(
                sample(),
                WorksheetConfig(
                    groups =
                        listOf(
                            WorksheetGroup(
                                "amount-bin",
                                "amount",
                                grouping = PivotGrouping.NumberBin("10"),
                            )
                        ),
                    filters =
                        listOf(
                            WorksheetFilter(
                                "region-filter",
                                "region",
                                op = WorksheetFilterOp.Members,
                                includedKeys = setOf(included),
                            )
                        ),
                ),
            )

        assertEquals(
            listOf("amount: 10 – 20", "amount: 20 – 30"),
            preview.rows.filter { it.kind == WorksheetRowKind.Group }.map { it.label },
        )
        assertEquals(2, preview.rows.count { it.kind == WorksheetRowKind.Detail })
    }

    @Test
    fun groupedDateAndNumericBucketSortsUseTypedBucketOrder() {
        val dateSample =
            QueryResult(
                columns = listOf(ResultColumn("day", "date"), ResultColumn("amount", "decimal")),
                rows =
                    listOf(
                        listOf(ResultCell.text("2026-04-01"), ResultCell.FloatCell(10.0)),
                        listOf(ResultCell.text("2026-01-01"), ResultCell.FloatCell(2.0)),
                        listOf(ResultCell.text("2026-02-01"), ResultCell.FloatCell(20.0)),
                    ),
                rowCount = 3,
                truncated = false,
                warnings = emptyList(),
            )
        val monthPreview =
            applyWorksheet(
                dateSample,
                WorksheetConfig(
                    groups =
                        listOf(
                            WorksheetGroup(
                                "month",
                                "day",
                                grouping = PivotGrouping.Date(DateGroupUnit.Month),
                            )
                        ),
                    sorts = listOf(WorksheetSort(WorksheetValueRef.Column("day"))),
                ),
            )
        val binPreview =
            applyWorksheet(
                dateSample,
                WorksheetConfig(
                    groups =
                        listOf(
                            WorksheetGroup(
                                "amount-bin",
                                "amount",
                                grouping = PivotGrouping.NumberBin("2"),
                            )
                        ),
                    sorts = listOf(WorksheetSort(WorksheetValueRef.Column("amount"))),
                ),
            )

        assertEquals(
            listOf("day: Jan 2026", "day: Feb 2026", "day: Apr 2026"),
            monthPreview.rows.filter { it.kind == WorksheetRowKind.Group }.map { it.label },
        )
        assertEquals(
            listOf("amount: 2 – 4", "amount: 10 – 12", "amount: 20 – 22"),
            binPreview.rows.filter { it.kind == WorksheetRowKind.Group }.map { it.label },
        )
    }

    @Test
    fun rowFormulaSupportsFieldsNullsAndErrors() {
        val preview =
            applyWorksheet(
                sample(),
                WorksheetConfig(
                    calculations =
                        listOf(
                            WorksheetCalculation.RowFormula("net", "Net", "[amount] - [discount]"),
                            WorksheetCalculation.RowFormula("bad", "Bad", "[amount] / 0"),
                        )
                ),
            )
        val first = preview.rows.first()

        assertEquals(
            8.0,
            (first.cells[first.cells.lastIndex - 1].value as ResultCell.FloatCell).value,
        )
        assertEquals("Division by zero", first.cells.last().error)
        assertTrue(
            preview.rows[2].cells[preview.rows[2].cells.lastIndex - 1].value is ResultCell.Null
        )
        assertEquals(sample().rowCount, preview.calculationErrorCount)
    }

    @Test
    fun aggregateAndGroupFormulaComposeByCalculationId() {
        val preview =
            applyWorksheet(
                sample(),
                WorksheetConfig(
                    groups = listOf(WorksheetGroup("g", "region")),
                    calculations =
                        listOf(
                            WorksheetCalculation.Aggregate(
                                "sum",
                                "Revenue",
                                WorksheetAggregateFn.Sum,
                                "amount",
                                "region",
                            ),
                            WorksheetCalculation.Aggregate(
                                "count",
                                "Orders",
                                WorksheetAggregateFn.Count,
                                groupColumn = "region",
                            ),
                            WorksheetCalculation.GroupFormula(
                                "avg",
                                "Average",
                                "[sum] / [count]",
                                "region",
                            ),
                        ),
                ),
            )
        val east =
            preview.rows.first { it.kind == WorksheetRowKind.Group && it.label == "region: East" }

        assertEquals(15.0, (east.cells.last().value as ResultCell.FloatCell).value)
    }

    @Test
    fun allAggregateFunctionsHandleDuplicates() {
        val calculations =
            listOf(
                WorksheetCalculation.Aggregate(
                    "count",
                    "Count",
                    WorksheetAggregateFn.Count,
                    "amount",
                ),
                WorksheetCalculation.Aggregate(
                    "distinct",
                    "Distinct",
                    WorksheetAggregateFn.CountDistinct,
                    "amount",
                ),
                WorksheetCalculation.Aggregate("sum", "Sum", WorksheetAggregateFn.Sum, "amount"),
                WorksheetCalculation.Aggregate(
                    "average",
                    "Average",
                    WorksheetAggregateFn.Average,
                    "amount",
                ),
                WorksheetCalculation.Aggregate(
                    "minimum",
                    "Minimum",
                    WorksheetAggregateFn.Minimum,
                    "amount",
                ),
                WorksheetCalculation.Aggregate(
                    "maximum",
                    "Maximum",
                    WorksheetAggregateFn.Maximum,
                    "amount",
                ),
            )
        val total =
            applyWorksheet(sample(), WorksheetConfig(calculations = calculations)).rows.last()

        assertEquals(3L, (total.cells[5].value as ResultCell.IntegerCell).value)
        assertEquals(2L, (total.cells[6].value as ResultCell.IntegerCell).value)
        assertEquals(50.0, (total.cells[7].value as ResultCell.FloatCell).value)
        assertEquals(10.0, (total.cells[9].value as ResultCell.FloatCell).value)
        assertEquals(20.0, (total.cells[10].value as ResultCell.FloatCell).value)
    }

    @Test
    fun runningTotalAndPreviousValueUseVisibleSortAndPartition() {
        val preview =
            applyWorksheet(
                sample(),
                WorksheetConfig(
                    sorts = listOf(WorksheetSort(WorksheetValueRef.Column("day"))),
                    calculations =
                        listOf(
                            WorksheetCalculation.Window(
                                id = "running",
                                label = "Running",
                                fn = WorksheetWindowFn.RunningTotal,
                                source = WorksheetValueRef.Column("amount"),
                                restartColumns = listOf("region"),
                            ),
                            WorksheetCalculation.Window(
                                id = "previous",
                                label = "Previous",
                                fn = WorksheetWindowFn.PreviousValue,
                                source = WorksheetValueRef.Column("amount"),
                                restartColumns = listOf("region"),
                            ),
                        ),
                ),
            )
        val east =
            preview.rows.filter { row ->
                row.kind == WorksheetRowKind.Detail &&
                    (row.cells[1].value as? ResultCell.TextCell)?.value?.text == "East"
            }

        assertEquals(
            listOf(10.0, 30.0),
            east.map { (it.cells[it.cells.lastIndex - 1].value as ResultCell.FloatCell).value },
        )
        assertTrue(east.first().cells.last().value is ResultCell.Null)
        assertEquals(10.0, (east.last().cells.last().value as ResultCell.FloatCell).value)
    }

    @Test
    fun windowPartitionsKeepDelimiterValuesDistinct() {
        val sample =
            QueryResult(
                columns =
                    listOf(
                        ResultColumn("first", "varchar"),
                        ResultColumn("second", "varchar"),
                        ResultColumn("sequence", "int"),
                        ResultColumn("amount", "decimal"),
                    ),
                rows =
                    listOf(
                        listOf(
                            ResultCell.text("a|TextCell:b"),
                            ResultCell.text("c"),
                            ResultCell.IntegerCell(1),
                            ResultCell.FloatCell(10.0),
                        ),
                        listOf(
                            ResultCell.text("a"),
                            ResultCell.text("b|TextCell:c"),
                            ResultCell.IntegerCell(2),
                            ResultCell.FloatCell(20.0),
                        ),
                    ),
                rowCount = 2,
                truncated = false,
                warnings = emptyList(),
            )
        val preview =
            applyWorksheet(
                sample,
                WorksheetConfig(
                    sorts = listOf(WorksheetSort(WorksheetValueRef.Column("sequence"))),
                    calculations =
                        listOf(
                            WorksheetCalculation.Window(
                                id = "running",
                                label = "Running",
                                fn = WorksheetWindowFn.RunningTotal,
                                source = WorksheetValueRef.Column("amount"),
                                restartColumns = listOf("first", "second"),
                            )
                        ),
                ),
            )

        assertEquals(
            listOf(10.0, 20.0),
            preview.rows.map { (it.cells.last().value as ResultCell.FloatCell).value },
        )
    }

    @Test
    fun runningAverageDifferenceAndAscendingRankWorkOnDetailRows() {
        val preview =
            applyWorksheet(
                sample(),
                WorksheetConfig(
                    sorts = listOf(WorksheetSort(WorksheetValueRef.Column("day"))),
                    calculations =
                        listOf(
                            WorksheetCalculation.Window(
                                "average",
                                "Running average",
                                WorksheetWindowFn.RunningAverage,
                                WorksheetValueRef.Column("amount"),
                            ),
                            WorksheetCalculation.Window(
                                "difference",
                                "Difference",
                                WorksheetWindowFn.DifferenceFromPrevious,
                                WorksheetValueRef.Column("amount"),
                            ),
                            WorksheetCalculation.Window(
                                "rank",
                                "Rank",
                                WorksheetWindowFn.RankAscending,
                                WorksheetValueRef.Column("amount"),
                            ),
                        ),
                ),
            )
        val rows = preview.rows.filter { it.kind == WorksheetRowKind.Detail }

        assertEquals(
            listOf(10.0, 15.0, 50.0 / 3.0),
            rows.map { (it.cells[it.cells.size - 3].value as ResultCell.FloatCell).value },
        )
        assertTrue(rows.first().cells[rows.first().cells.size - 2].value is ResultCell.Null)
        assertEquals(
            listOf(1.0, 2.0, 2.0),
            rows.map { (it.cells.last().value as ResultCell.FloatCell).value },
        )
    }

    @Test
    fun percentRankAndGroupWindowsAreDeterministic() {
        val preview =
            applyWorksheet(
                sample(),
                WorksheetConfig(
                    groups = listOf(WorksheetGroup("g", "region")),
                    sorts = listOf(WorksheetSort(WorksheetValueRef.Column("region"))),
                    calculations =
                        listOf(
                            WorksheetCalculation.Aggregate(
                                "sum",
                                "Revenue",
                                WorksheetAggregateFn.Sum,
                                "amount",
                                "region",
                            ),
                            WorksheetCalculation.Window(
                                "share",
                                "Share",
                                WorksheetWindowFn.PercentOfTotal,
                                WorksheetValueRef.Calculation("sum"),
                                WorksheetGrain.GroupRows,
                                "region",
                            ),
                            WorksheetCalculation.Window(
                                "rank",
                                "Rank",
                                WorksheetWindowFn.RankDescending,
                                WorksheetValueRef.Calculation("sum"),
                                WorksheetGrain.GroupRows,
                                "region",
                            ),
                        ),
                ),
            )
        val groups = preview.rows.filter { it.kind == WorksheetRowKind.Group }

        assertEquals(2, groups.size)
        assertEquals(
            0.6,
            (groups
                    .first { it.label == "region: East" }
                    .cells[groups.first().cells.lastIndex - 1]
                    .value as ResultCell.FloatCell)
                .value,
        )
        assertEquals(
            1.0,
            (groups.first { it.label == "region: East" }.cells.last().value as ResultCell.FloatCell)
                .value,
        )
    }

    @Test
    fun groupWindowUsesVisibleAggregateSortOrder() {
        val preview =
            applyWorksheet(
                sample(),
                WorksheetConfig(
                    groups = listOf(WorksheetGroup("g", "region")),
                    sorts =
                        listOf(WorksheetSort(WorksheetValueRef.Calculation("sum"), SortDir.Desc)),
                    calculations =
                        listOf(
                            WorksheetCalculation.Aggregate(
                                "sum",
                                "Revenue",
                                WorksheetAggregateFn.Sum,
                                "amount",
                                "region",
                            ),
                            WorksheetCalculation.Window(
                                "running",
                                "Running",
                                WorksheetWindowFn.RunningTotal,
                                WorksheetValueRef.Calculation("sum"),
                                WorksheetGrain.GroupRows,
                                "region",
                            ),
                        ),
                ),
            )
        val groups = preview.rows.filter { it.kind == WorksheetRowKind.Group }

        assertEquals(listOf("region: East", "region: West"), groups.map { it.label })
        assertEquals(
            listOf(30.0, 50.0),
            groups.map { (it.cells.last().value as ResultCell.FloatCell).value },
        )
    }

    @Test
    fun orderedWindowWithoutSortWarnsAndLeavesNulls() {
        val preview =
            applyWorksheet(
                sample(),
                WorksheetConfig(
                    calculations =
                        listOf(
                            WorksheetCalculation.Window(
                                "run",
                                "Running",
                                WorksheetWindowFn.RunningAverage,
                                WorksheetValueRef.Column("amount"),
                            )
                        )
                ),
            )

        assertTrue(preview.warnings.any { it.contains("needs a worksheet sort") })
        assertTrue(preview.rows.all { it.cells.last().value is ResultCell.Null })
    }

    @Test
    fun circularReferencesProduceWarning() {
        val preview =
            applyWorksheet(
                sample(),
                WorksheetConfig(
                    calculations =
                        listOf(
                            WorksheetCalculation.GroupFormula("a", "A", "[b] + 1"),
                            WorksheetCalculation.GroupFormula("b", "B", "[a] + 1"),
                        )
                ),
            )

        assertTrue(preview.warnings.any { it.contains("circular") })
    }

    @Test
    fun querySampleWarningsAreNotCopiedIntoWorksheetPreview() {
        val preview =
            applyWorksheet(
                sample().copy(warnings = listOf("No columns selected — query will select all columns")),
                WorksheetConfig(),
            )

        assertEquals(emptyList(), preview.warnings)
    }

    private fun WorksheetPreview.detailValues(column: String): List<String> {
        val index = columns.indexOfFirst { it.sourceColumn == column }
        return rows
            .filter { it.kind == WorksheetRowKind.Detail }
            .map { row -> (row.cells[index].value as ResultCell.TextCell).value.text }
    }

    private fun sample(): QueryResult =
        QueryResult(
            columns =
                listOf(
                    ResultColumn("name", "varchar"),
                    ResultColumn("region", "varchar"),
                    ResultColumn("day", "date"),
                    ResultColumn("amount", "decimal"),
                    ResultColumn("discount", "decimal"),
                ),
            rows =
                listOf(
                    listOf(
                        ResultCell.text("Acme"),
                        ResultCell.text("East"),
                        ResultCell.text("2026-01-01"),
                        ResultCell.FloatCell(10.0),
                        ResultCell.FloatCell(2.0),
                    ),
                    listOf(
                        ResultCell.text("Bravo"),
                        ResultCell.text("East"),
                        ResultCell.text("2026-01-02"),
                        ResultCell.FloatCell(20.0),
                        ResultCell.FloatCell(1.0),
                    ),
                    listOf(
                        ResultCell.text("Cyan"),
                        ResultCell.text("West"),
                        ResultCell.text("2026-01-01"),
                        ResultCell.FloatCell(20.0),
                        ResultCell.Null,
                    ),
                ),
            rowCount = 3,
            truncated = false,
            warnings = emptyList(),
        )
}
