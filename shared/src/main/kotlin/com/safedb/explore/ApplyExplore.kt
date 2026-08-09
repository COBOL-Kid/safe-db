package com.safedb.explore

import com.safedb.model.QueryResult
import com.safedb.model.ResultCell
import com.safedb.model.ResultColumn
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields
import java.util.BitSet
import java.util.Currency
import java.util.Locale
import kotlin.math.sqrt

fun applyExplore(sample: QueryResult, config: ExploreConfig): ExplorePreviewResult {
    val engine = PivotEngine(sample, config)
    return engine.apply()
}

private class PivotEngine(private val sample: QueryResult, private val config: ExploreConfig) {
    private val indexes =
        sample.columns.mapIndexed { index, column -> column.name to index }.toMap()
    private val warnings = linkedSetOf<String>()
    private val rowDimensions = config.rowDimensions.filterKnown(indexes, warnings, "row")
    private val columnDimensions =
        config.effectiveColumnDimensions.filterKnown(indexes, warnings, "column")
    private val measures =
        config.measures
            .ifEmpty { listOf(PivotMeasure.countRows()) }
            .filterValid(indexes, warnings)
            .ifEmpty { listOf(PivotMeasure.countRows()) }
    private val allRows = BitSet(sample.rows.size).apply { set(0, sample.rows.size) }
    private var activeRows: BitSet = allRows
    private val aggregateCache = mutableMapOf<BitSet, Map<String, ResultCell>>()

