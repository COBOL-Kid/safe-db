package com.safedb.explore

import com.safedb.model.ResultCell
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.util.BitSet

internal fun cellKey(rowPath: String, columnPath: String, measureAlias: String): String =
    "$rowPath|$columnPath|$measureAlias"

internal fun List<PivotDimension>.filterKnown(
    indexes: Map<String, Int>,
    warnings: MutableSet<String>,
    kind: String,
): List<PivotDimension> = filter { dimension ->
    val known = indexes.containsKey(dimension.column)
    if (!known)
        warnings += "Unknown $kind dimension '${displayColumnLabel(dimension.column)}' was ignored"
    known
}

internal fun List<PivotMeasure>.filterValid(
    indexes: Map<String, Int>,
    warnings: MutableSet<String>,
): List<PivotMeasure> = filter { measure ->
    val valid =
        !measure.formula.isNullOrBlank() ||
            measure.fn == MeasureFn.Count ||
            measure.sourceColumn?.let(indexes::containsKey) == true
    if (!valid) warnings += "Measure '${measure.label}' references an unknown source column"
    valid
}

internal fun parseDateTime(raw: String): LocalDateTime? {
    val text = raw.trim()
    return runCatching { OffsetDateTime.parse(text).toLocalDateTime() }.getOrNull()
        ?: runCatching { LocalDateTime.parse(text) }.getOrNull()
        ?: runCatching { LocalDateTime.parse(text.replace(' ', 'T')) }.getOrNull()
        ?: runCatching { LocalDate.parse(text).atStartOfDay() }.getOrNull()
}

internal fun escapePath(raw: String): String = raw.replace("%", "%25").replace("/", "%2F")

internal fun intersect(left: BitSet, right: BitSet): BitSet =
    (left.clone() as BitSet).apply { and(right) }

internal fun BitSet.toIndexList(): List<Int> = buildList {
    var index = nextSetBit(0)
    while (index >= 0) {
        add(index)
        index = nextSetBit(index + 1)
    }
}

internal fun List<BigDecimal>.average(): BigDecimal? =
    if (isEmpty()) null
    else
        fold(BigDecimal.ZERO, BigDecimal::add)
            .divide(BigDecimal(size), 8, RoundingMode.HALF_UP)
            .stripTrailingZeros()

internal fun ratio(numerator: BigDecimal, denominator: BigDecimal?): BigDecimal? =
    denominator
        ?.takeUnless { it.compareTo(BigDecimal.ZERO) == 0 }
        ?.let { numerator.divide(it, 10, RoundingMode.HALF_UP).stripTrailingZeros() }

internal fun measureReferences(formula: String): Set<String> =
    Regex("\\[([^]]+)]")
        .findAll(formula)
        .map { it.groupValues[1].trim() }
        .filter { it.isNotEmpty() }
        .toSet()

internal fun ResultCell.toDecimalOrNull(): BigDecimal? = resultCellDecimal(this)

internal fun BigDecimal.toPivotResultCell(): ResultCell {
    val normalized = stripTrailingZeros()
    return runCatching { ResultCell.IntegerCell(normalized.longValueExact()) }
        .getOrElse { ResultCell.FloatCell(normalized.toDouble()) }
}

internal fun comparePivotCells(left: ResultCell, right: ResultCell): Int {
    val leftDecimal = left.toDecimalOrNull()
    val rightDecimal = right.toDecimalOrNull()
    if (leftDecimal != null && rightDecimal != null) return leftDecimal.compareTo(rightDecimal)
    return cellText(left).compareTo(cellText(right))
}

internal fun cellText(cell: ResultCell): String = resultCellText(cell)

internal fun resultType(measure: PivotMeasure): String =
    when {
        measure.formula != null -> "decimal"
        measure.showAs.mode in
            setOf(
                ShowAsMode.PercentGrandTotal,
                ShowAsMode.PercentRowTotal,
                ShowAsMode.PercentColumnTotal,
                ShowAsMode.PercentParent,
                ShowAsMode.PercentDifferenceFrom,
                ShowAsMode.PercentRunningTotal,
            ) -> "decimal"
        measure.fn in setOf(MeasureFn.Count, MeasureFn.CountNumbers, MeasureFn.CountDistinct) ->
            "bigint"
        measure.fn in setOf(MeasureFn.Min, MeasureFn.Max) -> "text"
        else -> "decimal"
    }
