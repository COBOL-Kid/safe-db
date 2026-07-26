package com.safedb.explore

import com.safedb.model.ColumnCategory
import com.safedb.model.QueryResult
import com.safedb.model.ResultCell
import com.safedb.model.ResultColumn
import com.safedb.model.TableRef
import com.safedb.model.classifyColumn
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields
import java.util.Currency
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

fun applyVisualization(
    sample: QueryResult,
    config: VisualizationConfig,
    tables: List<TableRef> = emptyList(),
): VisualizationPreview = VisualizationPlanner(sample, config, tables).apply()

private class VisualizationPlanner(
    private val sample: QueryResult,
    private val config: VisualizationConfig,
    tables: List<TableRef>,
) {
    private val indexes = sample.columns.mapIndexed { index, column -> column.name to index }.toMap()
    private val types = sample.columns.associate { it.name to classifyColumn(it.dataType) }
    private val labels = displayColumnLabels(sample.columns, tables)
    private val warnings = linkedSetOf<String>()

    fun apply(): VisualizationPreview {
        if (!config.isConfigured()) return blocked("Choose a template or add fields to build a chart.")
        val chartType = resolveChartType() ?: return blocked("Add compatible fields to finish the chart.")
        val validation = validate(chartType)
        if (validation != null) return blocked(validation, chartType)

        val records = sample.rows.mapIndexed { index, row -> ChartRecord(index, row) }.filter(::passesFilters)
        if (records.isEmpty()) return blocked("No rows to plot.", chartType)

        val marks = when (chartType) {
            ChartType.Bar, ChartType.Line -> aggregateMarks(records, chartType)
            ChartType.Scatter -> scatterMarks(records)
            ChartType.Histogram -> histogramMarks(records)
            ChartType.Kpi -> kpiMarks(records)
            ChartType.Auto -> emptyList()
        }
        if (marks.isEmpty()) return blocked("No plottable values were found.", chartType)
        val ordered = orderMarks(marks, chartType)
        val series = ordered.distinctBy { it.seriesKey }.map { VisualizationSeries(it.seriesKey, it.seriesLabel) }
        val title = config.title.ifBlank { defaultTitle(chartType) }
        return VisualizationPreview(
            chartType = chartType,
            title = title,
            marks = ordered,
            categories = ordered.distinctBy { it.xKey }.map { it.xLabel },
            series = series,
            warnings = warnings.toList() + sample.warnings,
            exportResult = exportResult(chartType, ordered),
        )
    }

    private fun resolveChartType(): ChartType? {
        if (config.chartType != ChartType.Auto) return config.chartType
        val x = config.x
        val firstValue = config.values.firstOrNull()
        return when {
            x == null && firstValue?.aggregate == true -> ChartType.Kpi
            x != null && firstValue != null && !firstValue.aggregate &&
                types[x.column].isNumeric() && types[firstValue.sourceColumn].isNumeric() -> ChartType.Scatter
            x != null && config.values.isEmpty() && types[x.column].isNumeric() -> ChartType.Histogram
            x != null && firstValue?.aggregate == true && types[x.column].isTemporal() -> ChartType.Line
            x != null && config.values.isNotEmpty() -> ChartType.Bar
            else -> null
        }
    }

    private fun validate(type: ChartType): String? {
        val x = config.x
        if (config.series != null && config.values.size > 1) {
            return "Use either multiple values or a Series field, not both."
        }
        return when (type) {
            ChartType.Bar, ChartType.Line -> when {
                x == null -> "Choose an X or category field."
                config.values.isEmpty() -> "Add at least one value."
                config.values.any { !it.aggregate } -> "Bar and line values must use an aggregation."
                else -> null
            }
            ChartType.Scatter -> when {
                x == null || !types[x.column].isNumeric() -> "Scatter charts need a numeric X field."
                config.values.size != 1 || config.values.single().aggregate ||
                    !types[config.values.single().sourceColumn].isNumeric() -> "Scatter charts need one unaggregated numeric Y value."
                else -> null
            }
            ChartType.Histogram -> if (x == null || !types[x.column].isNumeric()) {
                "Histograms need one numeric X field."
            } else null
            ChartType.Kpi -> if (config.values.size != 1 || !config.values.single().aggregate) {
                "KPI charts need one aggregated value."
            } else null
            ChartType.Auto -> "Add compatible fields to finish the chart."
        }
    }

    private fun aggregateMarks(records: List<ChartRecord>, type: ChartType): List<VisualizationMark> {
        val x = config.x ?: return emptyList()
        val groups = linkedMapOf<Pair<Bucket, Bucket>, MutableList<ChartRecord>>()
        records.forEach { record ->
            val xBucket = bucket(record.cell(x.column), x)
            val seriesBucket = config.series?.let { bucket(record.cell(it.column), it) } ?: Bucket("", "", null)
            groups.getOrPut(xBucket to seriesBucket, ::mutableListOf) += record
        }
        val marks = mutableListOf<VisualizationMark>()
        groups.forEach { (key, grouped) ->
            val (xBucket, seriesBucket) = key
            config.values.forEach { measure ->
                val value = aggregate(grouped, measure) ?: return@forEach
                val seriesKey = if (config.series != null) seriesBucket.key else measure.alias
                val seriesLabel = if (config.series != null) seriesBucket.label else measure.label
                marks += VisualizationMark(
                    id = "${xBucket.key}|$seriesKey|${measure.alias}",
                    xKey = xBucket.key,
                    xLabel = xBucket.label,
                    xValue = xBucket.numeric,
                    y = value.toDouble(),
                    formattedY = formatNumber(value, measure.numberFormat),
                    seriesKey = seriesKey,
                    seriesLabel = seriesLabel,
                    measureAlias = measure.alias,
                    measureLabel = measure.label,
                    sourceRowIndices = grouped.map { it.index },
                )
            }
        }
        if (type == ChartType.Line && !types[x.column].isTemporal() && x.grouping is PivotGrouping.Exact) {
            warnings += "Line charts are clearest with a date or datetime X field"
        }
        return applyValueFilters(marks)
    }

    private fun scatterMarks(records: List<ChartRecord>): List<VisualizationMark> {
        val x = config.x ?: return emptyList()
        val yMeasure = config.values.single()
        val yColumn = yMeasure.sourceColumn ?: return emptyList()
        return records.mapNotNull { record ->
            val xValue = record.cell(x.column).decimalOrNull()
            val yValue = record.cell(yColumn).decimalOrNull()
            if (xValue == null || yValue == null) return@mapNotNull null
            val seriesBucket = config.series?.let { bucket(record.cell(it.column), it) }
            val size = config.size?.let { record.cell(it.column).decimalOrNull()?.toDouble() }
            VisualizationMark(
                id = "row:${record.index}",
                xKey = xValue.toPlainString(),
                xLabel = xValue.stripTrailingZeros().toPlainString(),
                xValue = xValue.toDouble(),
                y = yValue.toDouble(),
                formattedY = formatNumber(yValue, yMeasure.numberFormat),
                seriesKey = seriesBucket?.key ?: yMeasure.alias,
                seriesLabel = seriesBucket?.label ?: yMeasure.label,
                measureAlias = yMeasure.alias,
                measureLabel = yMeasure.label,
                size = size,
                sourceRowIndices = listOf(record.index),
            )
        }
    }

    private fun histogramMarks(records: List<ChartRecord>): List<VisualizationMark> {
        val field = config.x ?: return emptyList()
        val values = records.mapNotNull { record ->
            record.cell(field.column).decimalOrNull()?.let { record to it }
        }
        if (values.isEmpty()) return emptyList()
        val configured = field.grouping as? PivotGrouping.NumberBin
        val min = values.minOf { it.second }
        val max = values.maxOf { it.second }
        val requested = configured?.size?.toBigDecimalOrNull()
        val binCount = ceil(sqrt(values.size.toDouble())).toInt().coerceIn(5, 20)
        val computed = if (max.compareTo(min) == 0) BigDecimal.ONE else
            max.subtract(min).divide(binCount.toBigDecimal(), MathContext.DECIMAL128)
        val size = requested?.takeIf { it > BigDecimal.ZERO } ?: computed
        val start = configured?.start?.toBigDecimalOrNull() ?: min
        val groups = linkedMapOf<BigDecimal, MutableList<ChartRecord>>()
        values.forEach { (record, value) ->
            val index = value.subtract(start).divide(size, 0, RoundingMode.FLOOR)
            val lower = start.add(index.multiply(size))
            groups.getOrPut(lower, ::mutableListOf) += record
        }
        return groups.toSortedMap().map { (lower, grouped) ->
            val upper = lower.add(size)
            val label = "${plain(lower)} – ${plain(upper)}"
            VisualizationMark(
                id = "bin:${plain(lower)}",
                xKey = plain(lower),
                xLabel = label,
                xValue = lower.toDouble(),
                y = grouped.size.toDouble(),
                formattedY = grouped.size.toString(),
                seriesKey = "count",
                seriesLabel = "Count",
                measureAlias = "count",
                measureLabel = "Count",
                sourceRowIndices = grouped.map { it.index },
            )
        }
    }

    private fun kpiMarks(records: List<ChartRecord>): List<VisualizationMark> {
        val measure = config.values.single()
        val value = aggregate(records, measure) ?: return emptyList()
        return listOf(
            VisualizationMark(
                id = "kpi:${measure.alias}",
                xKey = "",
                xLabel = measure.label,
                y = value.toDouble(),
                formattedY = formatNumber(value, measure.numberFormat),
                seriesKey = measure.alias,
                seriesLabel = measure.label,
                measureAlias = measure.alias,
                measureLabel = measure.label,
                sourceRowIndices = records.map { it.index },
            ),
        )
    }

    private fun passesFilters(record: ChartRecord): Boolean = config.filters.all { filter ->
        val cell = record.cell(filter.column)
        when (filter) {
            is PivotFilter.Members -> filter.includedKeys.isEmpty() || pivotCellKey(cell) in filter.includedKeys
            is PivotFilter.Label -> {
                val text = cell.text()
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

    private fun applyValueFilters(marks: List<VisualizationMark>): List<VisualizationMark> {
        var current = marks
        config.filters.filterIsInstance<PivotFilter.Value>().forEach { filter ->
            val matching = current.filter { it.measureAlias == filter.measureAlias }
            current = when (filter.op) {
                ValueFilterOp.Top -> {
                    val keys = matching.sortedByDescending { it.y }.take(filter.count).map { it.xKey }.toSet()
                    current.filter { it.xKey in keys }
                }
                ValueFilterOp.Bottom -> {
                    val keys = matching.sortedBy { it.y }.take(filter.count).map { it.xKey }.toSet()
                    current.filter { it.xKey in keys }
                }
                else -> current.filter { mark ->
                    if (mark.measureAlias != filter.measureAlias) true else {
                        val first = filter.value.toDoubleOrNull()
                        val second = filter.secondValue?.toDoubleOrNull()
                        when (filter.op) {
                            ValueFilterOp.GreaterThan -> first != null && mark.y > first
                            ValueFilterOp.GreaterThanOrEqual -> first != null && mark.y >= first
                            ValueFilterOp.LessThan -> first != null && mark.y < first
                            ValueFilterOp.LessThanOrEqual -> first != null && mark.y <= first
                            ValueFilterOp.Between -> first != null && second != null && mark.y in first..second
                            ValueFilterOp.Top, ValueFilterOp.Bottom -> true
                        }
                    }
                }
            }
        }
        return current
    }

    private fun orderMarks(marks: List<VisualizationMark>, type: ChartType): List<VisualizationMark> {
        if (type == ChartType.Scatter || type == ChartType.Kpi) return marks
        val groups = marks.groupBy { it.xKey }.values.toList()
        if (type == ChartType.Histogram) {
            return groups.sortedBy { it.first().xValue }.flatten()
        }
        val sorted = when (config.sort.target) {
            VisualizationSortTarget.Source -> if (type == ChartType.Line) {
                groups.sortedBy { it.first().xValue }
            } else {
                groups
            }
            VisualizationSortTarget.Category -> groups.sortedBy { it.first().xLabel.lowercase() }
            VisualizationSortTarget.Value -> groups.sortedBy { it.firstOrNull()?.y ?: 0.0 }
        }.let { if (config.sort.dir == SortDir.Desc) it.reversed() else it }
        val limited = if (type == ChartType.Bar) sorted.take(config.topN) else sorted
        if (type == ChartType.Bar && groups.size > limited.size) {
            warnings += "Showing ${limited.size} of ${groups.size} categories"
        }
        return limited.flatten()
    }

    private fun bucket(cell: ResultCell, field: VisualizationField): Bucket {
        if (cell is ResultCell.Null) return Bucket("<null>", "(blank)", null)
        return when (val grouping = field.grouping) {
            PivotGrouping.Exact -> Bucket(pivotCellKey(cell), cell.text(), cell.decimalOrNull()?.toDouble())
            is PivotGrouping.Date -> dateBucket(cell, field, grouping.unit)
            is PivotGrouping.NumberBin -> numberBucket(cell, field, grouping)
        }
    }

    private fun dateBucket(cell: ResultCell, field: VisualizationField, unit: DateGroupUnit): Bucket {
        val date = parseDate(cell.text())
        if (date == null) {
            warnings += "${field.label} contains values that could not be grouped as dates"
            return Bucket("<invalid-date>", "(invalid date)", null)
        }
        return when (unit) {
            DateGroupUnit.Year -> Bucket("${date.year}", "${date.year}", date.year.toDouble())
            DateGroupUnit.Quarter -> {
                val quarter = ((date.monthValue - 1) / 3) + 1
                Bucket("${date.year}-Q$quarter", "Q$quarter ${date.year}", (date.year * 4 + quarter).toDouble())
            }
            DateGroupUnit.Month -> Bucket(
                "%04d-%02d".format(date.year, date.monthValue),
                date.format(DateTimeFormatter.ofPattern("MMM yyyy")),
                (date.year * 12 + date.monthValue).toDouble(),
            )
            DateGroupUnit.IsoWeek -> {
                val fields = WeekFields.ISO
                val year = date.get(fields.weekBasedYear())
                val week = date.get(fields.weekOfWeekBasedYear())
                Bucket("$year-W$week", "%04d-W%02d".format(year, week), (year * 53 + week).toDouble())
            }
            DateGroupUnit.Day -> Bucket(date.toString(), date.toString(), date.toEpochDay().toDouble())
        }
    }

    private fun numberBucket(cell: ResultCell, field: VisualizationField, grouping: PivotGrouping.NumberBin): Bucket {
        val value = cell.decimalOrNull()
        val size = grouping.size.toBigDecimalOrNull()
        val start = grouping.start?.toBigDecimalOrNull() ?: BigDecimal.ZERO
        if (value == null || size == null || size <= BigDecimal.ZERO) {
            warnings += "${field.label} needs a positive numeric bin size and numeric values"
            return Bucket(pivotCellKey(cell), cell.text(), value?.toDouble())
        }
        val index = value.subtract(start).divide(size, 0, RoundingMode.FLOOR)
        val lower = start.add(index.multiply(size))
        val upper = lower.add(size)
        return Bucket("${plain(lower)}:${plain(size)}", "${plain(lower)} – ${plain(upper)}", lower.toDouble())
    }

    private fun aggregate(records: List<ChartRecord>, measure: VisualizationMeasure): BigDecimal? {
        if (measure.fn == MeasureFn.Count && measure.sourceColumn == null) return records.size.toBigDecimal()
        val values = measure.sourceColumn?.let { column -> records.mapNotNull { it.cell(column).decimalOrNull() } }.orEmpty()
        val concrete = measure.sourceColumn?.let { column -> records.map { it.cell(column) }.filterNot { it is ResultCell.Null } }.orEmpty()
        return when (measure.fn) {
            MeasureFn.Count -> concrete.size.toBigDecimal()
            MeasureFn.CountNumbers -> values.size.toBigDecimal()
            MeasureFn.CountDistinct -> concrete.map(::pivotCellKey).distinct().size.toBigDecimal()
            MeasureFn.Sum -> values.takeIf { it.isNotEmpty() }?.sumDecimals()
            MeasureFn.Avg -> values.takeIf { it.isNotEmpty() }?.sumDecimals()?.divide(values.size.toBigDecimal(), MathContext.DECIMAL128)
            MeasureFn.Min -> values.minOrNull()
            MeasureFn.Max -> values.maxOrNull()
            MeasureFn.Product -> values.takeIf { it.isNotEmpty() }?.fold(BigDecimal.ONE, BigDecimal::multiply)
            MeasureFn.StdDev -> statistic(values, sample = true, squareRoot = true)
            MeasureFn.StdDevPopulation -> statistic(values, sample = false, squareRoot = true)
            MeasureFn.Variance -> statistic(values, sample = true, squareRoot = false)
            MeasureFn.VariancePopulation -> statistic(values, sample = false, squareRoot = false)
        }
    }

    private fun statistic(values: List<BigDecimal>, sample: Boolean, squareRoot: Boolean): BigDecimal? {
        if (values.isEmpty() || sample && values.size < 2) return null
        val doubles = values.map(BigDecimal::toDouble)
        val mean = doubles.average()
        val denominator = if (sample) doubles.size - 1 else doubles.size
        val variance = doubles.sumOf { (it - mean) * (it - mean) } / denominator
        return BigDecimal.valueOf(if (squareRoot) sqrt(variance) else variance)
    }

    private fun defaultTitle(type: ChartType): String {
        val value = config.values.joinToString(" and ") { it.label }
        return when (type) {
            ChartType.Bar, ChartType.Line -> "$value by ${config.x?.label.orEmpty()}".trim()
            ChartType.Scatter -> "${config.values.single().label} vs ${config.x?.label.orEmpty()}"
            ChartType.Histogram -> "${config.x?.label.orEmpty()} distribution"
            ChartType.Kpi -> config.values.single().label
            ChartType.Auto -> "Visualization"
        }
    }

    private fun exportResult(type: ChartType, marks: List<VisualizationMark>): QueryResult {
        val columns = if (type == ChartType.Scatter) {
            listOf(
                ResultColumn(config.x?.label ?: "X", "decimal"),
                ResultColumn(config.values.single().label, "decimal"),
                ResultColumn("Series", "text"),
                ResultColumn("Size", "decimal"),
                ResultColumn("Source rows", "integer"),
            )
        } else {
            listOf(
                ResultColumn(config.x?.label ?: "Category", "text"),
                ResultColumn("Series", "text"),
                ResultColumn("Measure", "text"),
                ResultColumn("Value", "text"),
                ResultColumn("Source rows", "integer"),
            )
        }
        val rows = marks.map { mark ->
            if (type == ChartType.Scatter) {
                listOf(
                    ResultCell.FloatCell(mark.xValue ?: 0.0),
                    ResultCell.FloatCell(mark.y),
                    ResultCell.text(mark.seriesLabel),
                    mark.size?.let(ResultCell::FloatCell) ?: ResultCell.Null,
                    ResultCell.IntegerCell(mark.sourceRowIndices.size.toLong()),
                )
            } else {
                listOf(
                    ResultCell.text(mark.xLabel),
                    ResultCell.text(mark.seriesLabel),
                    ResultCell.text(mark.measureLabel),
                    ResultCell.text(mark.formattedY),
                    ResultCell.IntegerCell(mark.sourceRowIndices.size.toLong()),
                )
            }
        }
        return QueryResult(columns, rows, rows.size, sample.truncated, warnings.toList())
    }

    private fun blocked(message: String, type: ChartType? = null) = VisualizationPreview(
        chartType = type,
        title = config.title,
        warnings = warnings.toList() + sample.warnings,
        blockingMessage = message,
    )

    private fun ChartRecord.cell(column: String): ResultCell =
        indexes[column]?.let { row.getOrNull(it) } ?: ResultCell.Null
}

private data class ChartRecord(val index: Int, val row: List<ResultCell>)

private data class Bucket(val key: String, val label: String, val numeric: Double?)

private fun ColumnCategory?.isNumeric(): Boolean = this == ColumnCategory.Integer || this == ColumnCategory.Decimal

private fun ColumnCategory?.isTemporal(): Boolean = this == ColumnCategory.Date || this == ColumnCategory.DateTime

private fun ResultCell.decimalOrNull(): BigDecimal? = resultCellDecimal(this)

private fun ResultCell.text(): String = resultCellText(this)

private fun parseDate(text: String): LocalDate? =
    runCatching { LocalDate.parse(text) }.getOrNull()
        ?: runCatching { LocalDateTime.parse(text).toLocalDate() }.getOrNull()
        ?: runCatching { LocalDateTime.parse(text.replaceFirst(' ', 'T')).toLocalDate() }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(text).toLocalDate() }.getOrNull()

private fun List<BigDecimal>.sumDecimals(): BigDecimal = fold(BigDecimal.ZERO, BigDecimal::add)

private fun plain(value: BigDecimal): String = value.stripTrailingZeros().toPlainString()

private fun formatNumber(value: BigDecimal, format: PivotNumberFormat): String {
    if (format.kind == NumberFormatKind.Auto) return plain(value)
    val decimals = format.decimals.coerceIn(0, 8)
    val symbols = DecimalFormatSymbols.getInstance(Locale.getDefault())
    val pattern = buildString {
        append(if (format.thousandsSeparator) "#,##0" else "0")
        if (decimals > 0) append('.').append("0".repeat(decimals))
    }
    return when (format.kind) {
        NumberFormatKind.Number -> DecimalFormat(pattern, symbols).format(value)
        NumberFormatKind.Percent -> DecimalFormat("$pattern%", symbols).format(value)
        NumberFormatKind.Currency -> DecimalFormat("¤$pattern", symbols).apply {
            runCatching { Currency.getInstance(format.currencyCode) }.getOrNull()?.let { currency = it }
        }.format(value)
        NumberFormatKind.Scientific -> DecimalFormat("0.${"0".repeat(decimals)}E0", symbols).format(value)
        NumberFormatKind.Auto -> plain(value)
    }
}
