package com.safedb.explore

import com.safedb.model.ResultCell
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields

// sortKey orders pivot axes; ordinal positions a chart mark. The worksheet builds its own
// comparable ResultCell from those two.
internal data class ExploreBucket(
    val key: String,
    val label: String,
    val sortKey: String,
    val ordinal: Double?,
)

// Bucket keys reach persisted collapse paths, and the two engines padded ISO weeks differently.
internal enum class WeekKeyStyle {
    Unpadded,
    Padded,
}

internal fun groupingBucket(
    cell: ResultCell,
    grouping: PivotGrouping,
    label: String,
    weekKeyStyle: WeekKeyStyle,
    onWarning: (String) -> Unit,
): ExploreBucket =
    when (grouping) {
        PivotGrouping.Exact -> exactBucket(cell)
        is PivotGrouping.Date -> dateBucket(cell, grouping.unit, label, weekKeyStyle, onWarning)
        is PivotGrouping.NumberBin -> numberBucket(cell, grouping, label, onWarning)
    }

private fun exactBucket(cell: ResultCell): ExploreBucket =
    ExploreBucket(
        key = pivotCellKey(cell),
        label = cellText(cell),
        sortKey = cellText(cell),
        ordinal = resultCellDecimal(cell)?.toDouble(),
    )

private fun dateBucket(
    cell: ResultCell,
    unit: DateGroupUnit,
    label: String,
    weekKeyStyle: WeekKeyStyle,
    onWarning: (String) -> Unit,
): ExploreBucket {
    val date = parseDateTime(cellText(cell))?.toLocalDate()
    if (date == null) {
        onWarning("$label contains values that could not be grouped as dates")
        return ExploreBucket("<invalid-date>", "(invalid date)", "9999", null)
    }
    return when (unit) {
        DateGroupUnit.Year ->
            ExploreBucket(
                key = "${date.year}",
                label = "${date.year}",
                sortKey = "%04d".format(date.year),
                ordinal = date.year.toDouble(),
            )
        DateGroupUnit.Quarter -> {
            val quarter = ((date.monthValue - 1) / 3) + 1
            ExploreBucket(
                key = "${date.year}-Q$quarter",
                label = "Q$quarter ${date.year}",
                sortKey = "%04d-%d".format(date.year, quarter),
                ordinal = (date.year * 4 + quarter).toDouble(),
            )
        }
        DateGroupUnit.Month ->
            ExploreBucket(
                key = "%04d-%02d".format(date.year, date.monthValue),
                label = date.format(DateTimeFormatter.ofPattern("MMM yyyy")),
                sortKey = "%04d-%02d".format(date.year, date.monthValue),
                ordinal = (date.year * 12 + date.monthValue).toDouble(),
            )
        DateGroupUnit.IsoWeek -> {
            val fields = WeekFields.ISO
            val weekYear = date.get(fields.weekBasedYear())
            val week = date.get(fields.weekOfWeekBasedYear())
            ExploreBucket(
                key =
                    when (weekKeyStyle) {
                        WeekKeyStyle.Unpadded -> "$weekYear-W$week"
                        WeekKeyStyle.Padded -> "%04d-W%02d".format(weekYear, week)
                    },
                label = "%04d-W%02d".format(weekYear, week),
                sortKey = "%04d-%02d".format(weekYear, week),
                ordinal = (weekYear * 53 + week).toDouble(),
            )
        }
        DateGroupUnit.Day ->
            ExploreBucket(
                key = date.toString(),
                label = date.toString(),
                sortKey = date.toString(),
                ordinal = date.toEpochDay().toDouble(),
            )
    }
}

private fun numberBucket(
    cell: ResultCell,
    grouping: PivotGrouping.NumberBin,
    label: String,
    onWarning: (String) -> Unit,
): ExploreBucket {
    val value = resultCellDecimal(cell)
    val size = grouping.size.toBigDecimalOrNull()
    val start = grouping.start?.toBigDecimalOrNull() ?: BigDecimal.ZERO
    if (value == null || size == null || size <= BigDecimal.ZERO) {
        onWarning("$label needs a positive numeric bin size and numeric values")
        return exactBucket(cell)
    }
    val index = value.subtract(start).divide(size, 0, RoundingMode.FLOOR)
    val lower = start.add(index.multiply(size)).stripTrailingZeros()
    val upper = lower.add(size).stripTrailingZeros()
    return ExploreBucket(
        key = "${lower.toPlainString()}:${size.toPlainString()}",
        label = "${lower.toPlainString()} – ${upper.toPlainString()}",
        sortKey = lower.toPlainString().padStart(32, '0'),
        ordinal = lower.toDouble(),
    )
}
