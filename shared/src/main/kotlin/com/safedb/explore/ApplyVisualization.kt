package com.safedb.explore

import com.safedb.model.QueryResult
import com.safedb.model.ResultCell
import com.safedb.model.ResultColumn
import com.safedb.model.TableRef
import com.safedb.model.classifyColumn
import com.safedb.model.isNumeric
import com.safedb.model.isTemporal
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sqrt

fun applyVisualization(
    sample: QueryResult,
    config: VisualizationConfig,
    tables: List<TableRef> = emptyList(),
): VisualizationPreview = VisualizationPlanner(sample, config).apply()

private class VisualizationPlanner(
    private val sample: QueryResult,
    private val config: VisualizationConfig,
) {
    private val sampleIndex = SampleIndex(sample)
    private val types = sample.columns.associate { it.name to classifyColumn(it.dataType) }
    private val warnings = linkedSetOf<String>()
    private val seriesSortKeys = mutableMapOf<String, String>()
    private val plainValues = mutableMapOf<String, String>()
    private val autoDecimalFormat by lazy { createGroupedAutoDecimalFormat() }

    fun apply(): VisualizationPreview {
        if (!config.isConfigured())
            return blocked("Choose a template or add fields to build a chart.")
        val chartType =
            resolveChartType() ?: return blocked("Add compatible fields to finish the chart.")
        val validation = validate(chartType)
        if (validation != null) return blocked(validation, chartType)

        val records =
            sample.rows.mapIndexed { index, row -> ChartRecord(index, row) }.filter(::passesFilters)
        if (records.isEmpty()) return blocked("No rows to plot.", chartType)

        val marks =
            when (chartType) {
                ChartType.Bar,
                ChartType.Line -> aggregateMarks(records, chartType)
                ChartType.Scatter -> scatterMarks(records)
                ChartType.Histogram -> histogramMarks(records)
                ChartType.Kpi -> kpiMarks(records)
                ChartType.Auto -> emptyList()
            }
        if (marks.isEmpty()) return blocked("No plottable values were found.", chartType)
        val canonicalSeries = canonicalSeriesOrder(marks)
        val seriesRank = canonicalSeries.mapIndexed { index, series -> series.key to index }.toMap()
        val ordered = orderMarks(marks, chartType, seriesRank)
        val presentSeriesKeys = ordered.mapTo(mutableSetOf()) { it.seriesKey }
        val presentSeries = canonicalSeries.filter { it.key in presentSeriesKeys }
        val orphanSecondary =
            presentSeries.none { it.axis == ValueAxis.Primary } &&
                presentSeries.any { it.axis == ValueAxis.Secondary }
        val normalizedMarks =
            if (orphanSecondary) ordered.map { it.copy(axis = ValueAxis.Primary) } else ordered
        val series =
            if (orphanSecondary) presentSeries.map { it.copy(axis = ValueAxis.Primary) }
            else presentSeries
        val title = config.title.ifBlank { defaultTitle(chartType) }
        return VisualizationPreview(
            chartType = chartType,
            title = title,
            marks = normalizedMarks,
            categories = normalizedMarks.distinctBy { it.xKey }.map { it.xLabel },
            series = series,
            warnings = warnings.toList(),
            exportResult = exportResult(chartType, normalizedMarks),
        )
    }

    private fun resolveChartType(): ChartType? {
        if (config.chartType != ChartType.Auto) return config.chartType
        val x = config.x
        val firstValue = config.values.firstOrNull()
        return when {
            x == null && firstValue?.aggregate == true -> ChartType.Kpi
            x != null &&
                firstValue != null &&
                !firstValue.aggregate &&
                types[x.column].isNumeric() &&
                types[firstValue.sourceColumn].isNumeric() -> ChartType.Scatter
            x != null && config.values.isEmpty() && types[x.column].isNumeric() ->
                ChartType.Histogram
            x != null && firstValue?.aggregate == true && types[x.column].isTemporal() ->
                ChartType.Line
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
            ChartType.Bar,
            ChartType.Line ->
                when {
                    x == null -> "Choose an X or category field."
                    config.values.isEmpty() -> "Add at least one value."
                    config.values.any { !it.aggregate } ->
                        "Bar and line values must use an aggregation."
                    else -> null
                }
            ChartType.Scatter ->
                when {
                    x == null || !types[x.column].isNumeric() ->
                        "Scatter charts need a numeric X field."
                    config.values.size != 1 ||
                        config.values.single().aggregate ||
                        !types[config.values.single().sourceColumn].isNumeric() ->
                        "Scatter charts need one unaggregated numeric Y value."
                    else -> null
                }
            ChartType.Histogram ->
                if (x == null || !types[x.column].isNumeric()) {
                    "Histograms need one numeric X field."
                } else null
            ChartType.Kpi ->
                if (config.values.size != 1 || !config.values.single().aggregate) {
                    "KPI charts need one aggregated value."
                } else null
            ChartType.Auto -> "Add compatible fields to finish the chart."
        }
    }

    private fun aggregateMarks(
        records: List<ChartRecord>,
        type: ChartType,
    ): List<VisualizationMark> {
        val x = config.x ?: return emptyList()
        val dualAxisAllowed = config.supportsSecondaryAxis(type)
        val effectiveDualAxis = config.usesSecondaryAxis(type)
        val groups = linkedMapOf<Pair<ExploreBucket, ExploreBucket>, MutableList<ChartRecord>>()
        records.forEach { record ->
            val xBucket = bucket(record.cell(x.column), x)
            val seriesBucket =
                config.series?.let { seriesBucket(record.cell(it.column), it) }
                    ?: ExploreBucket("", "", "", ordinal = null)
            groups.getOrPut(xBucket to seriesBucket, ::mutableListOf) += record
        }
        val marks = mutableListOf<VisualizationMark>()
        groups.forEach { (key, grouped) ->
            val (xBucket, seriesBucket) = key
            config.values.forEach { measure ->
                val value = aggregate(grouped, measure) ?: return@forEach
                val seriesKey = if (config.series != null) seriesBucket.key else measure.alias
                val seriesLabel = if (config.series != null) seriesBucket.label else measure.label
                val id = "${xBucket.key}|$seriesKey|${measure.alias}"
                plainValues[id] = plain(value)
                marks +=
                    VisualizationMark(
                        id = id,
                        xKey = xBucket.key,
                        xLabel = xBucket.label,
                        xValue = xBucket.ordinal,
                        y = value.toDouble(),
                        formattedY = formatVisualizationNumber(value, measure.numberFormat),
                        seriesKey = seriesKey,
                        seriesLabel = seriesLabel,
                        measureAlias = measure.alias,
                        measureLabel = measure.label,
                        axis = if (effectiveDualAxis) measure.axis else ValueAxis.Primary,
                        sourceRowIndices = grouped.map { it.index },
                    )
            }
        }
        if (
            type == ChartType.Line &&
                !types[x.column].isTemporal() &&
                x.grouping is PivotGrouping.Exact
        ) {
            warnings += "Line charts are clearest with a date or datetime X field"
        }
        val filtered = applyValueFilters(marks)
        if (dualAxisAllowed && !effectiveDualAxis && filtered.isNotEmpty()) {
            val nonZeroPeaks =
                filtered
                    .groupBy { it.measureAlias }
                    .values
                    .map { measureMarks -> measureMarks.maxOf { abs(it.y) } }
                    .filter { it > 0.0 }
            if (nonZeroPeaks.size >= 2 && nonZeroPeaks.max() / nonZeroPeaks.min() >= 20.0) {
                warnings +=
                    "Values use very different scales. Assign a value to the secondary axis to compare them."
            }
        }
        return filtered
    }

    private fun scatterMarks(records: List<ChartRecord>): List<VisualizationMark> {
        val x = config.x ?: return emptyList()
        val yMeasure = config.values.single()
        val yColumn = yMeasure.sourceColumn ?: return emptyList()
        return records.mapNotNull { record ->
            val xValue = resultCellDecimal(record.cell(x.column))
            val yValue = resultCellDecimal(record.cell(yColumn))
            if (xValue == null || yValue == null) return@mapNotNull null
            val seriesBucket = config.series?.let { seriesBucket(record.cell(it.column), it) }
            val size = config.size?.let { resultCellDecimal(record.cell(it.column))?.toDouble() }
            VisualizationMark(
                id = "row:${record.index}",
                xKey = xValue.toPlainString(),
                xLabel = xValue.stripTrailingZeros().toPlainString(),
                xValue = xValue.toDouble(),
                y = yValue.toDouble(),
                formattedY = formatVisualizationNumber(yValue, yMeasure.numberFormat),
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
            resultCellDecimal(record.cell(field.column))?.let { record to it }
        }
        if (values.isEmpty()) return emptyList()
        val configured = field.grouping as? PivotGrouping.NumberBin
        val min = values.minOf { it.second }
        val max = values.maxOf { it.second }
        val requested = configured?.size?.trim()?.toBigDecimalOrNull()
        val binCount = ceil(sqrt(values.size.toDouble())).toInt().coerceIn(5, 20)
        val computed =
            if (max.compareTo(min) == 0) BigDecimal.ONE
            else
                niceHistogramBinSize(
                    max.subtract(min).divide(binCount.toBigDecimal(), MathContext.DECIMAL128),
                )
        val size = requested?.takeIf { it > BigDecimal.ZERO } ?: computed
        val start = configured?.start?.trim()?.toBigDecimalOrNull() ?: BigDecimal.ZERO
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
                formattedY = autoDecimalFormat.format(grouped.size.toBigDecimal()),
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
        val id = "kpi:${measure.alias}"
        plainValues[id] = plain(value)
        return listOf(
            VisualizationMark(
                id = id,
                xKey = "",
                xLabel = measure.label,
                y = value.toDouble(),
                formattedY = formatVisualizationNumber(value, measure.numberFormat),
                seriesKey = measure.alias,
                seriesLabel = measure.label,
                measureAlias = measure.alias,
                measureLabel = measure.label,
                sourceRowIndices = records.map { it.index },
            ),
        )
    }

    private fun passesFilters(record: ChartRecord): Boolean =
        passesPivotFilters(record.row, config.filters, sampleIndex)

    private fun applyValueFilters(marks: List<VisualizationMark>): List<VisualizationMark> {
        var current = marks
        config.filters.filterIsInstance<PivotFilter.Value>().forEach { filter ->
            val matching = current.filter { it.measureAlias == filter.measureAlias }
            current =
                when (filter.op) {
                    ValueFilterOp.Top -> {
                        val keys =
                            matching
                                .sortedByDescending { it.y }
                                .take(filter.count)
                                .map { it.xKey }
                                .toSet()
                        current.filter { it.xKey in keys }
                    }
                    ValueFilterOp.Bottom -> {
                        val keys =
                            matching.sortedBy { it.y }.take(filter.count).map { it.xKey }.toSet()
                        current.filter { it.xKey in keys }
                    }
                    else ->
                        current.filter { mark ->
                            if (mark.measureAlias != filter.measureAlias) true
                            else {
                                val first = filter.value.toDoubleOrNull()
                                val second = filter.secondValue?.toDoubleOrNull()
                                when (filter.op) {
                                    ValueFilterOp.GreaterThan -> first != null && mark.y > first
                                    ValueFilterOp.GreaterThanOrEqual ->
                                        first != null && mark.y >= first
                                    ValueFilterOp.LessThan -> first != null && mark.y < first
                                    ValueFilterOp.LessThanOrEqual ->
                                        first != null && mark.y <= first
                                    ValueFilterOp.Between ->
                                        first != null && second != null && mark.y in first..second
                                }
                            }
                        }
                }
        }
        return current
    }

    private fun canonicalSeriesOrder(marks: List<VisualizationMark>): List<VisualizationSeries> {
        val distinct =
            marks
                .distinctBy { it.seriesKey }
                .map { VisualizationSeries(it.seriesKey, it.seriesLabel, it.axis) }
        return if (config.series != null) {
            distinct.sortedBy { (seriesSortKeys[it.key] ?: it.label).lowercase(Locale.ROOT) }
        } else {
            distinct
        }
    }

    private fun orderMarks(
        marks: List<VisualizationMark>,
        type: ChartType,
        seriesRank: Map<String, Int>,
    ): List<VisualizationMark> {
        if (type == ChartType.Scatter || type == ChartType.Kpi) return marks
        val groups = marks.groupBy { it.xKey }.values.toList()
        if (type == ChartType.Histogram) {
            return groups.sortedBy { it.first().xValue }.flatten()
        }
        val sorted =
            when (config.sort.target) {
                VisualizationSortTarget.Source ->
                    if (type == ChartType.Line) {
                        groups.sortedBy { it.first().xValue }
                    } else {
                        groups
                    }
                VisualizationSortTarget.Category ->
                    groups.sortedBy { it.first().xLabel.lowercase() }
                VisualizationSortTarget.Value ->
                    groups.sortedBy { group ->
                        val primary = group.filter { it.axis == ValueAxis.Primary }
                        (primary.ifEmpty { group }).sumOf { it.y }
                    }
            }.let { if (config.sort.dir == SortDir.Desc) it.reversed() else it }
        val limited = if (type == ChartType.Bar) sorted.take(config.topN) else sorted
        if (type == ChartType.Bar && groups.size > limited.size) {
            warnings += "Showing ${limited.size} of ${groups.size} categories"
        }
        return limited.flatMap { group ->
            group.sortedBy { mark -> seriesRank[mark.seriesKey] ?: Int.MAX_VALUE }
        }
    }

    private fun seriesBucket(cell: ResultCell, field: VisualizationField): ExploreBucket =
        bucket(cell, field).also { seriesSortKeys.putIfAbsent(it.key, it.sortKey) }

    private fun bucket(cell: ResultCell, field: VisualizationField): ExploreBucket {
        if (cell is ResultCell.Null)
            return ExploreBucket("<null>", "(blank)", "(blank)", ordinal = null)
        return groupingBucket(cell, field.grouping, field.label, WeekKeyStyle.Unpadded) {
            warnings += it
        }
    }

    private fun aggregate(records: List<ChartRecord>, measure: VisualizationMeasure): BigDecimal? {
        val column = measure.sourceColumn
        if (measure.fn == MeasureFn.Count && column == null) return records.size.toBigDecimal()
        val cells = column?.let { records.map { record -> record.cell(it) } }.orEmpty()
        return when (measure.fn) {
            MeasureFn.Min -> cells.mapNotNull(::resultCellDecimal).minOrNull()
            MeasureFn.Max -> cells.mapNotNull(::resultCellDecimal).maxOrNull()
            MeasureFn.Sum ->
                cells.mapNotNull(::resultCellDecimal).fold(BigDecimal.ZERO, BigDecimal::add)
            MeasureFn.Avg -> cells.mapNotNull(::resultCellDecimal).average()
            MeasureFn.Product ->
                cells
                    .mapNotNull(::resultCellDecimal)
                    .takeIf { it.isNotEmpty() }
                    ?.fold(BigDecimal.ONE, BigDecimal::multiply)
            else -> resultCellDecimal(aggregateMeasure(cells, measure.fn))
        }
    }

    private fun defaultTitle(type: ChartType): String {
        val value = config.values.joinToString(" and ") { it.label }
        return when (type) {
            ChartType.Bar,
            ChartType.Line -> "$value by ${config.x?.label.orEmpty()}".trim()
            ChartType.Scatter -> "${config.values.single().label} vs ${config.x?.label.orEmpty()}"
            ChartType.Histogram -> "${config.x?.label.orEmpty()} distribution"
            ChartType.Kpi -> config.values.single().label
            ChartType.Auto -> "Visualization"
        }
    }

    private fun exportResult(type: ChartType, marks: List<VisualizationMark>): QueryResult {
        val columnSpecs = visualizationTableColumns(type, config)
        val allAuto = config.values.all { it.numberFormat.kind == NumberFormatKind.Auto }
        val columns = columnSpecs.map { spec ->
            val dataType =
                when (spec.kind) {
                    VisualizationTableColumnKind.ScatterX,
                    VisualizationTableColumnKind.ScatterY,
                    VisualizationTableColumnKind.Size -> "decimal"
                    VisualizationTableColumnKind.Category,
                    VisualizationTableColumnKind.Series,
                    VisualizationTableColumnKind.Measure -> "text"
                    VisualizationTableColumnKind.Value ->
                        when {
                            type == ChartType.Histogram -> "integer"
                            allAuto -> "decimal"
                            else -> "text"
                        }
                    VisualizationTableColumnKind.SourceRows -> "integer"
                }
            ResultColumn(spec.label, dataType)
        }
        val rows = marks.map { mark ->
            columnSpecs.map { spec ->
                when (spec.kind) {
                    VisualizationTableColumnKind.ScatterX ->
                        ResultCell.FloatCell(mark.xValue ?: 0.0)
                    VisualizationTableColumnKind.ScatterY -> ResultCell.FloatCell(mark.y)
                    VisualizationTableColumnKind.Category -> ResultCell.text(mark.xLabel)
                    VisualizationTableColumnKind.Series -> ResultCell.text(mark.seriesLabel)
                    VisualizationTableColumnKind.Size ->
                        mark.size?.let(ResultCell::FloatCell) ?: ResultCell.Null
                    VisualizationTableColumnKind.Measure -> ResultCell.text(mark.measureLabel)
                    VisualizationTableColumnKind.Value ->
                        when {
                            type == ChartType.Histogram -> ResultCell.IntegerCell(mark.y.toLong())
                            allAuto -> ResultCell.text(plainValues[mark.id] ?: mark.y.toString())
                            else -> ResultCell.text(mark.formattedY)
                        }
                    VisualizationTableColumnKind.SourceRows ->
                        ResultCell.IntegerCell(mark.sourceRowIndices.size.toLong())
                }
            }
        }
        return QueryResult(columns, rows, rows.size, sample.truncated, warnings.toList())
    }

    private fun formatVisualizationNumber(value: BigDecimal, format: PivotNumberFormat): String =
        if (format.kind == NumberFormatKind.Auto) {
            autoDecimalFormat.format(value)
        } else {
            formatExploreNumber(value, format)
        }

    private fun blocked(message: String, type: ChartType? = null) =
        VisualizationPreview(
            chartType = type,
            title = config.title,
            warnings = warnings.toList(),
            blockingMessage = message,
        )

    private fun ChartRecord.cell(column: String): ResultCell = sampleIndex.cell(row, column)
}

private data class ChartRecord(val index: Int, val row: List<ResultCell>)

private fun plain(value: BigDecimal): String = value.stripTrailingZeros().toPlainString()

private fun niceHistogramBinSize(rough: BigDecimal): BigDecimal {
    if (rough <= BigDecimal.ZERO) return BigDecimal.ONE
    val exponent = rough.precision() - rough.scale() - 1
    val fraction = rough.scaleByPowerOfTen(-exponent)
    val niceFraction =
        when {
            fraction <= BigDecimal.ONE -> BigDecimal.ONE
            fraction <= BigDecimal("2") -> BigDecimal("2")
            fraction <= BigDecimal("2.5") -> BigDecimal("2.5")
            fraction <= BigDecimal("5") -> BigDecimal("5")
            else -> BigDecimal.TEN
        }
    return niceFraction.scaleByPowerOfTen(exponent).stripTrailingZeros()
}
