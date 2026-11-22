package com.safedb.ui

import com.safedb.explore.ChartType
import com.safedb.explore.ExploreConfig
import com.safedb.explore.ExploreSort
import com.safedb.explore.ExploreSortTarget
import com.safedb.explore.LabelFilterOp
import com.safedb.explore.MeasureFn
import com.safedb.explore.PivotDimension
import com.safedb.explore.PivotFilter
import com.safedb.explore.PivotGrouping
import com.safedb.explore.PivotMeasure
import com.safedb.explore.SortDir
import com.safedb.explore.ValueAxis
import com.safedb.explore.ValueFilterOp
import com.safedb.explore.VisualizationConfig
import com.safedb.explore.VisualizationField
import com.safedb.explore.VisualizationMeasure
import com.safedb.explore.displayColumnLabel
import com.safedb.explore.displayColumnLabels
import com.safedb.model.ColumnCategory
import com.safedb.model.QueryResult
import com.safedb.model.TableRef
import com.safedb.model.classifyColumn
import com.safedb.model.isNumeric
import java.math.BigDecimal
import java.util.UUID

internal data class ExploreFieldOption(
    val column: String,
    val label: String,
    val dataType: String,
    val category: ColumnCategory,
    val sourceTableName: String? = null,
    val sourceSchema: String? = null,
    val sourceTableLabel: String? = null,
) {
    fun asDimension(): PivotDimension = PivotDimension(column = column, label = label)

    fun supportingText(): String = listOfNotNull(sourceTableLabel, dataType).joinToString(" · ")

    fun matchesSearch(query: String): Boolean =
        query.isBlank() ||
            listOfNotNull(label, dataType, sourceTableName, sourceSchema, sourceTableLabel).any {
                it.contains(query, ignoreCase = true)
            }
}

internal data class ExploreFieldGroup(val label: String, val fields: List<ExploreFieldOption>)

internal const val ExploreTruncationExplanation =
    "You’re viewing a sample, so totals may not represent the full result."

internal fun exploreTruncationExplanation(truncated: Boolean): String? =
    ExploreTruncationExplanation.takeIf {
        truncated
    }

internal fun groupExploreFields(fields: List<ExploreFieldOption>): List<ExploreFieldGroup> =
    fields
        .groupBy { it.sourceTableLabel ?: "Other fields" }
        .map { (label, groupedFields) -> ExploreFieldGroup(label, groupedFields) }

internal fun buildExploreFieldOptions(
    sample: QueryResult,
    tables: List<TableRef>,
): List<ExploreFieldOption> {
    val labels = displayColumnLabels(sample.columns, tables)
    val duplicateTableNames = tables.groupingBy { it.name }.eachCount().filterValues { it > 1 }.keys
    val tablesByAlias = tables.associateBy { it.alias }
    return sample.columns.map { column ->
        val source = tablesByAlias[column.name.substringBefore("__", missingDelimiterValue = "")]
        ExploreFieldOption(
            column = column.name,
            label = labels.getValue(column.name),
            dataType = column.dataType,
            category = classifyColumn(column.dataType),
            sourceTableName = source?.name,
            sourceSchema = source?.schema,
            sourceTableLabel =
                source?.let { table ->
                    if (table.name in duplicateTableNames) "${table.schema}.${table.name}"
                    else table.name
                },
        )
    }
}

internal fun availableMeasureFunctions(field: ExploreFieldOption): List<MeasureFn> =
    when (field.category) {
        ColumnCategory.Integer,
        ColumnCategory.Decimal ->
            listOf(
                MeasureFn.Sum,
                MeasureFn.Avg,
                MeasureFn.Min,
                MeasureFn.Max,
                MeasureFn.CountNumbers,
                MeasureFn.CountDistinct,
                MeasureFn.Product,
                MeasureFn.StdDev,
                MeasureFn.StdDevPopulation,
                MeasureFn.Variance,
                MeasureFn.VariancePopulation,
            )

        ColumnCategory.Date,
        ColumnCategory.DateTime -> listOf(MeasureFn.Min, MeasureFn.Max, MeasureFn.CountDistinct)

        ColumnCategory.Text,
        ColumnCategory.Bool -> listOf(MeasureFn.CountDistinct, MeasureFn.Min, MeasureFn.Max)

        ColumnCategory.Binary,
        ColumnCategory.Json,
        ColumnCategory.Other -> listOf(MeasureFn.CountDistinct)
    }