    fun apply(): ExplorePreviewResult {
        var records =
            sample.rows.mapIndexedNotNull { index, row ->
                if (!passesSourceFilters(row)) return@mapIndexedNotNull null
                PivotRecord(
                    index = index,
                    row = row,
                    rowBuckets = rowDimensions.map { bucketFor(row, it) },
                    columnBuckets = columnDimensions.map { bucketFor(row, it) },
                )
            }
        var filteredRows = BitSet(sample.rows.size).apply { records.forEach { set(it.index) } }
        activeRows = filteredRows
        val axisColumns = (rowDimensions + columnDimensions).mapTo(mutableSetOf()) { it.column }
        config.filters
            .filterIsInstance<PivotFilter.Value>()
            .filter { it.column !in axisColumns }
            .forEach { filter ->
                val columnIndex = indexes[filter.column] ?: return@forEach
                val groupedRows = linkedMapOf<String, BitSet>()
                records.forEach { record ->
                    val key = pivotCellKey(record.row.getOrNull(columnIndex))
                    groupedRows.getOrPut(key, ::BitSet).set(record.index)
                }
                val scored = groupedRows.map { (key, rows) ->
                    key to aggregateDecimal(rows, activeRows, filter.measureAlias)
                }
                val retainedKeys =
                    when (filter.op) {
                        ValueFilterOp.Top ->
                            scored
                                .sortedByDescending { it.second }
                                .take(filter.count.coerceAtLeast(1))
                                .mapTo(mutableSetOf()) { it.first }
                        ValueFilterOp.Bottom ->
                            scored
                                .sortedBy { it.second }
                                .take(filter.count.coerceAtLeast(1))
                                .mapTo(mutableSetOf()) { it.first }
                        else ->
                            scored
                                .filter { (_, value) -> passesValueComparison(value, filter) }
                                .mapTo(mutableSetOf()) { it.first }
                    }
                val retainedRows = BitSet(sample.rows.size)
                retainedKeys.forEach { key -> groupedRows[key]?.let(retainedRows::or) }
                activeRows.and(retainedRows)
                aggregateCache.clear()
                records = records.filter { activeRows[it.index] }
            }
        filteredRows = activeRows.clone() as BitSet
        var rowRoot = buildTree(records, rowDimensions, filteredRows) { it.rowBuckets }
        var columnRoot = buildTree(records, columnDimensions, filteredRows) { it.columnBuckets }
        var rawRowSlices = buildRowSlices(rowRoot)
        if (config.filters.any { it is PivotFilter.Value }) {
            val retained = BitSet(sample.rows.size)
            rawRowSlices
                .filter { it.kind !in setOf(PivotRowKind.Group, PivotRowKind.GrandTotal) }
                .forEach { retained.or(it.node.rows) }
            activeRows = retained
            aggregateCache.clear()
            val retainedRecords = records.filter { retained[it.index] }
            rowRoot = buildTree(retainedRecords, rowDimensions, retained) { it.rowBuckets }
            columnRoot = buildTree(retainedRecords, columnDimensions, retained) { it.columnBuckets }
            rawRowSlices = buildRowSlices(rowRoot)
        }
        var rawColumnSlices = buildColumnSlices(columnRoot)
        if (
            config.filters.filterIsInstance<PivotFilter.Value>().any { filter ->
                columnDimensions.any { it.column == filter.column }
            }
        ) {
            val retainedColumns = BitSet(sample.rows.size)
            rawColumnSlices
                .filterNot { it.isGrandTotal || it.isSubtotal }
                .forEach { retainedColumns.or(it.node.rows) }
            activeRows.and(retainedColumns)
            aggregateCache.clear()
            val retainedRecords = records.filter { activeRows[it.index] }
            rowRoot = buildTree(retainedRecords, rowDimensions, activeRows) { it.rowBuckets }
            columnRoot =
                buildTree(retainedRecords, columnDimensions, activeRows) { it.columnBuckets }
            rawRowSlices = buildRowSlices(rowRoot)
            rawColumnSlices = buildColumnSlices(columnRoot)
        }
        val maxColumns = rawColumnSlices.take(MAX_VISIBLE_COLUMN_LEAVES)
        val cellsPerRow = (maxColumns.size.coerceAtLeast(1) * measures.size.coerceAtLeast(1))
        val maxRowCount = (MAX_VISIBLE_PIVOT_CELLS / cellsPerRow).coerceAtLeast(1)
        val visibleRows = rawRowSlices.take(maxRowCount)
        val overflowMessage =
            when {
                rawColumnSlices.size > MAX_VISIBLE_COLUMN_LEAVES ->
                    "This pivot has ${rawColumnSlices.size} visible column groups. Showing the first $MAX_VISIBLE_COLUMN_LEAVES; filter, group, or collapse fields to see a smaller view."
                rawRowSlices.size > maxRowCount ->
                    "This pivot exceeds $MAX_VISIBLE_PIVOT_CELLS visible cells. Showing the first $maxRowCount rows; filter, group, or collapse fields to see a smaller view."
                else -> null
            }
        overflowMessage?.let(warnings::add)

        val includeRowHeader = rowDimensions.isNotEmpty()
        val columns = buildOutputColumns(includeRowHeader, maxColumns)
        val resultRows = mutableListOf<List<ResultCell>>()
        val formattedRows = mutableListOf<List<String>>()
        val rowEntries = mutableListOf<PivotRowEntry>()
        val lineage = linkedMapOf<String, List<Int>>()

        for (rowSlice in visibleRows) {
            rowEntries += rowSlice.toEntry()
            val cells = mutableListOf<ResultCell>()
            val formatted = mutableListOf<String>()
            if (includeRowHeader) {
                cells += ResultCell.text(rowSlice.label)
                formatted += rowSlice.label
            }
            for (columnSlice in maxColumns) {
                val rowBits = rowSlice.node.rows
                val columnBits = columnSlice.node.rows
                val matching = intersect(rowBits, columnBits)
                for (measure in measures) {
                    val cell =
                        if (rowSlice.kind == PivotRowKind.Group) {
                            ResultCell.Null
                        } else {
                            transformedValue(rowSlice.node, columnSlice.node, measure)
                        }
                    cells += cell
                    formatted +=
                        if (rowSlice.kind == PivotRowKind.Group) ""
                        else formatPivotCell(cell, measure)
                    lineage[cellKey(rowSlice.pathKey, columnSlice.pathKey, measure.alias)] =
                        matching.toIndexList()
                }
            }
            resultRows += cells
            formattedRows += formatted
        }

        val resultWarnings = (sample.warnings + warnings).distinct()
        val columnLeaves = maxColumns.map { it.toLeaf() }
        val headerRows = buildColumnHeaders(maxColumns)
        val columnGroups = maxColumns.mapIndexed { index, slice ->
            ExploreColumnGroup(
                label = slice.labels.lastOrNull(),
                startColumnIndex = (if (includeRowHeader) 1 else 0) + index * measures.size,
                measureAliases = measures.map { it.alias },
                isTotal = slice.isGrandTotal || slice.isSubtotal,
            )
        }
        return ExplorePreviewResult(
            result =
                QueryResult(
                    columns = columns,
                    rows = resultRows,
                    rowCount = resultRows.size,
                    truncated = sample.truncated,
                    warnings = resultWarnings,
                ),
            warnings = warnings.toList(),
            layout =
                ExplorePivotLayout(
                    rowDimensions = rowDimensions,
                    columnDimension = columnDimensions.firstOrNull(),
                    columnDimensions = columnDimensions,
                    measures = measures,
                    columnGroups = columnGroups,
                    hasGrandTotalRow = rowEntries.lastOrNull()?.kind == PivotRowKind.GrandTotal,
                    rowEntries = rowEntries,
                    columnLeaves = columnLeaves,
                    columnHeaderRows = headerRows,
                    formattedRows = formattedRows,
                    cellLineage = lineage,
                    overflowMessage = overflowMessage,
                ),
        )
    }

