package com.safedb.explore

import com.safedb.model.QueryResult
import com.safedb.model.ResultCell
import com.safedb.model.TableRef
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields

fun applyWorksheet(sample: QueryResult, config: WorksheetConfig, tables: List<TableRef> = emptyList()): WorksheetPreview =
    WorksheetEngine(sample, config, tables).apply()

private class WorksheetEngine(
    private val sample: QueryResult,
    private val config: WorksheetConfig,
    private val tables: List<TableRef>,
) {
    private val indexes = sample.columns.mapIndexed { index, column -> column.name to index }.toMap()
    private val warnings = linkedSetOf<String>()
    private var errorCount = 0

    fun apply(): WorksheetPreview {
        validateConfiguration()
        var records = sample.rows.mapIndexed { index, row -> WorksheetRecord(index, row) }
            .filter(::passesFilters)
        records.forEach(::applyRowFormulas)
        records = records.sortedWith(recordComparator())

        val entries = mutableListOf<MutableWorksheetEntry>()
        if (config.groups.isEmpty()) {
            records.forEach { entries += detailEntry(it, emptyMap(), depth = 0) }
        } else {
            appendGroups(entries, records, depth = 0, parentPath = "", inheritedGroups = emptyMap())
        }
        if (config.calculations.any { it is WorksheetCalculation.Aggregate || it is WorksheetCalculation.GroupFormula }) {
            entries += MutableWorksheetEntry(
                kind = WorksheetRowKind.GrandTotal,
                depth = 0,
                pathKey = "__grand_total__",
                label = "Grand total",
                expanded = true,
                records = records,
                groupColumn = null,
                groupValues = emptyMap(),
                sourceIndexes = indexes,
            ).also(::applyGroupCalculations)
        }
        applyWindowCalculations(entries)

        val labels = displayColumnLabels(sample.columns, tables)
        val columns = buildList {
            sample.columns.forEach { column ->
                add(
                    WorksheetDisplayColumn(
                        id = "column:${column.name}",
                        label = labels[column.name] ?: displayColumnLabel(column.name),
                        dataType = column.dataType,
                        sourceColumn = column.name,
                    ),
                )
            }
            config.calculations.forEach { calculation ->
                add(
                    WorksheetDisplayColumn(
                        id = "calculation:${calculation.id}",
                        label = calculation.label,
                        dataType = "calculated",
                        calculationId = calculation.id,
                        numberFormat = calculation.numberFormat,
                    ),
                )
            }
        }
        val rows = entries.map { entry ->
            WorksheetDisplayRow(
                kind = entry.kind,
                depth = entry.depth,
                pathKey = entry.pathKey,
                label = entry.label,
                expanded = entry.expanded,
                cells = columns.map { column -> entry.cellFor(column) },
                sourceRowIndex = entry.record?.index,
            )
        }
        return WorksheetPreview(
            columns = columns,
            rows = rows,
            warnings = (sample.warnings + warnings).distinct(),
            calculationErrorCount = errorCount,
        )
    }

    private fun validateConfiguration() {
        config.groups.filter { it.column !in indexes }.forEach { warnings += "Unknown group field ${it.label}" }
        config.filters.filter { it.column !in indexes }.forEach { warnings += "Unknown filter field ${it.label}" }
        val calculationIds = config.calculations.mapTo(mutableSetOf()) { it.id }
        val duplicates = config.calculations.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
        duplicates.forEach { warnings += "Duplicate calculation id $it" }
        config.sorts.forEach { sort ->
            when (val target = sort.target) {
                is WorksheetValueRef.Column -> if (target.column !in indexes) warnings += "Unknown sort field ${target.column}"
                is WorksheetValueRef.Calculation -> if (target.id !in calculationIds) warnings += "Unknown sort calculation ${target.id}"
            }
        }
        validateCalculationDependencies()
    }

    private fun validateCalculationDependencies() {
        val dependencies = config.calculations.associate { calculation ->
            val ids = when (calculation) {
                is WorksheetCalculation.GroupFormula -> formulaReferences(calculation.formula)
                is WorksheetCalculation.RowFormula -> formulaReferences(calculation.formula)
                    .filterTo(mutableSetOf()) { ref -> config.calculations.any { it.id == ref } }
                is WorksheetCalculation.Window -> setOfNotNull((calculation.source as? WorksheetValueRef.Calculation)?.id)
                is WorksheetCalculation.Aggregate -> emptySet()
            }
            calculation.id to ids
        }
        val visiting = mutableSetOf<String>()
        val visited = mutableSetOf<String>()
        fun visit(id: String): Boolean {
            if (id in visiting) return true
            if (!visited.add(id)) return false
            visiting += id
            val cycle = dependencies[id].orEmpty().any(::visit)
            visiting -= id
            return cycle
        }
        if (dependencies.keys.any(::visit)) warnings += "Calculated columns contain a circular reference"
    }

    private fun passesFilters(record: WorksheetRecord): Boolean = config.filters.all { filter ->
        val cell = record.source(filter.column)
        val text = cellText(cell)
        val comparison = compareTextOrNumber(text, filter.value)
        when (filter.op) {
            WorksheetFilterOp.Members -> filter.includedKeys.isEmpty() || pivotCellKey(cell) in filter.includedKeys
            WorksheetFilterOp.Equals -> filterEquals(cell, text, filter.value)
            WorksheetFilterOp.NotEquals -> !filterEquals(cell, text, filter.value)
            WorksheetFilterOp.Contains -> text.contains(filter.value, ignoreCase = true)
            WorksheetFilterOp.StartsWith -> text.startsWith(filter.value, ignoreCase = true)
            WorksheetFilterOp.EndsWith -> text.endsWith(filter.value, ignoreCase = true)
            WorksheetFilterOp.GreaterThan -> comparison > 0
            WorksheetFilterOp.GreaterThanOrEqual -> comparison >= 0
            WorksheetFilterOp.LessThan -> comparison < 0
            WorksheetFilterOp.LessThanOrEqual -> comparison <= 0
            WorksheetFilterOp.Between -> comparison >= 0 && compareTextOrNumber(text, filter.secondValue.orEmpty()) <= 0
            WorksheetFilterOp.IsNull -> cell is ResultCell.Null
            WorksheetFilterOp.IsNotNull -> cell !is ResultCell.Null
        }
    }

    private fun applyRowFormulas(record: WorksheetRecord) {
        config.calculations.filterIsInstance<WorksheetCalculation.RowFormula>().forEach { calculation ->
            val values = buildMap<String, BigDecimal?> {
                sample.columns.forEach { column -> put(column.name, record.source(column.name).decimalOrNull()) }
                record.calculations.forEach { (id, cell) -> put(id, cell.value.decimalOrNull()) }
            }
            val result = evaluatePivotFormula(calculation.formula, values)
            record.calculations[calculation.id] = if (result.error != null) {
                errorCount++
                WorksheetCell(error = result.error)
            } else {
                WorksheetCell(result.value.toResultCell())
            }
        }
    }

    private fun recordComparator(): Comparator<WorksheetRecord> = Comparator { left, right ->
        for (sort in config.sorts) {
            val compared = compareCells(left.value(sort.target), right.value(sort.target))
            if (compared != 0) return@Comparator if (sort.dir == SortDir.Desc) -compared else compared
        }
        left.index.compareTo(right.index)
    }

    private fun appendGroups(
        out: MutableList<MutableWorksheetEntry>,
        records: List<WorksheetRecord>,
        depth: Int,
        parentPath: String,
        inheritedGroups: Map<String, String>,
    ) {
        val group = config.groups.getOrNull(depth) ?: run {
            records.forEach { out += detailEntry(it, inheritedGroups, depth) }
            return
        }
        val buckets = linkedMapOf<GroupBucket, MutableList<WorksheetRecord>>()
        records.forEach { record -> buckets.getOrPut(groupBucket(record.source(group.column), group), ::mutableListOf) += record }
        val orderedBuckets = buckets.entries.sortedWith(Comparator { left, right ->
            for (sort in config.sorts) {
                val compared = when (val target = sort.target) {
                    is WorksheetValueRef.Column -> if (target.column == group.column) {
                        compareCells(left.key.sortValue, right.key.sortValue)
                    } else {
                        compareCells(left.value.firstOrNull()?.source(target.column), right.value.firstOrNull()?.source(target.column))
                    }
                    is WorksheetValueRef.Calculation -> compareCells(
                        groupCalculationValue(left.value, group.column, target.id),
                        groupCalculationValue(right.value, group.column, target.id),
                    )
                }
                if (compared != 0) return@Comparator if (sort.dir == SortDir.Desc) -compared else compared
            }
            0
        })
        orderedBuckets.forEach { (bucket, bucketRecords) ->
            val path = if (parentPath.isBlank()) escapeGroupPath(bucket.key) else "$parentPath/${escapeGroupPath(bucket.key)}"
            val collapsed = path in config.collapsedGroupPaths
            val groupValues = inheritedGroups + (group.column to bucket.key)
            out += MutableWorksheetEntry(
                kind = WorksheetRowKind.Group,
                depth = depth,
                pathKey = path,
                label = "${group.label}: ${bucket.label}",
                expanded = !collapsed,
                records = bucketRecords,
                groupColumn = group.column,
                groupValues = groupValues,
                sourceIndexes = indexes,
            ).also(::applyGroupCalculations)
            if (!collapsed) appendGroups(out, bucketRecords, depth + 1, path, groupValues)
        }
    }

    private fun groupCalculationValue(records: List<WorksheetRecord>, groupColumn: String, calculationId: String): ResultCell {
        val entry = MutableWorksheetEntry(
            kind = WorksheetRowKind.Group,
            depth = 0,
            pathKey = "",
            label = "",
            expanded = true,
            records = records,
            groupColumn = groupColumn,
            groupValues = emptyMap(),
            sourceIndexes = indexes,
        )
        applyGroupCalculations(entry)
        return entry.calculations[calculationId]?.value ?: ResultCell.Null
    }

    private fun detailEntry(record: WorksheetRecord, groupValues: Map<String, String>, depth: Int) = MutableWorksheetEntry(
        kind = WorksheetRowKind.Detail,
        depth = depth,
        pathKey = "row:${record.index}",
        label = null,
        expanded = true,
        records = listOf(record),
        record = record,
        groupColumn = null,
        groupValues = groupValues,
        sourceIndexes = indexes,
    ).also { entry -> entry.calculations.putAll(record.calculations) }

    private fun applyGroupCalculations(entry: MutableWorksheetEntry) {
        config.calculations.forEach { calculation ->
            when (calculation) {
                is WorksheetCalculation.Aggregate -> {
                    if (calculation.groupColumn == null || calculation.groupColumn == entry.groupColumn || entry.kind == WorksheetRowKind.GrandTotal) {
                        entry.calculations[calculation.id] = WorksheetCell(aggregate(entry.records, calculation))
                    }
                }
                is WorksheetCalculation.GroupFormula -> {
                    if (calculation.groupColumn == null || calculation.groupColumn == entry.groupColumn || entry.kind == WorksheetRowKind.GrandTotal) {
                        val values = entry.calculations.mapValues { it.value.value.decimalOrNull() }
                        val result = evaluatePivotFormula(calculation.formula, values)
                        entry.calculations[calculation.id] = if (result.error != null) {
                            errorCount++
                            WorksheetCell(error = result.error)
                        } else {
                            WorksheetCell(result.value.toResultCell())
                        }
                    }
                }
                else -> Unit
            }
        }
    }

    private fun aggregate(records: List<WorksheetRecord>, calculation: WorksheetCalculation.Aggregate): ResultCell {
        if (calculation.fn == WorksheetAggregateFn.Count && calculation.sourceColumn == null) {
            return ResultCell.IntegerCell(records.size.toLong())
        }
        val cells = calculation.sourceColumn?.let { column -> records.map { it.source(column) } }.orEmpty()
        val concrete = cells.filterNotNull().filterNot { it is ResultCell.Null }
        return when (calculation.fn) {
            WorksheetAggregateFn.Count -> ResultCell.IntegerCell(concrete.size.toLong())
            WorksheetAggregateFn.CountDistinct -> ResultCell.IntegerCell(concrete.map(::pivotCellKey).distinct().size.toLong())
            WorksheetAggregateFn.Sum -> concrete.mapNotNull { it.decimalOrNull() }.sumDecimals().toResultCell()
            WorksheetAggregateFn.Average -> {
                val values = concrete.mapNotNull { it.decimalOrNull() }
                if (values.isEmpty()) ResultCell.Null else values.sumDecimals().divide(values.size.toBigDecimal(), MathContext.DECIMAL128).toResultCell()
            }
            WorksheetAggregateFn.Minimum -> concrete.minWithOrNull(::compareCells) ?: ResultCell.Null
            WorksheetAggregateFn.Maximum -> concrete.maxWithOrNull(::compareCells) ?: ResultCell.Null
        }
    }

    private fun applyWindowCalculations(entries: List<MutableWorksheetEntry>) {
        config.calculations.filterIsInstance<WorksheetCalculation.Window>().forEach { calculation ->
            if (calculation.fn in ORDERED_WINDOWS && config.sorts.isEmpty()) {
                warnings += "${calculation.label} needs a worksheet sort"
                return@forEach
            }
            val eligible = entries.filter { entry ->
                when (calculation.grain) {
                    WorksheetGrain.DetailRows -> entry.kind == WorksheetRowKind.Detail
                    WorksheetGrain.GroupRows -> entry.kind == WorksheetRowKind.Group &&
                        (calculation.groupColumn == null || entry.groupColumn == calculation.groupColumn)
                }
            }
            eligible.groupBy { entry -> calculation.restartColumns.map(entry::partitionValue) }
                .values.forEach { partition -> applyWindowPartition(partition, calculation) }
        }
    }

    private fun applyWindowPartition(entries: List<MutableWorksheetEntry>, calculation: WorksheetCalculation.Window) {
        val values = entries.map { it.value(calculation.source).decimalOrNull() }
        val total = values.filterNotNull().sumDecimals()
        var running = BigDecimal.ZERO
        var runningCount = 0
        val ranks = if (calculation.fn in setOf(WorksheetWindowFn.RankAscending, WorksheetWindowFn.RankDescending)) {
            rankedValues(values, calculation.fn == WorksheetWindowFn.RankDescending)
        } else {
            emptyMap()
        }
        entries.forEachIndexed { index, entry ->
            val current = values[index]
            val previous = values.getOrNull(index - calculation.offset.coerceAtLeast(1))
            val result = when (calculation.fn) {
                WorksheetWindowFn.RunningTotal -> current?.let { running += it; running }
                WorksheetWindowFn.RunningAverage -> current?.let {
                    running += it
                    runningCount++
                    running.divide(runningCount.toBigDecimal(), MathContext.DECIMAL128)
                }
                WorksheetWindowFn.PercentOfTotal -> if (current == null || total.compareTo(BigDecimal.ZERO) == 0) null else current.divide(total, MathContext.DECIMAL128)
                WorksheetWindowFn.PreviousValue -> previous
                WorksheetWindowFn.DifferenceFromPrevious -> if (current == null || previous == null) null else current - previous
                WorksheetWindowFn.RankAscending,
                WorksheetWindowFn.RankDescending,
                -> ranks[index]?.toBigDecimal()
            }
            entry.calculations[calculation.id] = WorksheetCell(result.toResultCell())
        }
    }

    private fun rankedValues(values: List<BigDecimal?>, descending: Boolean): Map<Int, Int> {
        val ordered = values.mapIndexedNotNull { index, value -> value?.let { index to it } }
            .sortedWith(compareBy<Pair<Int, BigDecimal>> { it.second }.let { if (descending) it.reversed() else it })
        val result = mutableMapOf<Int, Int>()
        var previous: BigDecimal? = null
        var rank = 0
        ordered.forEachIndexed { index, (originalIndex, value) ->
            if (previous == null || value.compareTo(previous) != 0) rank = index + 1
            result[originalIndex] = rank
            previous = value
        }
        return result
    }

    private fun groupBucket(cell: ResultCell?, group: WorksheetGroup): GroupBucket {
        if (cell == null || cell is ResultCell.Null) return GroupBucket("<null>", "(blank)", ResultCell.Null)
        return when (val grouping = group.grouping) {
            PivotGrouping.Exact -> GroupBucket(pivotCellKey(cell), cellText(cell), cell)
            is PivotGrouping.Date -> dateBucket(cell, grouping.unit, group.label)
            is PivotGrouping.NumberBin -> numberBucket(cell, grouping, group.label)
        }
    }

    private fun dateBucket(cell: ResultCell, unit: DateGroupUnit, label: String): GroupBucket {
        val date = parseExploreDate(cellText(cell))
        if (date == null) {
            warnings += "$label contains values that could not be grouped as dates"
            return GroupBucket("<invalid-date>", "(invalid date)", ResultCell.Null)
        }
        return when (unit) {
            DateGroupUnit.Year -> GroupBucket(date.year.toString(), date.year.toString(), ResultCell.text(date.year.toString()))
            DateGroupUnit.Quarter -> {
                val quarter = ((date.monthValue - 1) / 3) + 1
                val key = "${date.year}-Q$quarter"
                GroupBucket(key, "Q$quarter ${date.year}", ResultCell.text(key))
            }
            DateGroupUnit.Month -> {
                val key = "%04d-%02d".format(date.year, date.monthValue)
                GroupBucket(key, date.format(DateTimeFormatter.ofPattern("MMM yyyy")), ResultCell.text(key))
            }
            DateGroupUnit.IsoWeek -> {
                val fields = WeekFields.ISO
                val year = date.get(fields.weekBasedYear())
                val week = date.get(fields.weekOfWeekBasedYear())
                val key = "%04d-W%02d".format(year, week)
                GroupBucket(key, key, ResultCell.text(key))
            }
            DateGroupUnit.Day -> GroupBucket(date.toString(), date.toString(), ResultCell.text(date.toString()))
        }
    }

    private fun numberBucket(cell: ResultCell, grouping: PivotGrouping.NumberBin, label: String): GroupBucket {
        val value = cell.decimalOrNull()
        val size = grouping.size.toBigDecimalOrNull()
        val start = grouping.start?.toBigDecimalOrNull() ?: BigDecimal.ZERO
        if (value == null || size == null || size <= BigDecimal.ZERO) {
            warnings += "$label needs a positive numeric bin size"
            return GroupBucket(pivotCellKey(cell), cellText(cell), cell)
        }
        val index = value.subtract(start).divide(size, 0, RoundingMode.FLOOR)
        val lower = start.add(index.multiply(size)).stripTrailingZeros()
        val upper = lower.add(size).stripTrailingZeros()
        return GroupBucket(
            "${lower.toPlainString()}:${size.toPlainString()}",
            "${lower.toPlainString()} – ${upper.toPlainString()}",
            ResultCell.FloatCell(lower.toDouble()),
        )
    }

    private fun WorksheetRecord.source(column: String): ResultCell = indexes[column]?.let(row::getOrNull) ?: ResultCell.Null

    private fun WorksheetRecord.value(ref: WorksheetValueRef): ResultCell = when (ref) {
        is WorksheetValueRef.Column -> source(ref.column)
        is WorksheetValueRef.Calculation -> calculations[ref.id]?.value ?: ResultCell.Null
    }
}