// Charts plot a numeric Y, so only counting functions survive on a non-numeric column.
internal fun availablePlottableMeasureFunctions(field: ExploreFieldOption): List<MeasureFn> =
    if (field.category.isNumeric()) availableMeasureFunctions(field)
    else
        availableMeasureFunctions(field).filter {
            it == MeasureFn.Count || it == MeasureFn.CountDistinct
        }

internal fun measureFor(field: ExploreFieldOption, function: MeasureFn): PivotMeasure {
    require(function != MeasureFn.Count) { "Count rows does not use a source field" }
    return PivotMeasure(
        alias =
            "${function.name.lowercase()}_${field.column}_${UUID.randomUUID().toString().take(8)}",
        fn = function,
        sourceColumn = field.column,
        label = "${function.shortLabel} ${field.label}",
    )
}

internal fun toggleExploreSort(config: ExploreConfig, target: ExploreSortTarget): ExploreConfig {
    val current = config.sort
    val defaultDirection = if (target is ExploreSortTarget.Measure) SortDir.Desc else SortDir.Asc
    val next =
        if (current?.target == target) {
            current.copy(dir = if (current.dir == SortDir.Asc) SortDir.Desc else SortDir.Asc)
        } else {
            ExploreSort(target = target, dir = defaultDirection)
        }
    return config.copy(sort = next)
}

internal fun moveDimension(
    dimensions: List<PivotDimension>,
    dimension: PivotDimension,
    offset: Int,
): List<PivotDimension> {
    val currentIndex = dimensions.indexOf(dimension)
    if (currentIndex < 0) return dimensions
    val targetIndex = (currentIndex + offset).coerceIn(dimensions.indices)
    if (targetIndex == currentIndex) return dimensions
    return dimensions.toMutableList().apply {
        removeAt(currentIndex)
        add(targetIndex, dimension)
    }
}

internal fun filterSupportingText(filter: PivotFilter, memberCount: Int? = null): String =
    when (filter) {
        is PivotFilter.Members -> {
            val selection =
                when {
                    filter.includedKeys.isEmpty() -> "All values"
                    memberCount != null && filter.includedKeys.size == memberCount -> "All values"
                    else -> "${filter.includedKeys.size} selected"
                }
            if (filter.pinned) "$selection · Pinned" else selection
        }
        is PivotFilter.Label -> "${labelFilterOpLabel(filter.op)} \"${filter.value}\""
        is PivotFilter.Value -> valueFilterSupportingText(filter)
    }

private fun valueFilterSupportingText(filter: PivotFilter.Value): String =
    when (filter.op) {
        ValueFilterOp.Top -> "Top ${filter.count}"
        ValueFilterOp.Bottom -> "Bottom ${filter.count}"
        ValueFilterOp.Between -> "Between ${filter.value} and ${filter.secondValue.orEmpty()}"
        else -> "${valueFilterOpLabel(filter.op)} ${filter.value}"
    }

private fun labelFilterOpLabel(op: LabelFilterOp): String =
    when (op) {
        LabelFilterOp.Equals -> "Equals"
        LabelFilterOp.Contains -> "Contains"
        LabelFilterOp.StartsWith -> "Starts with"
        LabelFilterOp.EndsWith -> "Ends with"
    }

private fun valueFilterOpLabel(op: ValueFilterOp): String =
    when (op) {
        ValueFilterOp.GreaterThan -> ">"
        ValueFilterOp.GreaterThanOrEqual -> "≥"
        ValueFilterOp.LessThan -> "<"
        ValueFilterOp.LessThanOrEqual -> "≤"
        ValueFilterOp.Between -> "Between"
        ValueFilterOp.Top -> "Top"
        ValueFilterOp.Bottom -> "Bottom"
    }