    private fun passesSourceFilters(row: List<ResultCell>): Boolean =
        config.filters.all { filter ->
            when (filter) {
                is PivotFilter.Members -> {
                    if (filter.includedKeys.isEmpty()) true
                    else {
                        val cell = indexes[filter.column]?.let(row::getOrNull)
                        pivotCellKey(cell) in filter.includedKeys
                    }
                }
                is PivotFilter.Label -> {
                    val text =
                        indexes[filter.column]?.let(row::getOrNull)?.let(::cellText).orEmpty()
                    when (filter.op) {
                        LabelFilterOp.Equals -> text.equals(filter.value, ignoreCase = true)
                        LabelFilterOp.Contains -> text.contains(filter.value, ignoreCase = true)
                        LabelFilterOp.StartsWith -> text.startsWith(filter.value, ignoreCase = true)
                        LabelFilterOp.EndsWith -> text.endsWith(filter.value, ignoreCase = true)
                    }
                }
                is PivotFilter.Value -> true
            }
        }

    private fun bucketFor(row: List<ResultCell>, dimension: PivotDimension): PivotBucket {
        val cell = row.getOrNull(indexes.getValue(dimension.column))
        if (cell == null || cell is ResultCell.Null) {
            return PivotBucket("<null>", config.nullBucketLabel, config.nullBucketLabel)
        }
        return when (val grouping = dimension.grouping) {
            PivotGrouping.Exact -> PivotBucket(pivotCellKey(cell), cellText(cell), cellText(cell))
            is PivotGrouping.Date -> dateBucket(cell, dimension, grouping.unit)
            is PivotGrouping.NumberBin -> numberBucket(cell, dimension, grouping)
        }
    }

    private fun dateBucket(
        cell: ResultCell,
        dimension: PivotDimension,
        unit: DateGroupUnit,
    ): PivotBucket {
        val dateTime = parseDateTime(cellText(cell))
        if (dateTime == null) {
            warnings += "${dimension.label} contains values that could not be grouped as dates"
            return PivotBucket("<invalid-date>", "(invalid date)", "9999")
        }
        val date = dateTime.toLocalDate()
        return when (unit) {
            DateGroupUnit.Year ->
                PivotBucket("${date.year}", "${date.year}", "%04d".format(date.year))
            DateGroupUnit.Quarter -> {
                val quarter = ((date.monthValue - 1) / 3) + 1
                PivotBucket(
                    "${date.year}-Q$quarter",
                    "Q$quarter ${date.year}",
                    "%04d-%d".format(date.year, quarter),
                )
            }
            DateGroupUnit.Month ->
                PivotBucket(
                    "%04d-%02d".format(date.year, date.monthValue),
                    date.format(DateTimeFormatter.ofPattern("MMM yyyy")),
                    "%04d-%02d".format(date.year, date.monthValue),
                )
            DateGroupUnit.IsoWeek -> {
                val weekFields = WeekFields.ISO
                val weekYear = date.get(weekFields.weekBasedYear())
                val week = date.get(weekFields.weekOfWeekBasedYear())
                PivotBucket(
                    "$weekYear-W$week",
                    "%04d-W%02d".format(weekYear, week),
                    "%04d-%02d".format(weekYear, week),
                )
            }
            DateGroupUnit.Day -> PivotBucket(date.toString(), date.toString(), date.toString())
        }
    }

    private fun numberBucket(
        cell: ResultCell,
        dimension: PivotDimension,
        grouping: PivotGrouping.NumberBin,
    ): PivotBucket {
        val value = cell.toDecimalOrNull()
        val size = grouping.size.toBigDecimalOrNull()
        val start = grouping.start?.toBigDecimalOrNull() ?: BigDecimal.ZERO
        if (value == null || size == null || size <= BigDecimal.ZERO) {
            warnings += "${dimension.label} needs a positive numeric bin size and numeric values"
            return PivotBucket(pivotCellKey(cell), cellText(cell), cellText(cell))
        }
        val bucketIndex = value.subtract(start).divide(size, 0, RoundingMode.FLOOR)
        val lower = start.add(bucketIndex.multiply(size)).stripTrailingZeros()
        val upper = lower.add(size).stripTrailingZeros()
        val label = "${lower.toPlainString()} – ${upper.toPlainString()}"
        return PivotBucket(
            "${lower.toPlainString()}:${size.toPlainString()}",
            label,
            lower.toPlainString().padStart(32, '0'),
        )
    }

