package com.safedb.ui

import com.safedb.explore.ExploreConfig
import com.safedb.explore.ExploreSort
import com.safedb.explore.ExploreSortTarget
import com.safedb.explore.LabelFilterOp
import com.safedb.explore.MeasureFn
import com.safedb.explore.PivotDimension
import com.safedb.explore.PivotFilter
import com.safedb.explore.PivotMeasure
import com.safedb.explore.SortDir
import com.safedb.explore.ValueFilterOp
import com.safedb.explore.displayColumnLabels
import com.safedb.model.ColumnCategory
import com.safedb.model.QueryResult
import com.safedb.model.TableRef
import com.safedb.model.classifyColumn
import java.util.UUID

internal data class ExploreFieldOption(
    val column: String,
    val label: String,
    val dataType: String,
    val category: ColumnCategory,
) {
    fun asDimension(): PivotDimension = PivotDimension(column = column, label = label)
}

internal fun buildExploreFieldOptions(
    sample: QueryResult,
    tables: List<TableRef>,
): List<ExploreFieldOption> {
    val labels = displayColumnLabels(sample.columns, tables)
    return sample.columns.map { column ->
        ExploreFieldOption(
            column = column.name,
            label = labels.getValue(column.name),
            dataType = column.dataType,
            category = classifyColumn(column.dataType),
        )
    }
}

internal fun availableMeasureFunctions(field: ExploreFieldOption): List<MeasureFn> = when (field.category) {
    ColumnCategory.Integer,
    ColumnCategory.Decimal,
    -> listOf(
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
    ColumnCategory.DateTime,
    -> listOf(MeasureFn.Min, MeasureFn.Max, MeasureFn.CountDistinct)

    ColumnCategory.Text,
    ColumnCategory.Bool,
    -> listOf(MeasureFn.CountDistinct, MeasureFn.Min, MeasureFn.Max)

    ColumnCategory.Binary,
    ColumnCategory.Json,
    ColumnCategory.Other,
    -> listOf(MeasureFn.CountDistinct)
}

internal fun measureFor(field: ExploreFieldOption, function: MeasureFn): PivotMeasure {
    require(function != MeasureFn.Count) { "Count rows does not use a source field" }
    val functionLabel = when (function) {
        MeasureFn.CountDistinct -> "Distinct"
        MeasureFn.CountNumbers -> "Count numbers"
        MeasureFn.Sum -> "Sum"
        MeasureFn.Avg -> "Average"
        MeasureFn.Min -> "Minimum"
        MeasureFn.Max -> "Maximum"
        MeasureFn.Product -> "Product"
        MeasureFn.StdDev -> "StdDev"
        MeasureFn.StdDevPopulation -> "StdDevP"
        MeasureFn.Variance -> "Variance"
        MeasureFn.VariancePopulation -> "VarianceP"
        MeasureFn.Count -> error("Count rows does not use a source field")
    }
    return PivotMeasure(
        alias = "${function.name.lowercase()}_${field.column}_${UUID.randomUUID().toString().take(8)}",
        fn = function,
        sourceColumn = field.column,
        label = "$functionLabel ${field.label}",
    )
}

internal fun measureFunctionLabel(function: MeasureFn): String = when (function) {
    MeasureFn.Count -> "Count rows"
    MeasureFn.CountNumbers -> "Count numbers"
    MeasureFn.CountDistinct -> "Count distinct"
    MeasureFn.Sum -> "Sum"
    MeasureFn.Avg -> "Average"
    MeasureFn.Min -> "Minimum"
    MeasureFn.Max -> "Maximum"
    MeasureFn.Product -> "Product"
    MeasureFn.StdDev -> "Standard deviation"
    MeasureFn.StdDevPopulation -> "Population standard deviation"
    MeasureFn.Variance -> "Variance"
    MeasureFn.VariancePopulation -> "Population variance"
}

internal fun toggleExploreSort(config: ExploreConfig, target: ExploreSortTarget): ExploreConfig {
    val current = config.sort
    val defaultDirection = if (target is ExploreSortTarget.Measure) SortDir.Desc else SortDir.Asc
    val next = if (current?.target == target) {
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

internal fun exploreConfigSummary(config: ExploreConfig): String {
    val parts = mutableListOf<String>()
    val rowLabels = config.rowDimensions.map { it.label }
    val columnLabels = config.effectiveColumnDimensions.map { it.label }
    when {
        rowLabels.isNotEmpty() && columnLabels.isNotEmpty() -> parts += "${rowLabels.joinToString(" → ")} × ${columnLabels.joinToString(" → ")}"
        rowLabels.isNotEmpty() -> parts += rowLabels.joinToString(" → ")
        columnLabels.isNotEmpty() -> parts += columnLabels.joinToString(" → ")
    }
    if (config.measures.isNotEmpty()) {
        parts += config.measures.joinToString(", ") { it.label }
    }
    if (config.filters.isNotEmpty()) {
        val count = config.filters.size
        parts += "$count filter${if (count == 1) "" else "s"}"
    }
    return parts.joinToString(" · ").ifEmpty { "No grouping configured" }
}

internal fun filterSupportingText(filter: PivotFilter, memberCount: Int? = null): String = when (filter) {
    is PivotFilter.Members -> {
        val selection = when {
            filter.includedKeys.isEmpty() -> "All values"
            memberCount != null && filter.includedKeys.size == memberCount -> "All values"
            else -> "${filter.includedKeys.size} selected"
        }
        if (filter.pinned) "$selection · Pinned" else selection
    }
    is PivotFilter.Label -> "${labelFilterOpLabel(filter.op)} \"${filter.value}\""
    is PivotFilter.Value -> valueFilterSupportingText(filter)
}

private fun valueFilterSupportingText(filter: PivotFilter.Value): String = when (filter.op) {
    ValueFilterOp.Top -> "Top ${filter.count}"
    ValueFilterOp.Bottom -> "Bottom ${filter.count}"
    ValueFilterOp.Between -> "Between ${filter.value} and ${filter.secondValue.orEmpty()}"
    else -> "${valueFilterOpLabel(filter.op)} ${filter.value}"
}

private fun labelFilterOpLabel(op: LabelFilterOp): String = when (op) {
    LabelFilterOp.Equals -> "Equals"
    LabelFilterOp.Contains -> "Contains"
    LabelFilterOp.StartsWith -> "Starts with"
    LabelFilterOp.EndsWith -> "Ends with"
}

private fun valueFilterOpLabel(op: ValueFilterOp): String = when (op) {
    ValueFilterOp.GreaterThan -> ">"
    ValueFilterOp.GreaterThanOrEqual -> "≥"
    ValueFilterOp.LessThan -> "<"
    ValueFilterOp.LessThanOrEqual -> "≤"
    ValueFilterOp.Between -> "Between"
    ValueFilterOp.Top -> "Top"
    ValueFilterOp.Bottom -> "Bottom"
}
