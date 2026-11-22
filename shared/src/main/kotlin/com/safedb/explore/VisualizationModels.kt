package com.safedb.explore

import com.safedb.model.QueryResult
import kotlinx.serialization.Serializable

const val VISUALIZATION_SCHEMA_VERSION = 1

@Serializable
data class VisualizationConfig(
    val schemaVersion: Int = VISUALIZATION_SCHEMA_VERSION,
    val chartType: ChartType = ChartType.Auto,
    val x: VisualizationField? = null,
    val values: List<VisualizationMeasure> = emptyList(),
    val series: VisualizationField? = null,
    val size: VisualizationField? = null,
    val filters: List<PivotFilter> = emptyList(),
    val sort: VisualizationSort = VisualizationSort(),
    val topN: Int = 20,
    val barArrangement: BarArrangement = BarArrangement.Grouped,
    val barOrientation: BarOrientation = BarOrientation.Vertical,
    val title: String = "",
    val showLabels: Boolean = false,
) {
    fun isConfigured(): Boolean = x != null || values.isNotEmpty()

    fun isStackedBar(resolvedType: ChartType?): Boolean =
        resolvedType == ChartType.Bar && barArrangement == BarArrangement.Stacked

    fun supportsSecondaryAxis(resolvedType: ChartType?): Boolean =
        series == null &&
            values.size > 1 &&
            (resolvedType == ChartType.Line ||
                (resolvedType == ChartType.Bar && barArrangement == BarArrangement.Grouped))

    fun hasMixedAxes(): Boolean =
        values.any { it.axis == ValueAxis.Primary } && values.any { it.axis == ValueAxis.Secondary }

    fun usesSecondaryAxis(resolvedType: ChartType?): Boolean =
        supportsSecondaryAxis(resolvedType) && hasMixedAxes()

    fun validate(): VisualizationConfig {
        require(schemaVersion == VISUALIZATION_SCHEMA_VERSION) {
            "Unsupported visualization version $schemaVersion"
        }
        require(topN in setOf(10, 20, 50, 100)) { "Visualization Top N must be 10, 20, 50, or 100" }
        return this
    }
}

@Serializable
enum class ChartType {
    Auto,
    Bar,
    Line,
    Scatter,
    Histogram,
    Kpi,
}

@Serializable
data class VisualizationField(
    val column: String,
    val label: String = displayColumnLabel(column),
    val grouping: PivotGrouping = PivotGrouping.Exact,
)

@Serializable
enum class ValueAxis {
    Primary,
    Secondary,
}

@Serializable
data class VisualizationMeasure(
    val alias: String,
    val fn: MeasureFn,
    val sourceColumn: String? = null,
    val label: String = visualizationMeasureLabel(fn, sourceColumn),
    val aggregate: Boolean = true,
    val numberFormat: PivotNumberFormat = PivotNumberFormat(),
    val axis: ValueAxis = ValueAxis.Primary,
) {
    companion object {
        fun countRows(alias: String = "count"): VisualizationMeasure =
            VisualizationMeasure(alias = alias, fn = MeasureFn.Count, label = "Count")
    }
}

@Serializable
data class VisualizationSort(
    val target: VisualizationSortTarget = VisualizationSortTarget.Value,
    val dir: SortDir = SortDir.Desc,
)

private fun visualizationMeasureLabel(fn: MeasureFn, sourceColumn: String?): String =
    when (fn) {
        MeasureFn.Count ->
            if (sourceColumn == null) "Count" else "Count ${displayColumnLabel(sourceColumn)}"
        MeasureFn.CountNumbers -> "Count numbers ${displayColumnLabel(sourceColumn.orEmpty())}"
        MeasureFn.CountDistinct -> "Distinct ${displayColumnLabel(sourceColumn.orEmpty())}"
        MeasureFn.Sum -> "Sum ${displayColumnLabel(sourceColumn.orEmpty())}"
        MeasureFn.Avg -> "Average ${displayColumnLabel(sourceColumn.orEmpty())}"
        MeasureFn.Min -> "Minimum ${displayColumnLabel(sourceColumn.orEmpty())}"
        MeasureFn.Max -> "Maximum ${displayColumnLabel(sourceColumn.orEmpty())}"
        MeasureFn.Product -> "Product ${displayColumnLabel(sourceColumn.orEmpty())}"
        MeasureFn.StdDev -> "StdDev ${displayColumnLabel(sourceColumn.orEmpty())}"
        MeasureFn.StdDevPopulation -> "StdDevP ${displayColumnLabel(sourceColumn.orEmpty())}"
        MeasureFn.Variance -> "Variance ${displayColumnLabel(sourceColumn.orEmpty())}"
        MeasureFn.VariancePopulation -> "VarianceP ${displayColumnLabel(sourceColumn.orEmpty())}"
    }.trim()

@Serializable
enum class VisualizationSortTarget {
    Source,
    Category,
    Value,
}

@Serializable
enum class BarArrangement {
    Grouped,
    Stacked,
}

@Serializable
enum class BarOrientation {
    Vertical,
    Horizontal,
}

data class VisualizationPreview(
    val chartType: ChartType? = null,
    val title: String = "",
    val marks: List<VisualizationMark> = emptyList(),
    val categories: List<String> = emptyList(),
    val series: List<VisualizationSeries> = emptyList(),
    val warnings: List<String> = emptyList(),
    val blockingMessage: String? = null,
    val exportResult: QueryResult? = null,
) {
    val ready: Boolean
        get() = blockingMessage == null && marks.isNotEmpty()

    val hasSecondaryAxis: Boolean
        get() =
            series.any { it.axis == ValueAxis.Primary } &&
                series.any { it.axis == ValueAxis.Secondary }
}

data class VisualizationMark(
    val id: String,
    val xKey: String,
    val xLabel: String,
    val xValue: Double? = null,
    val y: Double,
    val formattedY: String,
    val seriesKey: String,
    val seriesLabel: String,
    val measureAlias: String,
    val measureLabel: String,
    val axis: ValueAxis = ValueAxis.Primary,
    val size: Double? = null,
    val sourceRowIndices: List<Int>,
)

data class VisualizationSeries(
    val key: String,
    val label: String,
    val axis: ValueAxis = ValueAxis.Primary,
)