    private fun buildTree(
        records: List<PivotRecord>,
        dimensions: List<PivotDimension>,
        filteredRows: BitSet,
        buckets: (PivotRecord) -> List<PivotBucket>,
    ): AxisNode {
        val root =
            AxisNode(
                null,
                depth = 0,
                pathKey = "",
                parent = null,
                rows = filteredRows.clone() as BitSet,
            )
        for (record in records) {
            var node = root
            buckets(record).forEachIndexed { index, bucket ->
                val path =
                    if (node.pathKey.isEmpty()) escapePath(bucket.key)
                    else "${node.pathKey}/${escapePath(bucket.key)}"
                node =
                    node.children.getOrPut(bucket.key) {
                        AxisNode(bucket, depth = index + 1, pathKey = path, parent = node)
                    }
                node.rows.set(record.index)
            }
        }
        if (dimensions.isEmpty()) root.rows.or(filteredRows)
        return root
    }

    private fun buildRowSlices(root: AxisNode): List<RowSlice> {
        if (rowDimensions.isEmpty())
            return listOf(
                RowSlice(root, "", PivotRowKind.Leaf, hasChildren = false, expanded = true)
            )
        val out = mutableListOf<RowSlice>()
        orderedChildren(root, rowDimensions.first()).forEach { flattenRowNode(it, out) }
        if (config.showColumnTotals && root.rows.cardinality() > 0) {
            out +=
                RowSlice(
                    root,
                    "Total",
                    PivotRowKind.GrandTotal,
                    hasChildren = false,
                    expanded = true,
                )
        }
        return out
    }

    private fun flattenRowNode(node: AxisNode, out: MutableList<RowSlice>) {
        val hasChildren = node.children.isNotEmpty()
        val collapsed = node.pathKey in config.collapsedRowPaths
        if (!hasChildren) {
            out +=
                RowSlice(
                    node,
                    node.bucket?.label.orEmpty(),
                    PivotRowKind.Leaf,
                    hasChildren = false,
                    expanded = true,
                )
            return
        }
        val dimension = rowDimensions.getOrNull(node.depth - 1)
        val showSubtotal = config.showSubtotals && dimension?.showSubtotals != false
        if (collapsed) {
            out +=
                RowSlice(
                    node,
                    node.bucket?.label.orEmpty(),
                    PivotRowKind.Subtotal,
                    hasChildren = true,
                    expanded = false,
                )
            return
        }
        out +=
            if (config.subtotalPosition == SubtotalPosition.Top && showSubtotal) {
                RowSlice(
                    node,
                    "${node.bucket?.label.orEmpty()} total",
                    PivotRowKind.Subtotal,
                    hasChildren = true,
                    expanded = true,
                )
            } else {
                RowSlice(
                    node,
                    node.bucket?.label.orEmpty(),
                    PivotRowKind.Group,
                    hasChildren = true,
                    expanded = true,
                )
            }
        val childDimension = rowDimensions.getOrNull(node.depth)
        val children =
            if (childDimension == null) node.children.values.toList()
            else orderedChildren(node, childDimension)
        children.forEach { flattenRowNode(it, out) }
        if (config.subtotalPosition == SubtotalPosition.Bottom && showSubtotal) {
            out +=
                RowSlice(
                    node,
                    "${node.bucket?.label.orEmpty()} total",
                    PivotRowKind.Subtotal,
                    hasChildren = false,
                    expanded = true,
                )
        }
    }

    private fun buildColumnSlices(root: AxisNode): List<ColumnSlice> {
        if (columnDimensions.isEmpty())
            return listOf(ColumnSlice(root, emptyList(), isSubtotal = false, isGrandTotal = false))
        val out = mutableListOf<ColumnSlice>()
        orderedChildren(root, columnDimensions.first()).forEach { flattenColumnNode(it, out) }
        if (config.showRowTotals)
            out += ColumnSlice(root, listOf("Total"), isSubtotal = false, isGrandTotal = true)
        return out
    }

