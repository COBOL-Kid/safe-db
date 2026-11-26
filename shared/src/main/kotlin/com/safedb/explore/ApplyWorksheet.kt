package com.safedb.explore

import com.safedb.model.QueryResult
import com.safedb.model.ResultCell
import com.safedb.model.ResultColumn
import com.safedb.model.TableRef
import com.safedb.model.classifyColumn
import com.safedb.model.isNumeric
import java.math.BigDecimal
import java.math.MathContext

fun precedingWorksheetCalculations(
    calculations: List<WorksheetCalculation>,
    editingId: String?,
): List<WorksheetCalculation> {
    val editingIndex = editingId?.let { id -> calculations.indexOfFirst { it.id == id } } ?: -1
    return if (editingIndex >= 0) calculations.take(editingIndex) else calculations
}

private fun uniqueSampleDisplayLabels(
    sampleColumns: List<ResultColumn>,
    tables: List<TableRef> = emptyList(),
): Map<String, String> {
    val displays = sampleColumns.map { it.name to displayColumnLabel(it.name, tables) }
    val labelCounts = displays.groupingBy { it.second }.eachCount()
    return buildMap {
        displays.forEach { (rawName, display) ->
            if (labelCounts[display] == 1) {
                put(rawName, display)
            }
        }
    }
}

private fun uniqueCalculationLabels(
    calculations: List<WorksheetCalculation>,
    reservedNames: Set<String>,
): Map<String, String> {
    val labelCounts = calculations.groupingBy { it.label }.eachCount()
    val ids = calculations.mapTo(mutableSetOf()) { it.id }
    return buildMap {
        calculations.forEach { calc ->
            if (
                labelCounts[calc.label] == 1 && calc.label !in reservedNames && calc.label !in ids
            ) {
                put(calc.id, calc.label)
            }
        }
    }
}

fun worksheetFormulaTokens(
    sampleColumns: List<ResultColumn>,
    priorCalculations: List<WorksheetCalculation>,
    tables: List<TableRef> = emptyList(),
): List<Pair<String, String>> {
    val uniqueSampleLabels = uniqueSampleDisplayLabels(sampleColumns, tables)
    val columnTokens = sampleColumns.map { col ->
        val uniqueDisplay = uniqueSampleLabels[col.name]
        (uniqueDisplay ?: col.name) to (uniqueDisplay ?: displayColumnLabel(col.name, tables))
    }
    val reservedNames = sampleColumns.mapTo(mutableSetOf()) { it.name } + uniqueSampleLabels.values
    val uniqueCalcLabels = uniqueCalculationLabels(priorCalculations, reservedNames)
    val calcTokens = priorCalculations.map { calc ->
        (uniqueCalcLabels[calc.id] ?: calc.id) to calc.label
    }
    return columnTokens + calcTokens
}

fun worksheetFormulaValues(
    sampleColumns: List<ResultColumn>,
    sourceValue: (String) -> BigDecimal?,
    priorCalculations: List<WorksheetCalculation>,
    calculationValue: (String) -> BigDecimal?,
    tables: List<TableRef> = emptyList(),
): Map<String, BigDecimal?> = buildMap {
    val uniqueSampleLabels = uniqueSampleDisplayLabels(sampleColumns, tables)
    sampleColumns.forEach { col -> put(col.name, sourceValue(col.name)) }
    uniqueSampleLabels.forEach { (rawName, display) ->
        if (display !in this) {
            put(display, this[rawName])
        }
    }
    val reservedNames = sampleColumns.mapTo(mutableSetOf()) { it.name } + uniqueSampleLabels.values
    val uniqueCalcLabels = uniqueCalculationLabels(priorCalculations, reservedNames)
    val resolvedValues = priorCalculations.associate { it.id to calculationValue(it.id) }
    priorCalculations.forEach { calc -> put(calc.id, resolvedValues[calc.id]) }
    priorCalculations.forEach { calc ->
        uniqueCalcLabels[calc.id]?.let { label ->
            if (label !in this) {
                put(label, resolvedValues[calc.id])
            }
        }
    }
}

fun applyWorksheet(
    sample: QueryResult,
    config: WorksheetConfig,
    tables: List<TableRef> = emptyList(),
): WorksheetPreview = WorksheetEngine(sample, config, tables).apply()

