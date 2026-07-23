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
    val candidates = sample.columns.map { column ->
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
        if (manuallySelected != null && candidates.any { it.column == manuallySelected && compatibleTypes(required.dataType, it.dataType) }) {
            resolved[required.column] = manuallySelected
            return@forEach
        }
        candidates.firstOrNull { candidate ->
            candidate.column == required.column &&
                compatibleTypes(required.dataType, candidate.dataType) &&
                (required.sourceTable == null || candidate.sourceTable == required.sourceTable)
        }?.let {
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

fun compatibleTypes(left: String, right: String): Boolean = dataTypeFamily(left) == dataTypeFamily(right)

fun remapRecipe(recipe: ExploreRecipe, mapping: Map<String, String>): ExploreRecipe = recipe.copy(
    pivot = recipe.pivot?.remapColumns(mapping),
    worksheet = recipe.worksheet?.remapColumns(mapping),
    visualization = recipe.visualization?.remapColumns(mapping),
    requiredFields = recipe.requiredFields.map { field -> field.copy(column = mapping[field.column] ?: field.column) },
)

private fun ExploreConfig.remapColumns(mapping: Map<String, String>): ExploreConfig = copy(
    rowDimensions = rowDimensions.map { it.copy(column = mapping[it.column] ?: it.column) },
    columnDimensions = effectiveColumnDimensions.map { it.copy(column = mapping[it.column] ?: it.column) },
    columnDimension = null,
    measures = measures.map { measure -> measure.copy(sourceColumn = measure.sourceColumn?.let { mapping[it] ?: it }) },
    filters = filters.map { filter ->
        when (filter) {
            is PivotFilter.Members -> filter.copy(column = mapping[filter.column] ?: filter.column)
            is PivotFilter.Label -> filter.copy(column = mapping[filter.column] ?: filter.column)
            is PivotFilter.Value -> filter.copy(column = mapping[filter.column] ?: filter.column)
        }
    },
    sort = sort?.let { current ->
        current.copy(
            target = when (val target = current.target) {
                is ExploreSortTarget.Dimension -> target.copy(column = mapping[target.column] ?: target.column)
                is ExploreSortTarget.Measure -> target
            },
        )
    },
)

private fun WorksheetConfig.remapColumns(mapping: Map<String, String>): WorksheetConfig = copy(
    groups = groups.map { it.copy(column = mapping[it.column] ?: it.column) },
    sorts = sorts.map { sort -> sort.copy(target = sort.target.remap(mapping)) },
    filters = filters.map { it.copy(column = mapping[it.column] ?: it.column) },
    calculations = calculations.map { calculation ->
        when (calculation) {
            is WorksheetCalculation.RowFormula -> calculation.copy(formula = remapFormula(calculation.formula, mapping))
            is WorksheetCalculation.Aggregate -> calculation.copy(
                sourceColumn = calculation.sourceColumn?.let { mapping[it] ?: it },
                groupColumn = calculation.groupColumn?.let { mapping[it] ?: it },
            )
            is WorksheetCalculation.GroupFormula -> calculation.copy(
                groupColumn = calculation.groupColumn?.let { mapping[it] ?: it },
            )
            is WorksheetCalculation.Window -> calculation.copy(
                source = calculation.source.remap(mapping),
                groupColumn = calculation.groupColumn?.let { mapping[it] ?: it },
                restartColumns = calculation.restartColumns.map { mapping[it] ?: it },
            )
        }
    },
)

private fun VisualizationConfig.remapColumns(mapping: Map<String, String>): VisualizationConfig = copy(
    x = x?.copy(column = mapping[x.column] ?: x.column),
    values = values.map { value ->
        value.copy(sourceColumn = value.sourceColumn?.let { mapping[it] ?: it })
    },
    series = series?.copy(column = mapping[series.column] ?: series.column),
    size = size?.copy(column = mapping[size.column] ?: size.column),
    filters = filters.map { filter ->
        when (filter) {
            is PivotFilter.Members -> filter.copy(column = mapping[filter.column] ?: filter.column)
            is PivotFilter.Label -> filter.copy(column = mapping[filter.column] ?: filter.column)
            is PivotFilter.Value -> filter.copy(column = mapping[filter.column] ?: filter.column)
        }
    },
)

private fun WorksheetValueRef.remap(mapping: Map<String, String>): WorksheetValueRef = when (this) {
    is WorksheetValueRef.Column -> copy(column = mapping[column] ?: column)
    is WorksheetValueRef.Calculation -> this
}

private fun remapFormula(formula: String, mapping: Map<String, String>): String = Regex("\\[([^]]+)]").replace(formula) { match ->
    val reference = match.groupValues[1].trim()
    "[${mapping[reference] ?: reference}]"
}

private fun dataTypeFamily(raw: String): String {
    val type = raw.lowercase()
    return when {
        listOf("int", "decimal", "numeric", "number", "real", "double", "float", "money").any(type::contains) -> "number"
        listOf("date", "time").any(type::contains) -> "temporal"
        listOf("bool", "bit").any(type::contains) -> "boolean"
        listOf("binary", "blob", "byte", "varbinary").any(type::contains) -> "binary"
        else -> "text"
    }
}

fun recipeCandidateColumns(field: RecipeField, columns: List<ResultColumn>): List<ResultColumn> =
    columns.filter { compatibleTypes(field.dataType, it.dataType) }