    private fun flattenColumnNode(node: AxisNode, out: MutableList<ColumnSlice>) {
        val labels = node.labels()
        val hasChildren = node.children.isNotEmpty()
        val collapsed = node.pathKey in config.collapsedColumnPaths
        if (!hasChildren || collapsed) {
            out += ColumnSlice(node, labels, isSubtotal = collapsed, isGrandTotal = false)
            return
        }
        val childDimension = columnDimensions.getOrNull(node.depth)
        val children =
            if (childDimension == null) node.children.values.toList()
            else orderedChildren(node, childDimension)
        children.forEach { flattenColumnNode(it, out) }
        val dimension = columnDimensions.getOrNull(node.depth - 1)
        if (config.showSubtotals && dimension?.showSubtotals != false) {
            out +=
                ColumnSlice(
                    node,
                    labels.dropLast(1) + "${labels.last()} total",
                    isSubtotal = true,
                    isGrandTotal = false,
                )
        }
    }

    private fun orderedChildren(node: AxisNode, dimension: PivotDimension): List<AxisNode> {
        var children = node.children.values.toList()
        config.filters
            .filterIsInstance<PivotFilter.Value>()
            .filter { it.column == dimension.column }
            .forEach { filter ->
                val scored = children.map {
                    it to aggregateDecimal(it.rows, activeRows, filter.measureAlias)
                }
                children =
                    when (filter.op) {
                        ValueFilterOp.Top ->
                            scored
                                .sortedByDescending { it.second }
                                .take(filter.count.coerceAtLeast(1))
                                .map { it.first }
                        ValueFilterOp.Bottom ->
                            scored
                                .sortedBy { it.second }
                                .take(filter.count.coerceAtLeast(1))
                                .map { it.first }
                        else ->
                            scored
                                .filter { (_, value) -> passesValueComparison(value, filter) }
                                .map { it.first }
                    }
            }
        val sort = config.sort
        var descending = sort?.dir == SortDir.Desc
        val comparator =
            when (val target = sort?.target) {
                is ExploreSortTarget.Dimension ->
                    if (target.column == dimension.column) {
                        compareBy { it.bucket?.sortKey.orEmpty() }
                    } else {
                        dimensionComparator(dimension).also {
                            descending =
                                dimension.sortMode == DimensionSortMode.LabelDescending ||
                                    dimension.sortMode == DimensionSortMode.ValueDescending
                        }
                    }
                is ExploreSortTarget.Measure ->
                    Comparator { left, right ->
                        aggregateDecimal(left.rows, activeRows, target.alias)
                            .compareTo(aggregateDecimal(right.rows, activeRows, target.alias))
                    }
                null ->
                    dimensionComparator(dimension).also {
                        descending =
                            dimension.sortMode == DimensionSortMode.LabelDescending ||
                                dimension.sortMode == DimensionSortMode.ValueDescending
                    }
            }
        if (comparator == null) return children
        return if (descending) children.sortedWith(comparator.reversed())
        else children.sortedWith(comparator)
    }

    private fun dimensionComparator(dimension: PivotDimension): Comparator<AxisNode>? =
        when (dimension.sortMode) {
            DimensionSortMode.SourceOrder ->
                if (dimension.grouping == PivotGrouping.Exact) null
                else compareBy { it.bucket?.sortKey.orEmpty() }
            DimensionSortMode.LabelAscending,
            DimensionSortMode.LabelDescending -> compareBy { it.bucket?.sortKey.orEmpty() }
            DimensionSortMode.ValueAscending,
            DimensionSortMode.ValueDescending ->
                dimension.sortMeasureAlias?.let { alias ->
                    Comparator { left, right ->
                        aggregateDecimal(left.rows, activeRows, alias)
                            .compareTo(aggregateDecimal(right.rows, activeRows, alias))
                    }
                }
        }

    private fun passesValueComparison(value: BigDecimal, filter: PivotFilter.Value): Boolean {
        val first = filter.value.toBigDecimalOrNull() ?: return true
        return when (filter.op) {
            ValueFilterOp.GreaterThan -> value > first
            ValueFilterOp.GreaterThanOrEqual -> value >= first
            ValueFilterOp.LessThan -> value < first
            ValueFilterOp.LessThanOrEqual -> value <= first
            ValueFilterOp.Between ->
                value >= first && value <= (filter.secondValue?.toBigDecimalOrNull() ?: first)
            ValueFilterOp.Top,
            ValueFilterOp.Bottom -> true
        }
    }