private data class WorksheetRecord(
    val index: Int,
    val row: List<ResultCell>,
    val calculations: MutableMap<String, WorksheetCell> = linkedMapOf(),
)

private data class MutableWorksheetEntry(
    val kind: WorksheetRowKind,
    val depth: Int,
    val pathKey: String,
    val label: String?,
    val expanded: Boolean,
    val records: List<WorksheetRecord>,
    val record: WorksheetRecord? = null,
    val groupColumn: String?,
    val groupValues: Map<String, String>,
    val sourceIndexes: Map<String, Int>,
    val calculations: MutableMap<String, WorksheetCell> = linkedMapOf(),
) {
    fun cellFor(column: WorksheetDisplayColumn): WorksheetCell {
        column.calculationId?.let { return calculations[it] ?: WorksheetCell() }
        val source = column.sourceColumn ?: return WorksheetCell()
        if (kind == WorksheetRowKind.Detail) {
            val index = sourceIndex(source)
            return WorksheetCell(index?.let { record?.row?.getOrNull(it) } ?: ResultCell.Null)
        }
        return WorksheetCell()
    }

    fun value(ref: WorksheetValueRef): ResultCell = when (ref) {
        is WorksheetValueRef.Column -> sourceIndex(ref.column)?.let { record?.row?.getOrNull(it) } ?: ResultCell.Null
        is WorksheetValueRef.Calculation -> calculations[ref.id]?.value ?: ResultCell.Null
    }

    fun partitionValue(column: String): String = groupValues[column]
        ?: sourceIndex(column)?.let { record?.row?.getOrNull(it) }?.let(::pivotCellKey)
        ?: ""

    private fun sourceIndex(column: String): Int? = sourceIndexes[column]
}

