package com.safedb.explore

import com.safedb.model.ColumnCategory
import com.safedb.model.QueryResult
import com.safedb.model.ResultCell
import com.safedb.model.TableRef
import com.safedb.model.classifyColumn
import com.safedb.model.isNumeric
import com.safedb.model.isTemporal

enum class VisualizationTemplateId {
    Breakdown,
    TrendOverTime,
    TopValues,
    CompareCategories,
    Distribution,
    Relationship,
    SingleValue,
}

data class VisualizationTemplate(
    val id: VisualizationTemplateId,
    val name: String,
    val description: String,
    val preview: String,
    val score: Int,
    val result: VisualizationTemplateBuildResult,
)

sealed class VisualizationTemplateBuildResult {
    data class Ready(val config: VisualizationConfig) : VisualizationTemplateBuildResult()

    data class Unavailable(val reason: String) : VisualizationTemplateBuildResult()
}

fun visualizationTemplates(
    sample: QueryResult,
    tables: List<TableRef> = emptyList(),
): List<VisualizationTemplate> {
    val profile = VisualizationSampleProfile(sample, tables)
    return listOf(
        profile.breakdown(),
        profile.trend(),
        profile.topValues(),
        profile.compare(),
        profile.distribution(),
        profile.relationship(),
        profile.kpi(),
    )
}

fun suggestedVisualizationTemplates(
    sample: QueryResult,
    tables: List<TableRef> = emptyList(),
    limit: Int = 5,
): List<VisualizationTemplate> =
    visualizationTemplates(sample, tables)
        .filter { it.result is VisualizationTemplateBuildResult.Ready }
        .sortedWith(
            compareByDescending<VisualizationTemplate> { it.score }.thenBy { it.id.ordinal }
        )
        .take(limit.coerceIn(3, 5))

private class VisualizationSampleProfile(sample: QueryResult, tables: List<TableRef>) {
    private val labels = displayColumnLabels(sample.columns, tables)
    private val fields =
        sample.columns.mapIndexed { index, column ->
            val values =
                sample.rows.mapNotNull { it.getOrNull(index) }.filterNot { it is ResultCell.Null }
            ProfileField(
                column = column.name,
                label = labels[column.name] ?: displayColumnLabel(column.name),
                category = classifyColumn(column.dataType),
                cardinality = values.map(::pivotCellKey).distinct().size,
                nonNull = values.size,
            )
        }
    private val numeric = fields.filter { it.category.isNumeric() && !it.identifierLike }
    private val temporal = fields.filter { it.category.isTemporal() && !it.identifierLike }
    private val dimensions =
        fields
            .filter {
                !it.identifierLike &&
                    it.category !in setOf(ColumnCategory.Binary, ColumnCategory.Json)
            }
            .sortedWith(
                compareByDescending<ProfileField> {
                        when (it.category) {
                            ColumnCategory.Text,
                            ColumnCategory.Bool -> 3
                            ColumnCategory.Date,
                            ColumnCategory.DateTime -> 2
                            else -> 1
                        }
                    }
                    .thenBy { if (it.cardinality in 2..50) 0 else 1 }
            )

    fun breakdown(): VisualizationTemplate {
        val dimension =
            dimensions.firstOrNull()
                ?: return unavailable(
                    VisualizationTemplateId.Breakdown,
                    "Breakdown",
                    "Count rows by a category.",
                    "Category · Count",
                    "Needs a groupable field.",
                )
        return ready(
            VisualizationTemplateId.Breakdown,
            "Breakdown",
            "Count rows grouped by one field.",
            "${dimension.label} · Count rows",
            75 + readableCardinalityScore(dimension),
            VisualizationConfig(
                chartType = ChartType.Bar,
                x = dimension.encoding(),
                values = listOf(VisualizationMeasure.countRows()),
            ),
        )
    }

    fun trend(): VisualizationTemplate {
        val date =
            temporal.firstOrNull()
                ?: return unavailable(
                    VisualizationTemplateId.TrendOverTime,
                    "Trend over time",
                    "Track a value over time.",
                    "Date by month · Sum",
                    "Needs a date or datetime field.",
                )
        val value =
            numeric.firstOrNull()
                ?: return unavailable(
                    VisualizationTemplateId.TrendOverTime,
                    "Trend over time",
                    "Track a value over time.",
                    "Date by month · Sum",
                    "Needs a numeric value.",
                )
        return ready(
            VisualizationTemplateId.TrendOverTime,
            "Trend over time",
            "Sum a numeric value by month.",
            "${date.label} by month · Sum ${value.label}",
            100 + if (date.nonNull > 1) 10 else 0,
            VisualizationConfig(
                chartType = ChartType.Line,
                x = date.encoding(PivotGrouping.Date(DateGroupUnit.Month)),
                values = listOf(value.sumMeasure()),
                sort = VisualizationSort(VisualizationSortTarget.Source, SortDir.Asc),
            ),
        )
    }