    private fun transformedValue(
        rowNode: AxisNode,
        columnNode: AxisNode,
        measure: PivotMeasure,
    ): ResultCell {
        val raw = aggregateValues(rowNode.rows, columnNode.rows)[measure.alias] ?: ResultCell.Null
        val rawDecimal = raw.toDecimalOrNull()
        if (measure.showAs.mode == ShowAsMode.Value || rawDecimal == null) return raw
        val denominator: BigDecimal?
        val value =
            when (measure.showAs.mode) {
                ShowAsMode.Value -> rawDecimal
                ShowAsMode.PercentGrandTotal -> {
                    denominator = aggregateDecimal(activeRows, activeRows, measure.alias)
                    ratio(rawDecimal, denominator)
                }
                ShowAsMode.PercentRowTotal -> {
                    denominator = aggregateDecimal(rowNode.rows, activeRows, measure.alias)
                    ratio(rawDecimal, denominator)
                }
                ShowAsMode.PercentColumnTotal -> {
                    denominator = aggregateDecimal(activeRows, columnNode.rows, measure.alias)
                    ratio(rawDecimal, denominator)
                }
                ShowAsMode.PercentParent -> {
                    val baseIsColumn = columnDimensions.any {
                        it.id == measure.showAs.baseDimensionId
                    }
                    denominator =
                        if (baseIsColumn) {
                            aggregateDecimal(
                                rowNode.rows,
                                columnNode.parent?.rows ?: activeRows,
                                measure.alias,
                            )
                        } else {
                            aggregateDecimal(
                                rowNode.parent?.rows ?: activeRows,
                                columnNode.rows,
                                measure.alias,
                            )
                        }
                    ratio(rawDecimal, denominator)
                }
                ShowAsMode.DifferenceFrom,
                ShowAsMode.PercentDifferenceFrom,
                ShowAsMode.RunningTotal,
                ShowAsMode.PercentRunningTotal,
                ShowAsMode.RankAscending,
                ShowAsMode.RankDescending ->
                    siblingTransform(rowNode, columnNode, measure, rawDecimal)
            }
        return value?.toPivotResultCell() ?: ResultCell.Null
    }

    private fun siblingTransform(
        rowNode: AxisNode,
        columnNode: AxisNode,
        measure: PivotMeasure,
        raw: BigDecimal,
    ): BigDecimal? {
        val useColumns = columnDimensions.any { it.id == measure.showAs.baseDimensionId }
        val node = if (useColumns) columnNode else rowNode
        val parent = node.parent ?: return null
        val dimensions = if (useColumns) columnDimensions else rowDimensions
        val dimension = dimensions.getOrNull(node.depth - 1)
        val siblings =
            if (dimension == null) {
                parent.children.values.toList()
            } else {
                orderedChildren(parent, dimension)
            }
        val index = siblings.indexOf(node)
        if (index < 0) return null
        fun siblingValue(sibling: AxisNode): BigDecimal =
            if (useColumns) {
                aggregateDecimal(rowNode.rows, sibling.rows, measure.alias)
            } else {
                aggregateDecimal(sibling.rows, columnNode.rows, measure.alias)
            }
        return when (measure.showAs.mode) {
            ShowAsMode.DifferenceFrom -> {
                val base =
                    measure.showAs.baseItemKey?.let { key ->
                        siblings.firstOrNull { it.bucket?.key == key }
                    } ?: siblings.getOrNull(index - 1) ?: return null
                raw.subtract(siblingValue(base))
            }
            ShowAsMode.PercentDifferenceFrom -> {
                val base =
                    measure.showAs.baseItemKey?.let { key ->
                        siblings.firstOrNull { it.bucket?.key == key }
                    } ?: siblings.getOrNull(index - 1) ?: return null
                val baseValue = siblingValue(base)
                ratio(raw.subtract(baseValue), baseValue)
            }
            ShowAsMode.RunningTotal ->
                siblings.take(index + 1).fold(BigDecimal.ZERO) { total, sibling ->
                    total + siblingValue(sibling)
                }
            ShowAsMode.PercentRunningTotal -> {
                val running =
                    siblings.take(index + 1).fold(BigDecimal.ZERO) { total, sibling ->
                        total + siblingValue(sibling)
                    }
                val total =
                    siblings.fold(BigDecimal.ZERO) { sum, sibling -> sum + siblingValue(sibling) }
                ratio(running, total)
            }
            ShowAsMode.RankAscending,
            ShowAsMode.RankDescending -> {
                val sorted =
                    if (measure.showAs.mode == ShowAsMode.RankAscending) {
                        siblings.sortedBy(::siblingValue)
                    } else {
                        siblings.sortedByDescending(::siblingValue)
                    }
                BigDecimal(sorted.indexOf(node) + 1)
            }
            else -> raw
        }
    }