internal fun VisualizationConfig.forChartType(
    type: ChartType,
    fields: List<ExploreFieldOption>,
): VisualizationConfig {
    fun numeric(column: String?): Boolean =
        fields.firstOrNull { it.column == column }?.category?.isNumeric() == true
    fun fieldLabel(column: String): String =
        fields.firstOrNull { it.column == column }?.label ?: displayColumnLabel(column)
    fun VisualizationMeasure.asAggregated(): VisualizationMeasure =
        if (aggregate) this
        else {
            val label = sourceColumn?.let { "${fn.shortLabel} ${fieldLabel(it)}" } ?: label
            copy(aggregate = true, label = label)
        }
    return when (type) {
        ChartType.Histogram -> {
            val promotedX =
                x?.takeIf { numeric(it.column) }
                    ?: values.firstNotNullOfOrNull { measure ->
                        measure.sourceColumn?.takeIf(::numeric)?.let { column ->
                            VisualizationField(
                                column = column,
                                label = fieldLabel(column),
                            )
                        }
                    }
            copy(
                chartType = type,
                x = promotedX,
                values = emptyList(),
                series = null,
                size = null,
            )
        }
        ChartType.Kpi ->
            copy(
                chartType = type,
                x = null,
                values = values.take(1).map { it.asAggregated().copy(axis = ValueAxis.Primary) },
                series = null,
                size = null,
            )
        ChartType.Scatter ->
            copy(
                chartType = type,
                x = x?.takeIf { numeric(it.column) },
                values =
                    values
                        .filter { numeric(it.sourceColumn) }
                        .take(1)
                        .map {
                            it.copy(
                                aggregate = false,
                                label = it.sourceColumn?.let(::fieldLabel) ?: it.label,
                                axis = ValueAxis.Primary,
                            )
                        },
                series = series,
                size = size?.takeIf { numeric(it.column) },
            )
        ChartType.Bar,
        ChartType.Line ->
            copy(chartType = type, values = values.map { it.asAggregated() }, size = null)
        ChartType.Auto -> copy(chartType = type)
    }
}

internal fun chartTypeTransitionMessage(
    previous: VisualizationConfig,
    next: VisualizationConfig,
): String? {
    val nextX = next.x
    if (
        nextX != null &&
            next.chartType == ChartType.Histogram &&
            previous.x?.column != nextX.column &&
            previous.values.any { it.sourceColumn == nextX.column }
    ) {
        val otherRemoved = buildList {
            if (previous.series != null && next.series == null) add("series")
            if (previous.size != null && next.size == null) add("size")
        }
        val moved = "Moved ${nextX.label} to X for histogram"
        return if (otherRemoved.isEmpty()) {
            "$moved."
        } else {
            "$moved; removed incompatible ${otherRemoved.joinToString()} assignment${if (otherRemoved.size == 1) "" else "s"}."
        }
    }
    val removed = buildList {
        if (previous.x != null && next.x == null) add("X")
        if (previous.values.isNotEmpty() && next.values.isEmpty()) add("values")
        if (previous.series != null && next.series == null) add("series")
        if (previous.size != null && next.size == null) add("size")
    }
    return removed
        .takeIf { it.isNotEmpty() }
        ?.let {
            "Removed incompatible ${it.joinToString()} assignment${if (it.size == 1) "" else "s"}."
        }
}

internal fun visualizationOptionsSummary(
    config: VisualizationConfig,
    effectiveType: ChartType?,
): String =
    when (effectiveType) {
        ChartType.Bar -> "${config.sort.target.name.lowercase()} sort · Top ${config.topN}"
        ChartType.Line -> "${config.sort.target.name.lowercase()} sort"
        ChartType.Histogram -> {
            val binSize =
                (config.x?.grouping as? PivotGrouping.NumberBin)?.size?.trim()?.takeIf { raw ->
                    raw.toBigDecimalOrNull()?.let { it > BigDecimal.ZERO } == true
                }
            if (binSize != null) "Bin size $binSize" else "Auto bins"
        }
        ChartType.Scatter,
        ChartType.Kpi -> if (config.values.isNotEmpty()) "Format & title" else "Title & display"
        ChartType.Auto,
        null -> "Title & display"
    }

internal fun normalizeMeasureAxes(
    values: List<VisualizationMeasure>,
): List<VisualizationMeasure> =
    if (values.none { it.axis == ValueAxis.Primary }) {
        values.mapIndexed { index, measure ->
            if (index == 0) measure.copy(axis = ValueAxis.Primary) else measure
        }
    } else {
        values
    }
