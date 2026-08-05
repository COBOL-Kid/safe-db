package com.safedb.ui

import com.safedb.explore.DateGroupUnit
import com.safedb.explore.ExploreConfig
import com.safedb.explore.MeasureFn
import com.safedb.explore.PivotDimension
import com.safedb.explore.PivotFilter
import com.safedb.explore.PivotGrouping
import com.safedb.explore.PivotMeasure
import com.safedb.explore.ValueFilterOp
import com.safedb.model.ColumnCategory
import com.safedb.model.QueryResult
import java.util.UUID

internal enum class ExploreBuiltinTemplateId {
    Breakdown,
    TrendOverTime,
    TopN,
    CompareCategories,
}

internal data class ExploreTemplateDefinition(
    val id: ExploreBuiltinTemplateId,
    val name: String,
    val description: String,
    val preview: String,
    val build: (QueryResult, List<ExploreFieldOption>) -> ExploreTemplateBuildResult,
)

internal sealed class ExploreTemplateBuildResult {
    data class Ready(val config: ExploreConfig) : ExploreTemplateBuildResult()

    data class Unavailable(val reason: String) : ExploreTemplateBuildResult()
}

internal interface ExploreTemplateCatalog {
    fun builtinTemplates(): List<ExploreTemplateDefinition>

    fun userTemplates(): List<ExploreTemplateDefinition> = emptyList()
}

internal object BuiltinExploreTemplateCatalog : ExploreTemplateCatalog {
    override fun builtinTemplates(): List<ExploreTemplateDefinition> =
        listOf(
            ExploreTemplateDefinition(
                id = ExploreBuiltinTemplateId.Breakdown,
                name = "Breakdown",
                description = "Count rows grouped by one field.",
                preview = "1 row field · Count rows",
                build = ::buildBreakdownTemplate,
            ),
            ExploreTemplateDefinition(
                id = ExploreBuiltinTemplateId.TrendOverTime,
                name = "Trend over time",
                description = "Sum a numeric field by month on a date column.",
                preview = "Date by month · Sum",
                build = ::buildTrendOverTimeTemplate,
            ),
            ExploreTemplateDefinition(
                id = ExploreBuiltinTemplateId.TopN,
                name = "Top N",
                description = "Show the top 10 groups by a summarized value.",
                preview = "1 row field · Measure · Top 10 filter",
                build = ::buildTopNTemplate,
            ),
            ExploreTemplateDefinition(
                id = ExploreBuiltinTemplateId.CompareCategories,
                name = "Compare categories",
                description = "Cross-tabulate two dimensions with one measure.",
                preview = "Row + column fields · 1 measure",
                build = ::buildCompareCategoriesTemplate,
            ),
        )
}

internal fun resolveExploreTemplate(
    templateId: ExploreBuiltinTemplateId,
    sample: QueryResult,
    fields: List<ExploreFieldOption>,
    catalog: ExploreTemplateCatalog = BuiltinExploreTemplateCatalog,
): ExploreTemplateBuildResult {
    val template =
        catalog.builtinTemplates().firstOrNull { it.id == templateId }
            ?: return ExploreTemplateBuildResult.Unavailable("Recipe not found.")
    return template.build(sample, fields)
}

internal fun listExploreTemplates(
    sample: QueryResult,
    fields: List<ExploreFieldOption>,
    catalog: ExploreTemplateCatalog = BuiltinExploreTemplateCatalog,
): List<ExploreTemplateListItem> {
    val builtin =
        catalog.builtinTemplates().map { template ->
            val result = template.build(sample, fields)
            ExploreTemplateListItem(
                id = template.id,
                name = template.name,
                description = template.description,
                preview = template.preview,
                available = result is ExploreTemplateBuildResult.Ready,
                unavailableReason = (result as? ExploreTemplateBuildResult.Unavailable)?.reason,
                isUserTemplate = false,
            )
        }
    val user =
        catalog.userTemplates().map { template ->
            val result = template.build(sample, fields)
            ExploreTemplateListItem(
                id = template.id,
                name = template.name,
                description = template.description,
                preview = template.preview,
                available = result is ExploreTemplateBuildResult.Ready,
                unavailableReason = (result as? ExploreTemplateBuildResult.Unavailable)?.reason,
                isUserTemplate = true,
            )
        }
    return builtin + user
}

internal data class ExploreTemplateListItem(
    val id: ExploreBuiltinTemplateId,
    val name: String,
    val description: String,
    val preview: String,
    val available: Boolean,
    val unavailableReason: String?,
    val isUserTemplate: Boolean,
)

