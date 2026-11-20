package com.safedb.explore

import com.safedb.model.QueryResult
import com.safedb.model.ResultCell

internal fun sampleColumnIndexes(sample: QueryResult): Map<String, Int> =
    sample.columns.mapIndexed { index, column -> column.name to index }.toMap()

internal class SampleIndex(val sample: QueryResult) {
    val indexes: Map<String, Int> = sampleColumnIndexes(sample)

    fun columnIndex(column: String): Int? = indexes[column]

    operator fun contains(column: String): Boolean = column in indexes

    fun cellOrNull(row: List<ResultCell>, column: String): ResultCell? =
        indexes[column]?.let(row::getOrNull)

    fun cell(row: List<ResultCell>, column: String): ResultCell =
        cellOrNull(row, column) ?: ResultCell.Null
}

internal fun matchesMembers(cell: ResultCell?, includedKeys: Set<String>): Boolean =
    includedKeys.isEmpty() || pivotCellMatches(cell, includedKeys)

internal fun matchesLabelOp(text: String, op: LabelFilterOp, value: String): Boolean =
    when (op) {
        LabelFilterOp.Equals -> text.equals(value, ignoreCase = true)
        LabelFilterOp.Contains -> text.contains(value, ignoreCase = true)
        LabelFilterOp.StartsWith -> text.startsWith(value, ignoreCase = true)
        LabelFilterOp.EndsWith -> text.endsWith(value, ignoreCase = true)
    }

internal fun matchesPivotLabelFilter(cell: ResultCell?, filter: PivotFilter.Label): Boolean =
    matchesLabelOp(resultCellText(cell), filter.op, filter.value)

internal fun passesPivotFilters(
    row: List<ResultCell>,
    filters: List<PivotFilter>,
    sampleIndex: SampleIndex,
): Boolean = filters.all { filter ->
    when (filter) {
        is PivotFilter.Members ->
            matchesMembers(sampleIndex.cellOrNull(row, filter.column), filter.includedKeys)
        is PivotFilter.Label ->
            matchesPivotLabelFilter(sampleIndex.cellOrNull(row, filter.column), filter)
        is PivotFilter.Value -> true
    }
}
