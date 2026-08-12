package com.safedb.explore

import com.safedb.model.ResultCell
import java.math.BigDecimal
import kotlin.math.sqrt

internal fun aggregateMeasure(
    cells: List<ResultCell>,
    fn: MeasureFn,
    onNonNumericSkipped: (Int) -> Unit = {},
): ResultCell {
    val concrete = cells.filterNot { it is ResultCell.Null }
    fun decimals(): List<BigDecimal> {
        val values = concrete.mapNotNull(::resultCellDecimal)
        val skipped = concrete.size - values.size
        if (skipped > 0) onNonNumericSkipped(skipped)
        return values
    }
    return when (fn) {
        MeasureFn.Count -> ResultCell.IntegerCell(concrete.size.toLong())
        MeasureFn.CountNumbers -> ResultCell.IntegerCell(decimals().size.toLong())
        MeasureFn.CountDistinct ->
            ResultCell.IntegerCell(concrete.map(::pivotCellKey).distinct().size.toLong())
        MeasureFn.Sum -> decimals().fold(BigDecimal.ZERO, BigDecimal::add).toPivotResultCell()
        MeasureFn.Avg -> decimals().average()?.toPivotResultCell() ?: ResultCell.Null
        MeasureFn.Min -> concrete.minWithOrNull(::comparePivotCells) ?: ResultCell.Null
        MeasureFn.Max -> concrete.maxWithOrNull(::comparePivotCells) ?: ResultCell.Null
        MeasureFn.Product ->
            decimals()
                .takeIf { it.isNotEmpty() }
                ?.fold(BigDecimal.ONE, BigDecimal::multiply)
                ?.toPivotResultCell() ?: ResultCell.Null
        MeasureFn.StdDev -> statistic(decimals(), sample = true, squareRoot = true)
        MeasureFn.StdDevPopulation -> statistic(decimals(), sample = false, squareRoot = true)
        MeasureFn.Variance -> statistic(decimals(), sample = true, squareRoot = false)
        MeasureFn.VariancePopulation -> statistic(decimals(), sample = false, squareRoot = false)
    }
}

private fun statistic(values: List<BigDecimal>, sample: Boolean, squareRoot: Boolean): ResultCell {
    if (values.isEmpty() || sample && values.size < 2) return ResultCell.Null
    val doubles = values.map(BigDecimal::toDouble)
    val mean = doubles.average()
    val denominator = if (sample) doubles.size - 1 else doubles.size
    val variance = doubles.sumOf { (it - mean) * (it - mean) } / denominator
    return ResultCell.FloatCell(if (squareRoot) sqrt(variance) else variance)
}