private fun buildBreakdownTemplate(
    sample: QueryResult,
    fields: List<ExploreFieldOption>,
): ExploreTemplateBuildResult {
    val dimension =
        firstDimensionField(fields)
            ?: return ExploreTemplateBuildResult.Unavailable(
                "Needs at least one field in the sample."
            )
    return ExploreTemplateBuildResult.Ready(
        ExploreConfig(
            rowDimensions = listOf(dimension.asDimension().withFreshId()),
            measures = listOf(PivotMeasure.countRows()),
        )
    )
}

private fun firstNumericField(fields: List<ExploreFieldOption>): ExploreFieldOption? =
    fields.firstOrNull {
        it.category in NUMERIC_CATEGORIES && !looksLikeUniqueIdentifier(it.column, it.label)
    }

private fun buildTrendOverTimeTemplate(
    sample: QueryResult,
    fields: List<ExploreFieldOption>,
): ExploreTemplateBuildResult {
    val temporal =
        fields.firstOrNull { it.category in TEMPORAL_CATEGORIES }
            ?: return ExploreTemplateBuildResult.Unavailable(
                "Needs a date or datetime column in your sample."
            )
    val numeric =
        firstNumericField(fields)
            ?: return ExploreTemplateBuildResult.Unavailable(
                "Needs a numeric column in your sample."
            )
    return ExploreTemplateBuildResult.Ready(
        ExploreConfig(
            rowDimensions =
                listOf(
                    temporal
                        .asDimension()
                        .copy(
                            id = freshDimensionId(temporal.column),
                            grouping = PivotGrouping.Date(DateGroupUnit.Month),
                        )
                ),
            measures = listOf(measureFor(numeric, MeasureFn.Sum)),
        )
    )
}

private fun buildTopNTemplate(
    sample: QueryResult,
    fields: List<ExploreFieldOption>,
): ExploreTemplateBuildResult {
    val dimension =
        firstDimensionField(fields)
            ?: return ExploreTemplateBuildResult.Unavailable(
                "Needs at least one field in the sample."
            )
    val numeric = firstNumericField(fields)
    val measure = numeric?.let { measureFor(it, MeasureFn.Sum) } ?: PivotMeasure.countRows()
    return ExploreTemplateBuildResult.Ready(
        ExploreConfig(
            rowDimensions = listOf(dimension.asDimension().withFreshId()),
            measures = listOf(measure),
            filters =
                listOf(
                    PivotFilter.Value(
                        id = UUID.randomUUID().toString(),
                        column = dimension.column,
                        label = dimension.label,
                        measureAlias = measure.alias,
                        op = ValueFilterOp.Top,
                        count = 10,
                    )
                ),
        )
    )
}

private fun buildCompareCategoriesTemplate(
    sample: QueryResult,
    fields: List<ExploreFieldOption>,
): ExploreTemplateBuildResult {
    val dimensions = dimensionFields(fields)
    if (dimensions.size < 2) {
        return ExploreTemplateBuildResult.Unavailable(
            "Needs at least two groupable fields in your sample."
        )
    }
    val row = dimensions[0]
    val column = dimensions[1]
    val numeric = firstNumericField(fields)
    val measure = numeric?.let { measureFor(it, MeasureFn.Sum) } ?: PivotMeasure.countRows()
    return ExploreTemplateBuildResult.Ready(
        ExploreConfig(
            rowDimensions = listOf(row.asDimension().withFreshId()),
            columnDimensions = listOf(column.asDimension().withFreshId()),
            measures = listOf(measure),
        )
    )
}

private fun firstDimensionField(fields: List<ExploreFieldOption>): ExploreFieldOption? =
    dimensionFields(fields).firstOrNull()

private fun dimensionFields(fields: List<ExploreFieldOption>): List<ExploreFieldOption> =
    fields.filterNot {
        looksLikeUniqueIdentifier(it.column, it.label)
    }

private fun looksLikeUniqueIdentifier(column: String, label: String): Boolean {
    val normalized = label.lowercase()
    return normalized == "id" ||
        normalized.endsWith("_id") ||
        normalized.endsWith(" id") ||
        column.lowercase().endsWith("_id") ||
        column.lowercase() == "id"
}

private fun PivotDimension.withFreshId(): PivotDimension = copy(id = freshDimensionId(column))

private fun freshDimensionId(column: String): String = "$column:${UUID.randomUUID()}"

private val NUMERIC_CATEGORIES = setOf(ColumnCategory.Integer, ColumnCategory.Decimal)

private val TEMPORAL_CATEGORIES = setOf(ColumnCategory.Date, ColumnCategory.DateTime)