private class WorksheetEngine(
    private val sample: QueryResult,
    private val config: WorksheetConfig,
    private val tables: List<TableRef>,
) {
    private val sampleIndex = SampleIndex(sample)
    private val numericColumns =
        sample.columns.associate { it.name to classifyColumn(it.dataType).isNumeric() }
    private val rowFormulas =
        config.calculations.filterIsInstance<WorksheetCalculation.RowFormula>()
    private val uniqueSampleLabels = uniqueSampleDisplayLabels(sample.columns, tables)
    private val reservedRowSampleNames =
        sample.columns.mapTo(mutableSetOf()) { it.name } + uniqueSampleLabels.values
    private val warnings = linkedSetOf<String>()
    private var errorCount = 0

    fun apply(): WorksheetPreview {
        validateConfiguration()
        var records =
            sample.rows
                .mapIndexed { index, row -> WorksheetRecord(index, row) }
                .filter(::passesFilters)
        if (rowFormulas.isNotEmpty()) {
            records.forEach(::applyRowFormulas)
        }
        records = records.sortedWith(recordComparator())

        val entries = mutableListOf<MutableWorksheetEntry>()
        if (config.groups.isEmpty()) {
            records.forEach { entries += detailEntry(it, emptyMap(), depth = 0) }
        } else {
            appendGroups(entries, records, depth = 0, parentPath = "", inheritedGroups = emptyMap())
        }
        if (
            config.calculations.any {
                it is WorksheetCalculation.Aggregate || it is WorksheetCalculation.GroupFormula
            }
        ) {
            entries +=
                MutableWorksheetEntry(
                        kind = WorksheetRowKind.GrandTotal,
                        depth = 0,
                        pathKey = "__grand_total__",
                        label = "Grand total",
                        expanded = true,
                        records = records,
                        groupColumn = null,
                        groupValues = emptyMap(),
                        sampleIndex = sampleIndex,
                    )
                    .also(::applyGroupCalculations)
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
            warnings = warnings.toList(),
            calculationErrorCount = errorCount,
        )
    }

    private fun validateConfiguration() {
        config.groups
            .filter { it.column !in sampleIndex }
            .forEach { warnings += "Unknown group field ${it.label}" }
        config.filters
            .filter { it.column !in sampleIndex }
            .forEach { warnings += "Unknown filter field ${it.label}" }
        val calculationIds = config.calculations.mapTo(mutableSetOf()) { it.id }
        val duplicates =
            config.calculations.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
        duplicates.forEach { warnings += "Duplicate calculation id $it" }
        config.sorts.forEach { sort ->
            when (val target = sort.target) {
                is WorksheetValueRef.Column ->
                    if (target.column !in sampleIndex) {
                        warnings += "Unknown sort field ${target.column}"
                    }
                is WorksheetValueRef.Calculation ->
                    if (target.id !in calculationIds)
                        warnings += "Unknown sort calculation ${target.id}"
            }
        }
        validateCalculationDependencies()
    }

    private fun validateCalculationDependencies() {
        val rowCalcById = rowFormulas.associateBy { it.id }
        val rowCalcIdByLabel =
            uniqueCalculationLabels(rowFormulas, reservedRowSampleNames).entries.associate {
                (id, label) ->
                label to id
            }
        val groupCalcs =
            config.calculations.filter {
                it is WorksheetCalculation.Aggregate || it is WorksheetCalculation.GroupFormula
            }
        val groupCalcById = groupCalcs.associateBy { it.id }
        val groupCalcIdByLabel =
            uniqueCalculationLabels(groupCalcs, emptySet()).entries.associate { (id, label) ->
                label to id
            }
        val dependencies =
            config.calculations.associate { calculation ->
                val ids =
                    when (calculation) {
                        is WorksheetCalculation.GroupFormula ->
                            formulaReferences(calculation.formula).mapNotNullTo(mutableSetOf()) {
                                ref ->
                                groupCalcById[ref]?.id ?: groupCalcIdByLabel[ref]
                            }
                        is WorksheetCalculation.RowFormula ->
                            formulaReferences(calculation.formula).mapNotNullTo(mutableSetOf()) {
                                ref ->
                                rowCalcById[ref]?.id ?: rowCalcIdByLabel[ref]
                            }
                        is WorksheetCalculation.Window ->
                            setOfNotNull((calculation.source as? WorksheetValueRef.Calculation)?.id)
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
        if (dependencies.keys.any(::visit))
            warnings += "Calculated columns contain a circular reference"
    }

    private fun passesFilters(record: WorksheetRecord): Boolean =
        config.filters.all { filter ->
            val cell = record.source(filter.column)
            val text = resultCellText(cell)
            val numericText = numericColumns[filter.column] == true
            val numeric = cell.usesNumericComparison(numericText)
            fun compareNumber(expected: String): Int? {
                val number = ResultNumber.fromCell(cell, numericText) ?: return null
                return ResultNumber.parse(expected)?.let(number::compareTo)
            }
            fun equalsExpected(): Boolean =
                if (numeric) compareNumber(filter.value) == 0
                else text.equals(filter.value, ignoreCase = true)
            fun ordered(expected: String, matches: (Int) -> Boolean): Boolean =
                (if (numeric) compareNumber(expected) else compareTextOrNumber(text, expected))
                    ?.let(matches) == true
            when (filter.op) {
                WorksheetFilterOp.Members -> pivotCellMatches(cell, filter.includedKeys)
                WorksheetFilterOp.Equals -> equalsExpected()
                WorksheetFilterOp.NotEquals -> !equalsExpected()
                WorksheetFilterOp.Contains ->
                    matchesLabelOp(text, LabelFilterOp.Contains, filter.value)
                WorksheetFilterOp.StartsWith ->
                    matchesLabelOp(text, LabelFilterOp.StartsWith, filter.value)
                WorksheetFilterOp.EndsWith ->
                    matchesLabelOp(text, LabelFilterOp.EndsWith, filter.value)
                WorksheetFilterOp.GreaterThan -> ordered(filter.value) { it > 0 }
                WorksheetFilterOp.GreaterThanOrEqual -> ordered(filter.value) { it >= 0 }
                WorksheetFilterOp.LessThan -> ordered(filter.value) { it < 0 }
                WorksheetFilterOp.LessThanOrEqual -> ordered(filter.value) { it <= 0 }
                WorksheetFilterOp.Between ->
                    ordered(filter.value) { it >= 0 } &&
                        ordered(filter.secondValue.orEmpty()) { it <= 0 }
                WorksheetFilterOp.IsNull -> cell is ResultCell.Null
                WorksheetFilterOp.IsNotNull -> cell !is ResultCell.Null
            }
        }

    private fun applyRowFormulas(record: WorksheetRecord) {
        val baseValues = buildMap {
            sample.columns.forEach { col ->
                put(col.name, resultCellDecimal(record.source(col.name)))
            }
            uniqueSampleLabels.forEach { (rawName, display) ->
                if (display !in this) {
                    put(display, this[rawName])
                }
            }
        }
        val priorCalculations = mutableListOf<WorksheetCalculation>()
        val priorValues = linkedMapOf<String, BigDecimal?>()
        rowFormulas.forEach { calculation ->
            val values =
                if (priorCalculations.isEmpty()) {
                    baseValues
                } else {
                    val uniqueCalcLabels =
                        uniqueCalculationLabels(priorCalculations, reservedRowSampleNames)
                    buildMap(baseValues.size + priorCalculations.size * 2) {
                        putAll(baseValues)
                        putAll(priorValues)
                        uniqueCalcLabels.forEach { (id, label) -> put(label, priorValues[id]) }
                    }
                }
            val result = evaluatePivotFormula(calculation.formula, values, referenceLabel = "field")
            val cell =
                if (result.error != null) {
                    errorCount++
                    WorksheetCell(error = result.error)
                } else {
                    WorksheetCell(result.value.toResultCell())
                }
            record.calculations[calculation.id] = cell
            priorCalculations += calculation
            priorValues[calculation.id] = resultCellDecimal(cell.value)
        }
    }

    private fun recordComparator(): Comparator<WorksheetRecord> = Comparator { left, right ->
        for ((target, dir) in config.sorts) {
            val numericText =
                target is WorksheetValueRef.Column && numericColumns[target.column] == true
            val compared = compareCells(left.value(target), right.value(target), numericText)
            if (compared != 0) return@Comparator if (dir == SortDir.Desc) -compared else compared
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
        val group =
            config.groups.getOrNull(depth)
                ?: run {
                    records.forEach { out += detailEntry(it, inheritedGroups, depth) }
                    return
                }
        val buckets = linkedMapOf<GroupBucket, MutableList<WorksheetRecord>>()
        records.forEach { record ->
            buckets.getOrPut(groupBucket(record.source(group.column), group), ::mutableListOf) +=
                record
        }
        val orderedBuckets =
            buckets.entries.sortedWith(
                Comparator { (leftKey, leftRecords), (rightKey, rightRecords) ->
                    for ((target, dir) in config.sorts) {
                        val compared =
                            when (target) {
                                is WorksheetValueRef.Column -> {
                                    val numericText = numericColumns[target.column] == true
                                    if (target.column == group.column) {
                                        compareCells(
                                            leftKey.sortValue,
                                            rightKey.sortValue,
                                            numericText,
                                        )
                                    } else {
                                        compareCells(
                                            leftRecords.firstOrNull()?.source(target.column),
                                            rightRecords.firstOrNull()?.source(target.column),
                                            numericText,
                                        )
                                    }
                                }
                                is WorksheetValueRef.Calculation ->
                                    compareCells(
                                        groupCalculationValue(leftRecords, group.column, target.id),
                                        groupCalculationValue(
                                            rightRecords,
                                            group.column,
                                            target.id,
                                        ),
                                    )
                            }
                        if (compared != 0)
                            return@Comparator if (dir == SortDir.Desc) -compared else compared
                    }
                    0
                },
            )
        orderedBuckets.forEach { (bucket, bucketRecords) ->
            val path =
                if (parentPath.isBlank()) escapePath(bucket.key)
                else "$parentPath/${escapePath(bucket.key)}"
            val collapsed = path in config.collapsedGroupPaths
            val groupValues = inheritedGroups + (group.column to bucket.key)
            out +=
                MutableWorksheetEntry(
                        kind = WorksheetRowKind.Group,
                        depth = depth,
                        pathKey = path,
                        label = "${group.label}: ${bucket.label}",
                        expanded = !collapsed,
                        records = bucketRecords,
                        groupColumn = group.column,
                        groupValues = groupValues,
                        sampleIndex = sampleIndex,
                    )
                    .also(::applyGroupCalculations)
            if (!collapsed) appendGroups(out, bucketRecords, depth + 1, path, groupValues)
        }
    }

    private fun groupCalculationValue(
        records: List<WorksheetRecord>,
        groupColumn: String,
        calculationId: String,
    ): ResultCell {
        val entry =
            MutableWorksheetEntry(
                kind = WorksheetRowKind.Group,
                depth = 0,
                pathKey = "",
                label = "",
                expanded = true,
                records = records,
                groupColumn = groupColumn,
                groupValues = emptyMap(),
                sampleIndex = sampleIndex,
            )
        applyGroupCalculations(entry)
        return entry.calculations[calculationId]?.value ?: ResultCell.Null
    }

    private fun detailEntry(record: WorksheetRecord, groupValues: Map<String, String>, depth: Int) =
        MutableWorksheetEntry(
                kind = WorksheetRowKind.Detail,
                depth = depth,
                pathKey = "row:${record.index}",
                label = null,
                expanded = true,
                records = listOf(record),
                record = record,
                groupColumn = null,
                groupValues = groupValues,
                sampleIndex = sampleIndex,
            )
            .also { entry -> entry.calculations.putAll(record.calculations) }

    private fun applyGroupCalculations(entry: MutableWorksheetEntry) {
        val priorGroupCalculations = mutableListOf<WorksheetCalculation>()
        config.calculations.forEach { calculation ->
            when (calculation) {
                is WorksheetCalculation.Aggregate -> {
                    if (
                        calculation.groupColumn == null ||
                            calculation.groupColumn == entry.groupColumn ||
                            entry.kind == WorksheetRowKind.GrandTotal
                    ) {
                        entry.calculations[calculation.id] =
                            WorksheetCell(aggregate(entry.records, calculation))
                        priorGroupCalculations += calculation
                    }
                }
                is WorksheetCalculation.GroupFormula -> {
                    if (
                        calculation.groupColumn == null ||
                            calculation.groupColumn == entry.groupColumn ||
                            entry.kind == WorksheetRowKind.GrandTotal
                    ) {
                        val values =
                            worksheetFormulaValues(
                                sampleColumns = emptyList(),
                                sourceValue = { null },
                                priorCalculations = priorGroupCalculations,
                                calculationValue = {
                                    resultCellDecimal(entry.calculations[it]?.value)
                                },
                            )
                        val result =
                            evaluatePivotFormula(
                                calculation.formula,
                                values,
                                referenceLabel = "field",
                            )
                        entry.calculations[calculation.id] =
                            if (result.error != null) {
                                errorCount++
                                WorksheetCell(error = result.error)
                            } else {
                                WorksheetCell(result.value.toResultCell())
                            }
                        priorGroupCalculations += calculation
                    }
                }
                else -> Unit
            }
        }
    }

    private fun aggregate(
        records: List<WorksheetRecord>,
        calculation: WorksheetCalculation.Aggregate,
    ): ResultCell {
        if (calculation.fn == WorksheetAggregateFn.Count && calculation.sourceColumn == null) {
            return ResultCell.IntegerCell(records.size.toLong())
        }
        val concrete =
            calculation.sourceColumn
                ?.let { column ->
                    records.mapNotNull { row ->
                        row.source(column).takeUnless { it is ResultCell.Null }
                    }
                }
                .orEmpty()
        return when (calculation.fn) {
            WorksheetAggregateFn.Count -> ResultCell.IntegerCell(concrete.size.toLong())
            WorksheetAggregateFn.CountDistinct ->
                ResultCell.IntegerCell(concrete.map(::pivotCellKey).distinct().size.toLong())
            WorksheetAggregateFn.Sum ->
                concrete.mapNotNull(::resultCellDecimal).sumDecimals().toResultCell()
            WorksheetAggregateFn.Average -> {
                val values = concrete.mapNotNull(::resultCellDecimal)
                if (values.isEmpty()) ResultCell.Null
                else
                    values
                        .sumDecimals()
                        .divide(values.size.toBigDecimal(), MathContext.DECIMAL128)
                        .toResultCell()
            }
            WorksheetAggregateFn.Minimum,
            WorksheetAggregateFn.Maximum -> {
                val numericText = numericColumns[calculation.sourceColumn] == true
                val usable = concrete.filter { cell ->
                    !cell.usesNumericComparison(numericText) ||
                        ResultNumber.fromCell(cell, numericText) != null
                }
                val comparator =
                    Comparator<ResultCell> { left, right -> compareCells(left, right, numericText) }
                (if (calculation.fn == WorksheetAggregateFn.Minimum)
                    usable.minWithOrNull(comparator)
                else usable.maxWithOrNull(comparator)) ?: ResultCell.Null
            }
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
                    WorksheetGrain.GroupRows ->
                        entry.kind == WorksheetRowKind.Group &&
                            (calculation.groupColumn == null ||
                                entry.groupColumn == calculation.groupColumn)
                }
            }
            eligible
                .groupBy { entry -> calculation.restartColumns.map(entry::partitionValue) }
                .values
                .forEach { partition -> applyWindowPartition(partition, calculation) }
        }
    }

    private fun applyWindowPartition(
        entries: List<MutableWorksheetEntry>,
        calculation: WorksheetCalculation.Window,
    ) {
        val values = entries.map { resultCellDecimal(it.value(calculation.source)) }
        val total = values.filterNotNull().sumDecimals()
        var running = BigDecimal.ZERO
        var runningCount = 0
        val ranks =
            if (
                calculation.fn in
                    setOf(WorksheetWindowFn.RankAscending, WorksheetWindowFn.RankDescending)
            ) {
                rankedValues(values, calculation.fn == WorksheetWindowFn.RankDescending)
            } else {
                emptyMap()
            }
        entries.forEachIndexed { index, entry ->
            val current = values[index]
            val previous = values.getOrNull(index - calculation.offset.coerceAtLeast(1))
            val result =
                when (calculation.fn) {
                    WorksheetWindowFn.RunningTotal ->
                        current?.let {
                            running += it
                            running
                        }
                    WorksheetWindowFn.RunningAverage ->
                        current?.let {
                            running += it
                            runningCount++
                            running.divide(runningCount.toBigDecimal(), MathContext.DECIMAL128)
                        }
                    WorksheetWindowFn.PercentOfTotal ->
                        if (current == null || total.compareTo(BigDecimal.ZERO) == 0) null
                        else current.divide(total, MathContext.DECIMAL128)
                    WorksheetWindowFn.PreviousValue -> previous
                    WorksheetWindowFn.DifferenceFromPrevious ->
                        if (current == null || previous == null) null else current - previous
                    WorksheetWindowFn.RankAscending,
                    WorksheetWindowFn.RankDescending -> ranks[index]?.toBigDecimal()
                }
            entry.calculations[calculation.id] = WorksheetCell(result.toResultCell())
        }
    }

    private fun rankedValues(values: List<BigDecimal?>, descending: Boolean): Map<Int, Int> {
        val ordered =
            values
                .mapIndexedNotNull { index, value -> value?.let { index to it } }
                .sortedWith(
                    compareBy<Pair<Int, BigDecimal>> { it.second }
                        .let { if (descending) it.reversed() else it },
                )
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
        if (cell == null || cell is ResultCell.Null)
            return GroupBucket("<null>", "(blank)", ResultCell.Null)
        // Saved collapsedGroupPaths hold zero-padded ISO-week keys; the pivot's are unpadded.
        val bucket =
            groupingBucket(cell, group.grouping, group.label, WeekKeyStyle.Padded) {
                warnings += it
            }
        return GroupBucket(bucket.key, bucket.label, groupSortValue(cell, group.grouping, bucket))
    }

    private fun groupSortValue(
        cell: ResultCell,
        grouping: PivotGrouping,
        bucket: ExploreBucket,
    ): ResultCell =
        when (grouping) {
            PivotGrouping.Exact -> cell
            is PivotGrouping.Date ->
                if (bucket.ordinal == null) ResultCell.Null else ResultCell.text(bucket.key)
            is PivotGrouping.NumberBin -> bucket.ordinal?.let(ResultCell::FloatCell) ?: cell
        }

    private fun WorksheetRecord.source(column: String): ResultCell = sampleIndex.cell(row, column)

    private fun WorksheetRecord.value(ref: WorksheetValueRef): ResultCell =
        when (ref) {
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
    val sampleIndex: SampleIndex,
    val calculations: MutableMap<String, WorksheetCell> = linkedMapOf(),
) {
    fun cellFor(column: WorksheetDisplayColumn): WorksheetCell {
        column.calculationId?.let {
            return calculations[it] ?: WorksheetCell()
        }
        val source = column.sourceColumn ?: return WorksheetCell()
        if (kind == WorksheetRowKind.Detail) {
            return WorksheetCell(
                record?.let { sampleIndex.cell(it.row, source) } ?: ResultCell.Null,
            )
        }
        return WorksheetCell()
    }

    fun value(ref: WorksheetValueRef): ResultCell =
        when (ref) {
            is WorksheetValueRef.Column ->
                record?.let { sampleIndex.cell(it.row, ref.column) } ?: ResultCell.Null
            is WorksheetValueRef.Calculation -> calculations[ref.id]?.value ?: ResultCell.Null
        }

    fun partitionValue(column: String): String =
        groupValues[column]
            ?: record?.let { sampleIndex.cellOrNull(it.row, column) }?.let(::pivotCellKey)
            ?: ""
}

private data class GroupBucket(val key: String, val label: String, val sortValue: ResultCell)

private val ORDERED_WINDOWS =
    setOf(
        WorksheetWindowFn.RunningTotal,
        WorksheetWindowFn.RunningAverage,
        WorksheetWindowFn.PreviousValue,
        WorksheetWindowFn.DifferenceFromPrevious,
    )

private fun BigDecimal?.toResultCell(): ResultCell =
    this?.let { ResultCell.FloatCell(it.toDouble()) } ?: ResultCell.Null

private fun List<BigDecimal>.sumDecimals(): BigDecimal = fold(BigDecimal.ZERO, BigDecimal::add)

private fun ResultCell?.usesNumericComparison(numericText: Boolean): Boolean =
    this is ResultCell.IntegerCell ||
        this is ResultCell.FloatCell ||
        (numericText && this is ResultCell.TextCell)

private fun compareCells(
    left: ResultCell?,
    right: ResultCell?,
    numericText: Boolean = false,
): Int {
    val leftNumber = ResultNumber.fromCell(left, numericText)
    val rightNumber = ResultNumber.fromCell(right, numericText)
    val leftMissing =
        left == null ||
            left is ResultCell.Null ||
            (left.usesNumericComparison(numericText) && leftNumber == null)
    val rightMissing =
        right == null ||
            right is ResultCell.Null ||
            (right.usesNumericComparison(numericText) && rightNumber == null)
    if (leftMissing || rightMissing) return rightMissing.compareTo(leftMissing)
    // Keep numeric and text keys in separate ranges even if a column mixes representations.
    if (leftNumber != null && rightNumber != null) return leftNumber.compareTo(rightNumber)
    if (leftNumber != null) return -1
    if (rightNumber != null) return 1
    return resultCellText(left).compareTo(resultCellText(right), ignoreCase = true)
}

// Ordered text filters keep their legacy numeric comparison for pairs of decimal-looking strings.
private fun compareTextOrNumber(left: String, right: String): Int {
    val leftNumber = left.toBigDecimalOrNull()
    val rightNumber = right.toBigDecimalOrNull()
    return if (leftNumber != null && rightNumber != null) leftNumber.compareTo(rightNumber)
    else left.compareTo(right, ignoreCase = true)
}