    private fun aggregateDecimal(rows: BitSet, columns: BitSet, alias: String): BigDecimal =
        aggregateValues(rows, columns)[alias]?.toDecimalOrNull() ?: BigDecimal.ZERO

    private fun aggregateValues(rowBits: BitSet, columnBits: BitSet): Map<String, ResultCell> {
        val matching = intersect(rowBits, columnBits)
        val cacheKey = matching.clone() as BitSet
        return aggregateCache.getOrPut(cacheKey) {
            val rowIndexes = matching.toIndexList()
            val resolved = linkedMapOf<String, ResultCell>()
            val resolving = mutableSetOf<String>()

            fun resolve(measure: PivotMeasure): ResultCell {
                resolved[measure.alias]?.let {
                    return it
                }
                if (!resolving.add(measure.alias)) {
                    warnings += "Calculated measure '${measure.label}' contains a cycle"
                    return ResultCell.Null
                }
                val value =
                    if (measure.formula.isNullOrBlank()) {
                        computeMeasure(rowIndexes, measure)
                    } else {
                        val references = measureReferences(measure.formula)
                        val values = references.associateWith { alias ->
                            val dependency = measures.firstOrNull { it.alias == alias }
                            if (dependency == null) {
                                warnings += "${measure.label}: unknown measure '$alias'"
                                null
                            } else {
                                resolve(dependency).toDecimalOrNull()
                            }
                        }
                        val result = evaluatePivotFormula(measure.formula, values)
                        result.error?.let { warnings += "${measure.label}: $it" }
                        result.value?.toPivotResultCell() ?: ResultCell.Null
                    }
                resolving.remove(measure.alias)
                resolved[measure.alias] = value
                return value
            }
            measures.forEach(::resolve)
            resolved
        }
    }

    private fun computeMeasure(rowIndexes: List<Int>, measure: PivotMeasure): ResultCell {
        val index = measure.sourceColumn?.let(indexes::get)
        val rows = rowIndexes.map(sample.rows::get)
        return when (measure.fn) {
            MeasureFn.Count ->
                ResultCell.IntegerCell(
                    if (index == null) rows.size.toLong()
                    else rows.count { it.getOrNull(index) !is ResultCell.Null }.toLong()
                )
            MeasureFn.CountNumbers ->
                ResultCell.IntegerCell(decimalCells(rows, index, measure).size.toLong())
            MeasureFn.CountDistinct -> {
                if (index == null) ResultCell.IntegerCell(0)
                else
                    ResultCell.IntegerCell(
                        rows
                            .mapNotNull { row ->
                                row.getOrNull(index)
                                    ?.takeUnless { it is ResultCell.Null }
                                    ?.let(::pivotCellKey)
                            }
                            .distinct()
                            .size
                            .toLong()
                    )
            }
            MeasureFn.Sum ->
                decimalCells(rows, index, measure)
                    .fold(BigDecimal.ZERO, BigDecimal::add)
                    .toPivotResultCell()
            MeasureFn.Avg ->
                decimalCells(rows, index, measure).average()?.toPivotResultCell() ?: ResultCell.Null
            MeasureFn.Min ->
                comparableCells(rows, index).minWithOrNull(::comparePivotCells) ?: ResultCell.Null
            MeasureFn.Max ->
                comparableCells(rows, index).maxWithOrNull(::comparePivotCells) ?: ResultCell.Null
            MeasureFn.Product ->
                decimalCells(rows, index, measure)
                    .takeIf { it.isNotEmpty() }
                    ?.fold(BigDecimal.ONE, BigDecimal::multiply)
                    ?.toPivotResultCell() ?: ResultCell.Null
            MeasureFn.StdDev ->
                statistic(decimalCells(rows, index, measure), sample = true, squareRoot = true)
            MeasureFn.StdDevPopulation ->
                statistic(decimalCells(rows, index, measure), sample = false, squareRoot = true)
            MeasureFn.Variance ->
                statistic(decimalCells(rows, index, measure), sample = true, squareRoot = false)
            MeasureFn.VariancePopulation ->
                statistic(decimalCells(rows, index, measure), sample = false, squareRoot = false)
        }
    }

    private fun decimalCells(
        rows: List<List<ResultCell>>,
        index: Int?,
        measure: PivotMeasure,
    ): List<BigDecimal> {
        if (index == null) return emptyList()
        var skipped = 0
        val values = rows.mapNotNull { row ->
            val value = row.getOrNull(index)
            val decimal = value?.toDecimalOrNull()
            if (decimal == null && value != null && value !is ResultCell.Null) skipped++
            decimal
        }
        if (skipped > 0)
            warnings +=
                "Measure '${measure.label}' skipped $skipped non-numeric cell${if (skipped == 1) "" else "s"}"
        return values
    }

