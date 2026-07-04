package com.safedb.explore

import com.safedb.model.QueryResult
import com.safedb.model.ResultCell
import com.safedb.model.ResultColumn
import java.math.BigDecimal
import java.math.RoundingMode

fun applyExplore(sample: QueryResult, config: ExploreConfig): ExplorePreviewResult {
    val indexes = sample.columns.mapIndexed { index, column -> column.name to index }.toMap()
    val warnings = linkedSetOf<String>()
    val rowDimensions = config.rowDimensions.filterKnown(indexes, warnings, "row")
    val columnDimension = config.columnDimension?.takeIfKnown(indexes, warnings)
    val measures = config.measures.ifEmpty { listOf(PivotMeasure.countRows()) }
        .filterValid(indexes, warnings)
        .ifEmpty { listOf(PivotMeasure.countRows()) }

    val rowKeys = linkedSetOf<List<String>>()
    val columnKeys = linkedSetOf<String>()
    val buckets = linkedMapOf<List<String>, MutableMap<String?, MutableList<List<ResultCell>>>>()

    for (row in sample.rows) {
        val rowKey = rowDimensions.map { dimension ->
            bucketValue(row.getOrNull(indexes.getValue(dimension.column)), config.nullBucketLabel)
        }
        val columnKey = columnDimension?.let { dimension ->
            bucketValue(row.getOrNull(indexes.getValue(dimension.column)), config.nullBucketLabel)
        }
        rowKeys.add(rowKey)
        if (columnKey != null) columnKeys.add(columnKey)
        val byColumn = buckets.getOrPut(rowKey) { linkedMapOf() }
        byColumn.getOrPut(columnKey) { mutableListOf() }.add(row)
    }

    if (sample.rows.isEmpty()) {
        val columns = buildOutputColumns(rowDimensions, columnDimension, columnKeys.toList(), measures, config)
        return ExplorePreviewResult(QueryResult(columns, emptyList(), 0, false, sample.warnings), warnings.toList())
    }

    val orderedColumnKeys = columnKeys.toList().sorted()
    val rows = rowKeys.map { rowKey ->
        val byColumn = buckets[rowKey].orEmpty()
        PivotOutputRow(
            rowKey = rowKey,
            cells = buildCellsForRow(
                rowKey = rowKey,
                byColumn = byColumn,
                columnKeys = orderedColumnKeys,
                columnDimension = columnDimension,
                measures = measures,
                indexes = indexes,
                config = config,
                warnings = warnings,
            ),
            sortValues = measureSortValues(
                allRows = byColumn.values.flatten(),
                measures = measures,
                indexes = indexes,
                warnings = warnings,
            ),
        )
    }.let { sortRows(it, config, rowDimensions, measures) }
        .map { it.cells }
        .toMutableList()

    if (config.showColumnTotals && rowDimensions.isNotEmpty()) {
        rows.add(
            buildGrandTotalRow(
                rowDimensions = rowDimensions,
                allRows = sample.rows,
                columnKeys = orderedColumnKeys,
                columnDimension = columnDimension,
                measures = measures,
                indexes = indexes,
                config = config,
                warnings = warnings,
            ),
        )
    }

    val columns = buildOutputColumns(rowDimensions, columnDimension, orderedColumnKeys, measures, config)
    val resultWarnings = (sample.warnings + warnings).distinct()
    return ExplorePreviewResult(
        result = QueryResult(
            columns = columns,
            rows = rows,
            rowCount = rows.size,
            truncated = sample.truncated,
            warnings = resultWarnings,
        ),
        warnings = warnings.toList(),
    )
}

private data class PivotOutputRow(
    val rowKey: List<String>,
    val cells: List<ResultCell>,
    val sortValues: Map<String, ResultCell>,
)

private fun List<PivotDimension>.filterKnown(
    indexes: Map<String, Int>,
    warnings: MutableSet<String>,
    kind: String,
): List<PivotDimension> = filter { dimension ->
    val known = indexes.containsKey(dimension.column)
    if (!known) warnings.add("Unknown $kind dimension '${displayColumnLabel(dimension.column)}' was ignored")
    known
}

private fun PivotDimension.takeIfKnown(
    indexes: Map<String, Int>,
    warnings: MutableSet<String>,
): PivotDimension? {
    if (indexes.containsKey(column)) return this
    warnings.add("Unknown column dimension '${displayColumnLabel(column)}' was ignored")
    return null
}

