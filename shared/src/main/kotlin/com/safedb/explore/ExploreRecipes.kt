package com.safedb.explore

import com.safedb.model.QueryResult
import com.safedb.model.QuerySpec
import com.safedb.model.ResultColumn

fun resolveRecipeFields(
    recipe: ExploreRecipe,
    sample: QueryResult,
    spec: QuerySpec,
    manual: Map<String, String> = emptyMap(),
): RecipeFieldMapping {
    val labels = displayColumnLabels(sample.columns, spec.tables)
    val tableByAlias = spec.tables.associate { it.alias to it.name }
    val candidates =
        sample.columns.map { column ->
            val alias = column.name.substringBefore("__", missingDelimiterValue = "")
            RecipeField(
                column = column.name,
                label = labels[column.name] ?: displayColumnLabel(column.name),
                dataType = column.dataType,
                sourceTable = tableByAlias[alias],
            )
        }
    val resolved = linkedMapOf<String, String>()
    recipe.requiredFields.forEach { required ->
        val manuallySelected = manual[required.column]
        if (
            manuallySelected != null &&
                candidates.any {
                    it.column == manuallySelected && compatibleTypes(required.dataType, it.dataType)
                }
        ) {
            resolved[required.column] = manuallySelected
            return@forEach
        }
        candidates
            .firstOrNull { candidate ->
                candidate.column == required.column &&
                    compatibleTypes(required.dataType, candidate.dataType) &&
                    (required.sourceTable == null || candidate.sourceTable == required.sourceTable)
            }
            ?.let {
                resolved[required.column] = it.column
                return@forEach
            }
        val matches = candidates.filter { candidate ->
            candidate.label.equals(required.label, ignoreCase = true) &&
                compatibleTypes(required.dataType, candidate.dataType) &&
                (required.sourceTable == null || candidate.sourceTable == required.sourceTable)
        }
        if (matches.size == 1) resolved[required.column] = matches.single().column
    }
    return RecipeFieldMapping(
        resolved = resolved,
        unresolved = recipe.requiredFields.filter { it.column !in resolved },
    )
}

fun compatibleTypes(left: String, right: String): Boolean =
    dataTypeFamily(left) == dataTypeFamily(right)

/**
 * The single traversal for every persisted column reference in a recipe. Keep new field-bearing
 * configuration properties here so discovery and remapping cannot silently drift apart.
 */
fun ExploreRecipe.mapColumnReferences(transform: (String) -> String): ExploreRecipe =
    copy(
        pivot = pivot?.mapColumnReferences(transform),
        worksheet = worksheet?.mapColumnReferences(transform),
        visualization = visualization?.mapColumnReferences(transform),
        requiredFields =
            requiredFields.map { field -> field.copy(column = transform(field.column)) },
    )

fun remapRecipe(recipe: ExploreRecipe, mapping: Map<String, String>): ExploreRecipe =
    recipe.mapColumnReferences { column ->
        mapping[column] ?: column
    }

fun recipeColumnReferences(
    pivot: ExploreConfig?,
    worksheet: WorksheetConfig?,
    visualization: VisualizationConfig?,
): Set<String> {
    val references = linkedSetOf<String>()
    ExploreRecipe(
            id = "references",
            name = "references",
            createdAt = "0",
            updatedAt = "0",
            defaultMode =
                pivot?.let { ExploreMode.Pivot }
                    ?: worksheet?.let { ExploreMode.Worksheet }
                    ?: ExploreMode.Visualization,
            pivot = pivot,
            worksheet = worksheet,
            visualization = visualization,
        )
        .mapColumnReferences { column ->
            references += column
            column
        }
    return references
}