    private fun statistic(
        values: List<BigDecimal>,
        sample: Boolean,
        squareRoot: Boolean,
    ): ResultCell {
        if (values.isEmpty() || sample && values.size < 2) return ResultCell.Null
        val doubles = values.map(BigDecimal::toDouble)
        val mean = doubles.average()
        val denominator = if (sample) doubles.size - 1 else doubles.size
        val variance = doubles.sumOf { (it - mean) * (it - mean) } / denominator
        return ResultCell.FloatCell(if (squareRoot) sqrt(variance) else variance)
    }

    private fun buildOutputColumns(
        includeRowHeader: Boolean,
        columns: List<ColumnSlice>,
    ): List<ResultColumn> = buildList {
        if (includeRowHeader)
            add(ResultColumn(rowDimensions.firstOrNull()?.label ?: "Row labels", "text"))
        for ((_, labels, _, _) in columns) {
            for (measure in measures) {
                val prefix = labels.joinToString(" / ")
                add(
                    ResultColumn(
                        if (prefix.isEmpty()) measure.label else "$prefix ${measure.label}",
                        resultType(measure),
                    )
                )
            }
        }
    }

    private fun buildColumnHeaders(columns: List<ColumnSlice>): List<List<PivotHeaderCell>> {
        if (columnDimensions.isEmpty()) return emptyList()
        return columnDimensions.indices.map { depth ->
            val cells = mutableListOf<PivotHeaderCell>()
            var start = 0
            while (start < columns.size) {
                val column = columns[start]
                val label = column.labels.getOrNull(depth).orEmpty()
                val prefix = column.labels.take(depth + 1)
                var end = start + 1
                while (end < columns.size && columns[end].labels.take(depth + 1) == prefix) end++
                val node = column.node.ancestorAtDepth(depth + 1)
                cells +=
                    PivotHeaderCell(
                        pathKey = node?.pathKey ?: column.pathKey,
                        label = label,
                        startLeafIndex = start,
                        leafSpan = end - start,
                        depth = depth,
                        hasChildren = node?.children?.isNotEmpty() == true,
                        expanded = node?.pathKey !in config.collapsedColumnPaths,
                        isTotal = column.isGrandTotal || column.isSubtotal,
                    )
                start = end
            }
            cells
        }
    }

    private fun formatPivotCell(cell: ResultCell, measure: PivotMeasure): String {
        if (cell is ResultCell.Null) return ""
        val decimal = cell.toDecimalOrNull() ?: return cellText(cell)
        val configured = measure.numberFormat
        val kind =
            if (
                configured.kind == NumberFormatKind.Auto &&
                    measure.showAs.mode.name.startsWith("Percent")
            ) {
                NumberFormatKind.Percent
            } else {
                configured.kind
            }
        if (kind == NumberFormatKind.Auto) return decimal.stripTrailingZeros().toPlainString()
        val decimals = configured.decimals.coerceIn(0, 8)
        val symbols = DecimalFormatSymbols.getInstance(Locale.getDefault())
        val pattern = buildString {
            append(if (configured.thousandsSeparator) "#,##0" else "0")
            if (decimals > 0) append('.').append("0".repeat(decimals))
        }
        return when (kind) {
            NumberFormatKind.Number -> DecimalFormat(pattern, symbols).format(decimal)
            NumberFormatKind.Percent -> DecimalFormat("$pattern%", symbols).format(decimal)
            NumberFormatKind.Currency -> {
                val currency = runCatching {
                    Currency.getInstance(configured.currencyCode)
                }
                    .getOrNull()
                val formatter = DecimalFormat("¤$pattern", symbols)
                currency?.let { formatter.currency = it }
                formatter.format(decimal)
            }
            NumberFormatKind.Scientific ->
                DecimalFormat("0.${"0".repeat(decimals)}E0", symbols).format(decimal)
        }
    }
}

fun pivotCellKey(cell: ResultCell?): String =
    when (cell) {
        null,
        is ResultCell.Null -> "<null>"
        else -> "${cell::class.simpleName}:${cellText(cell)}"
    }

fun pivotCellLineageKey(rowPath: String, columnPath: String, measureAlias: String): String =
    cellKey(rowPath, columnPath, measureAlias)