private fun List<PivotMeasure>.filterValid(
    indexes: Map<String, Int>,
    warnings: MutableSet<String>,
): List<PivotMeasure> = filter { measure ->
    val valid = measure.fn == MeasureFn.Count || measure.sourceColumn?.let(indexes::containsKey) == true
    if (!valid) warnings.add("Measure '${measure.label}' references an unknown source column")
    valid
}

private fun buildOutputColumns(
    rowDimensions: List<PivotDimension>,
    columnDimension: PivotDimension?,
    columnKeys: List<String>,
    measures: List<PivotMeasure>,
    config: ExploreConfig,
): List<ResultColumn> = buildList {
    rowDimensions.forEach { add(ResultColumn(it.label, "text")) }
    if (columnDimension == null) {
        measures.forEach { add(ResultColumn(it.label, resultType(it.fn))) }
    } else {
        for (columnKey in columnKeys) {
            for (measure in measures) {
                add(ResultColumn("$columnKey ${measure.label}", resultType(measure.fn)))
            }
        }
        if (config.showRowTotals) {
            measures.forEach { add(ResultColumn("Total ${it.label}", resultType(it.fn))) }
        }
    }
}

private fun buildCellsForRow(
    rowKey: List<String>,
    byColumn: Map<String?, List<List<ResultCell>>>,
    columnKeys: List<String>,
    columnDimension: PivotDimension?,
    measures: List<PivotMeasure>,
    indexes: Map<String, Int>,
    config: ExploreConfig,
    warnings: MutableSet<String>,
): List<ResultCell> = buildList {
    rowKey.forEach { add(ResultCell.text(it)) }
    if (columnDimension == null) {
        val rows = byColumn[null].orEmpty()
        measures.forEach { add(computeMeasure(rows, it, indexes, warnings)) }
    } else {
        for (columnKey in columnKeys) {
            val rows = byColumn[columnKey].orEmpty()
            measures.forEach { add(computeMeasure(rows, it, indexes, warnings)) }
        }
        if (config.showRowTotals) {
            val rows = byColumn.values.flatten()
            measures.forEach { add(computeMeasure(rows, it, indexes, warnings)) }
        }
    }
}

private fun buildGrandTotalRow(
    rowDimensions: List<PivotDimension>,
    allRows: List<List<ResultCell>>,
    columnKeys: List<String>,
    columnDimension: PivotDimension?,
    measures: List<PivotMeasure>,
    indexes: Map<String, Int>,
    config: ExploreConfig,
    warnings: MutableSet<String>,
): List<ResultCell> = buildList {
    rowDimensions.forEachIndexed { index, _ ->
        add(ResultCell.text(if (index == 0) "Total" else ""))
    }
    if (columnDimension == null) {
        measures.forEach { add(computeMeasure(allRows, it, indexes, warnings)) }
    } else {
        val columnIndex = indexes.getValue(columnDimension.column)
        for (columnKey in columnKeys) {
            val rows = allRows.filter {
                bucketValue(it.getOrNull(columnIndex), config.nullBucketLabel) == columnKey
            }
            measures.forEach { add(computeMeasure(rows, it, indexes, warnings)) }
        }
        if (config.showRowTotals) {
            measures.forEach { add(computeMeasure(allRows, it, indexes, warnings)) }
        }
    }
}

private fun measureSortValues(
    allRows: List<List<ResultCell>>,
    measures: List<PivotMeasure>,
    indexes: Map<String, Int>,
    warnings: MutableSet<String>,
): Map<String, ResultCell> = measures.associate { measure ->
    measure.alias to computeMeasure(allRows, measure, indexes, warnings)
}

private fun sortRows(
    rows: List<PivotOutputRow>,
    config: ExploreConfig,
    rowDimensions: List<PivotDimension>,
    measures: List<PivotMeasure>,
): List<PivotOutputRow> {
    val sort = config.sort ?: return rows
    val comparator = when (val target = sort.target) {
        is ExploreSortTarget.Dimension -> {
            val index = rowDimensions.indexOfFirst { it.column == target.column }
            if (index < 0) return rows
            compareBy<PivotOutputRow> { it.rowKey.getOrNull(index).orEmpty() }
        }
        is ExploreSortTarget.Measure -> {
            if (measures.none { it.alias == target.alias }) return rows
            Comparator { left, right ->
                compareCells(left.sortValues[target.alias], right.sortValues[target.alias])
            }
        }
    }
    return if (sort.dir == SortDir.Desc) rows.sortedWith(comparator.reversed()) else rows.sortedWith(comparator)
}