private fun ExploreConfig.mapColumnReferences(transform: (String) -> String): ExploreConfig =
    copy(
        rowDimensions = rowDimensions.map { it.copy(column = transform(it.column)) },
        columnDimensions = effectiveColumnDimensions.map { it.copy(column = transform(it.column)) },
        columnDimension = null,
        measures =
            measures.map { measure ->
                measure.copy(sourceColumn = measure.sourceColumn?.let(transform))
            },
        filters =
            filters.map { filter ->
                when (filter) {
                    is PivotFilter.Members -> filter.copy(column = transform(filter.column))
                    is PivotFilter.Label -> filter.copy(column = transform(filter.column))
                    is PivotFilter.Value -> filter.copy(column = transform(filter.column))
                }
            },
        sort =
            sort?.let { current ->
                current.copy(
                    target =
                        when (val target = current.target) {
                            is ExploreSortTarget.Dimension ->
                                target.copy(column = transform(target.column))
                            is ExploreSortTarget.Measure -> target
                        }
                )
            },
    )

private fun WorksheetConfig.mapColumnReferences(transform: (String) -> String): WorksheetConfig =
    copy(
        groups = groups.map { it.copy(column = transform(it.column)) },
        sorts =
            sorts.map { sort -> sort.copy(target = sort.target.mapColumnReferences(transform)) },
        filters = filters.map { it.copy(column = transform(it.column)) },
        columnLayout =
            columnLayout.map { entry ->
                entry.copy(ref = entry.ref.mapColumnReferences(transform))
            },
        calculations =
            calculations.map { calculation ->
                when (calculation) {
                    is WorksheetCalculation.RowFormula ->
                        calculation.copy(
                            formula = mapFormulaReferences(calculation.formula, transform)
                        )
                    is WorksheetCalculation.Aggregate ->
                        calculation.copy(
                            sourceColumn = calculation.sourceColumn?.let(transform),
                            groupColumn = calculation.groupColumn?.let(transform),
                        )
                    is WorksheetCalculation.GroupFormula ->
                        calculation.copy(groupColumn = calculation.groupColumn?.let(transform))
                    is WorksheetCalculation.Window ->
                        calculation.copy(
                            source = calculation.source.mapColumnReferences(transform),
                            groupColumn = calculation.groupColumn?.let(transform),
                            restartColumns = calculation.restartColumns.map(transform),
                        )
                }
            },
    )

private fun VisualizationConfig.mapColumnReferences(
    transform: (String) -> String
): VisualizationConfig =
    copy(
        x = x?.copy(column = transform(x.column)),
        values =
            values.map { value -> value.copy(sourceColumn = value.sourceColumn?.let(transform)) },
        series = series?.copy(column = transform(series.column)),
        size = size?.copy(column = transform(size.column)),
        filters =
            filters.map { filter ->
                when (filter) {
                    is PivotFilter.Members -> filter.copy(column = transform(filter.column))
                    is PivotFilter.Label -> filter.copy(column = transform(filter.column))
                    is PivotFilter.Value -> filter.copy(column = transform(filter.column))
                }
            },
    )

private fun WorksheetValueRef.mapColumnReferences(
    transform: (String) -> String
): WorksheetValueRef =
    when (this) {
        is WorksheetValueRef.Column -> copy(column = transform(column))
        is WorksheetValueRef.Calculation -> this
    }

private fun mapFormulaReferences(formula: String, transform: (String) -> String): String =
    Regex("\\[([^]]+)]").replace(formula) { match ->
        val reference = match.groupValues[1].trim()
        "[${transform(reference)}]"
    }

private fun dataTypeFamily(raw: String): String {
    val type = raw.lowercase()
    return when {
        listOf("int", "decimal", "numeric", "number", "real", "double", "float", "money")
            .any(type::contains) -> "number"
        listOf("date", "time").any(type::contains) -> "temporal"
        listOf("bool", "bit").any(type::contains) -> "boolean"
        listOf("binary", "blob", "byte", "varbinary").any(type::contains) -> "binary"
        else -> "text"
    }
}

fun recipeCandidateColumns(field: RecipeField, columns: List<ResultColumn>): List<ResultColumn> =
    columns.filter {
        compatibleTypes(field.dataType, it.dataType)
    }