    fun topValues(): VisualizationTemplate {
        val dimension =
            dimensions.firstOrNull()
                ?: return unavailable(
                    VisualizationTemplateId.TopValues,
                    "Top values",
                    "Rank the largest groups.",
                    "Top 10 · Horizontal bar",
                    "Needs a groupable field.",
                )
        val measure = numeric.firstOrNull()?.sumMeasure() ?: VisualizationMeasure.countRows()
        return ready(
            VisualizationTemplateId.TopValues,
            "Top values",
            "Show the ten largest groups.",
            "${dimension.label} · ${measure.label} · Top 10",
            80 + readableCardinalityScore(dimension),
            VisualizationConfig(
                chartType = ChartType.Bar,
                x = dimension.encoding(),
                values = listOf(measure),
                topN = 10,
                barOrientation = BarOrientation.Horizontal,
            ),
        )
    }

    fun compare(): VisualizationTemplate {
        if (dimensions.size < 2) {
            return unavailable(
                VisualizationTemplateId.CompareCategories,
                "Compare categories",
                "Compare one category across another.",
                "Category · Series · Value",
                "Needs at least two groupable fields.",
            )
        }
        val measure = numeric.firstOrNull()?.sumMeasure() ?: VisualizationMeasure.countRows()
        return ready(
            VisualizationTemplateId.CompareCategories,
            "Compare categories",
            "Compare one category across another.",
            "${dimensions[0].label} by ${dimensions[1].label} · ${measure.label}",
            70 + readableCardinalityScore(dimensions[0]) + readableCardinalityScore(dimensions[1]),
            VisualizationConfig(
                chartType = ChartType.Bar,
                x = dimensions[0].encoding(),
                values = listOf(measure),
                series = dimensions[1].encoding(),
            ),
        )
    }

    fun distribution(): VisualizationTemplate {
        val field =
            numeric.firstOrNull()
                ?: return unavailable(
                    VisualizationTemplateId.Distribution,
                    "Distribution",
                    "See how numeric values are distributed.",
                    "Numeric bins · Count",
                    "Needs a numeric field.",
                )
        return ready(
            VisualizationTemplateId.Distribution,
            "Distribution",
            "See how numeric values are distributed.",
            "${field.label} bins · Count",
            65 + if (field.cardinality > 5) 10 else 0,
            VisualizationConfig(
                chartType = ChartType.Histogram,
                x = field.encoding(PivotGrouping.NumberBin(size = "")),
            ),
        )
    }

    fun relationship(): VisualizationTemplate {
        if (numeric.size < 2) {
            return unavailable(
                VisualizationTemplateId.Relationship,
                "Relationship",
                "Compare two numeric fields.",
                "Numeric X · Numeric Y",
                "Needs at least two numeric fields.",
            )
        }
        return ready(
            VisualizationTemplateId.Relationship,
            "Relationship",
            "Compare two numeric fields row by row.",
            "${numeric[0].label} × ${numeric[1].label}",
            60 + if (numeric[0].nonNull > 2 && numeric[1].nonNull > 2) 10 else 0,
            VisualizationConfig(
                chartType = ChartType.Scatter,
                x = numeric[0].encoding(),
                values = listOf(numeric[1].rawMeasure()),
            ),
        )
    }

    fun kpi(): VisualizationTemplate {
        val measure = numeric.firstOrNull()?.sumMeasure() ?: VisualizationMeasure.countRows()
        return ready(
            VisualizationTemplateId.SingleValue,
            "Single value",
            "Summarize the sample in one headline value.",
            measure.label,
            55,
            VisualizationConfig(chartType = ChartType.Kpi, values = listOf(measure)),
        )
    }

    private fun ready(
        id: VisualizationTemplateId,
        name: String,
        description: String,
        preview: String,
        score: Int,
        config: VisualizationConfig,
    ) =
        VisualizationTemplate(
            id,
            name,
            description,
            preview,
            score,
            VisualizationTemplateBuildResult.Ready(config),
        )

    private fun unavailable(
        id: VisualizationTemplateId,
        name: String,
        description: String,
        preview: String,
        reason: String,
    ) =
        VisualizationTemplate(
            id,
            name,
            description,
            preview,
            0,
            VisualizationTemplateBuildResult.Unavailable(reason),
        )

    private fun readableCardinalityScore(field: ProfileField): Int =
        when (field.cardinality) {
            in 2..12 -> 15
            in 13..50 -> 8
            else -> 0
        }
}

private data class ProfileField(
    val column: String,
    val label: String,
    val category: ColumnCategory,
    val cardinality: Int,
    val nonNull: Int,
) {
    val identifierLike: Boolean
        get() {
            val normalized = label.lowercase()
            val raw = column.lowercase()
            return normalized == "id" ||
                normalized.endsWith("_id") ||
                normalized.endsWith(" id") ||
                raw == "id" ||
                raw.endsWith("_id")
        }

    fun encoding(grouping: PivotGrouping = PivotGrouping.Exact) =
        VisualizationField(column, label, grouping)

    fun sumMeasure() =
        VisualizationMeasure(
            alias = "sum_$column",
            fn = MeasureFn.Sum,
            sourceColumn = column,
            label = "Sum $label",
        )

    fun rawMeasure() =
        VisualizationMeasure(
            alias = "raw_$column",
            fn = MeasureFn.Sum,
            sourceColumn = column,
            label = label,
            aggregate = false,
        )
}