private fun computeMeasure(
    rows: List<List<ResultCell>>,
    measure: PivotMeasure,
    indexes: Map<String, Int>,
    warnings: MutableSet<String>,
): ResultCell {
    val index = measure.sourceColumn?.let(indexes::get)
    return when (measure.fn) {
        MeasureFn.Count -> ResultCell.IntegerCell(
            if (index == null) {
                rows.size.toLong()
            } else {
                rows.count { it.getOrNull(index) !is ResultCell.Null }.toLong()
            },
        )
        MeasureFn.CountDistinct -> {
            if (index == null) return ResultCell.IntegerCell(0)
            ResultCell.IntegerCell(rows.map { stableCellKey(it.getOrNull(index)) }.distinct().count().toLong())
        }
        MeasureFn.Sum -> decimalCells(rows, index, measure, warnings)
            .fold(BigDecimal.ZERO, BigDecimal::add)
            .toResultCell()
        MeasureFn.Avg -> {
            val values = decimalCells(rows, index, measure, warnings)
            if (values.isEmpty()) ResultCell.Null else values
                .fold(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal(values.size), 6, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toResultCell()
        }
        MeasureFn.Min -> comparableCells(rows, index)
            .minWithOrNull(::compareCells)
            ?: ResultCell.Null
        MeasureFn.Max -> comparableCells(rows, index)
            .maxWithOrNull(::compareCells)
            ?: ResultCell.Null
    }
}

private fun decimalCells(
    rows: List<List<ResultCell>>,
    index: Int?,
    measure: PivotMeasure,
    warnings: MutableSet<String>,
): List<BigDecimal> {
    if (index == null) {
        warnings.add("Measure '${measure.label}' needs a source column")
        return emptyList()
    }
    var skipped = 0
    val values = rows.mapNotNull { row ->
        val value = row.getOrNull(index)
        val decimal = value?.toDecimalOrNull()
        if (decimal == null && value != null && value !is ResultCell.Null) skipped++
        decimal
    }
    if (skipped > 0) {
        warnings.add("Measure '${measure.label}' skipped $skipped non-numeric cell${if (skipped == 1) "" else "s"}")
    }
    return values
}

private fun comparableCells(rows: List<List<ResultCell>>, index: Int?): List<ResultCell> {
    if (index == null) return emptyList()
    return rows.mapNotNull { it.getOrNull(index) }.filterNot { it is ResultCell.Null }
}

private fun ResultCell.toDecimalOrNull(): BigDecimal? = when (this) {
    is ResultCell.IntegerCell -> BigDecimal(value)
    is ResultCell.FloatCell -> BigDecimal.valueOf(value)
    is ResultCell.TextCell -> value.text.trim().takeIf { it.isNotEmpty() }?.toBigDecimalOrNull()
    else -> null
}

private fun BigDecimal.toResultCell(): ResultCell {
    val normalized = stripTrailingZeros()
    return runCatching { ResultCell.IntegerCell(normalized.longValueExact()) }
        .getOrElse { ResultCell.FloatCell(normalized.toDouble()) }
}

private fun bucketValue(cell: ResultCell?, nullBucketLabel: String): String =
    if (cell == null || cell is ResultCell.Null) {
        nullBucketLabel
    } else {
        cellText(cell)
    }

private fun cellText(cell: ResultCell): String = when (cell) {
    is ResultCell.Null -> ""
    is ResultCell.BoolCell -> cell.value.toString()
    is ResultCell.IntegerCell -> cell.value.toString()
    is ResultCell.FloatCell -> cell.value.toString()
    is ResultCell.TextCell -> cell.value.text
    is ResultCell.BinaryCell -> cell.value.base64
}

private fun stableCellKey(cell: ResultCell?): String = when (cell) {
    null, is ResultCell.Null -> "<null>"
    else -> "${cell::class.qualifiedName}:${cellText(cell)}"
}

private fun compareCells(left: ResultCell?, right: ResultCell?): Int {
    if (left == null || left is ResultCell.Null) return if (right == null || right is ResultCell.Null) 0 else 1
    if (right == null || right is ResultCell.Null) return -1
    val leftDecimal = left.toDecimalOrNull()
    val rightDecimal = right.toDecimalOrNull()
    if (leftDecimal != null && rightDecimal != null) return leftDecimal.compareTo(rightDecimal)
    return cellText(left).compareTo(cellText(right))
}

private fun resultType(fn: MeasureFn): String = when (fn) {
    MeasureFn.Count, MeasureFn.CountDistinct -> "bigint"
    MeasureFn.Sum, MeasureFn.Avg -> "decimal"
    MeasureFn.Min, MeasureFn.Max -> "text"
}