private data class GroupBucket(val key: String, val label: String, val sortValue: ResultCell)

private val ORDERED_WINDOWS = setOf(
    WorksheetWindowFn.RunningTotal,
    WorksheetWindowFn.RunningAverage,
    WorksheetWindowFn.PreviousValue,
    WorksheetWindowFn.DifferenceFromPrevious,
)

private fun ResultCell?.decimalOrNull(): BigDecimal? = resultCellDecimal(this)

private fun BigDecimal?.toResultCell(): ResultCell = this?.let { ResultCell.FloatCell(it.toDouble()) } ?: ResultCell.Null

private fun List<BigDecimal>.sumDecimals(): BigDecimal = fold(BigDecimal.ZERO, BigDecimal::add)

private fun compareCells(left: ResultCell?, right: ResultCell?): Int {
    if (left == null || left is ResultCell.Null) return if (right == null || right is ResultCell.Null) 0 else -1
    if (right == null || right is ResultCell.Null) return 1
    val leftNumber = left.decimalOrNull()
    val rightNumber = right.decimalOrNull()
    return if (leftNumber != null && rightNumber != null) leftNumber.compareTo(rightNumber) else cellText(left).compareTo(cellText(right), ignoreCase = true)
}

private fun compareTextOrNumber(left: String, right: String): Int {
    val leftNumber = left.toBigDecimalOrNull()
    val rightNumber = right.toBigDecimalOrNull()
    return if (leftNumber != null && rightNumber != null) leftNumber.compareTo(rightNumber) else left.compareTo(right, ignoreCase = true)
}

private fun filterEquals(cell: ResultCell, text: String, expected: String): Boolean {
    val numeric = cell is ResultCell.IntegerCell || cell is ResultCell.FloatCell
    if (numeric) {
        val left = cell.decimalOrNull()
        val right = expected.toBigDecimalOrNull()
        if (left != null && right != null) return left.compareTo(right) == 0
    }
    return text.equals(expected, ignoreCase = true)
}

private fun cellText(cell: ResultCell?): String = resultCellText(cell)


private fun escapeGroupPath(value: String): String = value.replace("%", "%25").replace("/", "%2F")
