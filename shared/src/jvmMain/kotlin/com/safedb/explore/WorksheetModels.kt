package com.safedb.explore

import com.safedb.model.QueryResult
import com.safedb.model.QuerySpec
import com.safedb.model.ResultCell
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val WORKSHEET_SCHEMA_VERSION = 1
const val EXPLORE_RECIPE_SCHEMA_VERSION = 1

@Serializable
enum class ExploreMode {
    Pivot,
    Visualization,
    Worksheet,
}

@Serializable
data class VisualizationConfig(
    val schemaVersion: Int = 1,
)

@Serializable
data class WorksheetConfig(
    val schemaVersion: Int = WORKSHEET_SCHEMA_VERSION,
    val groups: List<WorksheetGroup> = emptyList(),
    val sorts: List<WorksheetSort> = emptyList(),
    val filters: List<WorksheetFilter> = emptyList(),
    val calculations: List<WorksheetCalculation> = emptyList(),
    val collapsedGroupPaths: Set<String> = emptySet(),
)

@Serializable
data class WorksheetGroup(
    val id: String,
    val column: String,
    val label: String = displayColumnLabel(column),
    val grouping: PivotGrouping = PivotGrouping.Exact,
)

@Serializable
data class WorksheetSort(
    val target: WorksheetValueRef,
    val dir: SortDir = SortDir.Asc,
)

@Serializable
sealed class WorksheetValueRef {
    @Serializable
    @SerialName("Column")
    data class Column(val column: String) : WorksheetValueRef()

    @Serializable
    @SerialName("Calculation")
    data class Calculation(val id: String) : WorksheetValueRef()
}

@Serializable
data class WorksheetFilter(
    val id: String,
    val column: String,
    val label: String = displayColumnLabel(column),
    val op: WorksheetFilterOp = WorksheetFilterOp.Contains,
    val value: String = "",
    val secondValue: String? = null,
    val includedKeys: Set<String> = emptySet(),
)

@Serializable
enum class WorksheetFilterOp {
    Members,
    Equals,
    NotEquals,
    Contains,
    StartsWith,
    EndsWith,
    GreaterThan,
    GreaterThanOrEqual,
    LessThan,
    LessThanOrEqual,
    Between,
    IsNull,
    IsNotNull,
}

@Serializable
sealed class WorksheetCalculation {
    abstract val id: String
    abstract val label: String
    abstract val numberFormat: PivotNumberFormat

    @Serializable
    @SerialName("RowFormula")
    data class RowFormula(
        override val id: String,
        override val label: String,
        val formula: String,
        override val numberFormat: PivotNumberFormat = PivotNumberFormat(),
    ) : WorksheetCalculation()

    @Serializable
    @SerialName("Aggregate")
    data class Aggregate(
        override val id: String,
        override val label: String,
        val fn: WorksheetAggregateFn,
        val sourceColumn: String? = null,
        val groupColumn: String? = null,
        override val numberFormat: PivotNumberFormat = PivotNumberFormat(),
    ) : WorksheetCalculation()

    @Serializable
    @SerialName("GroupFormula")
    data class GroupFormula(
        override val id: String,
        override val label: String,
        val formula: String,
        val groupColumn: String? = null,
        override val numberFormat: PivotNumberFormat = PivotNumberFormat(),
    ) : WorksheetCalculation()

    @Serializable
    @SerialName("Window")
    data class Window(
        override val id: String,
        override val label: String,
        val fn: WorksheetWindowFn,
        val source: WorksheetValueRef,
        val grain: WorksheetGrain = WorksheetGrain.DetailRows,
        val groupColumn: String? = null,
        val restartColumns: List<String> = emptyList(),
        val offset: Int = 1,
        override val numberFormat: PivotNumberFormat = PivotNumberFormat(),
    ) : WorksheetCalculation()
}

@Serializable
enum class WorksheetAggregateFn {
    Count,
    CountDistinct,
    Sum,
    Average,
    Minimum,
    Maximum,
}

@Serializable
enum class WorksheetWindowFn {
    RunningTotal,
    RunningAverage,
    PercentOfTotal,
    PreviousValue,
    DifferenceFromPrevious,
    RankAscending,
    RankDescending,
}

@Serializable
enum class WorksheetGrain {
    DetailRows,
    GroupRows,
}

data class WorksheetPreview(
    val columns: List<WorksheetDisplayColumn>,
    val rows: List<WorksheetDisplayRow>,
    val warnings: List<String> = emptyList(),
    val calculationErrorCount: Int = 0,
)

data class WorksheetDisplayColumn(
    val id: String,
    val label: String,
    val dataType: String,
    val sourceColumn: String? = null,
    val calculationId: String? = null,
    val numberFormat: PivotNumberFormat? = null,
)

data class WorksheetDisplayRow(
    val kind: WorksheetRowKind,
    val depth: Int,
    val pathKey: String,
    val label: String? = null,
    val expanded: Boolean = true,
    val cells: List<WorksheetCell>,
    val sourceRowIndex: Int? = null,
)

data class WorksheetCell(
    val value: ResultCell = ResultCell.Null,
    val error: String? = null,
)

enum class WorksheetRowKind {
    Detail,
    Group,
    GrandTotal,
}

@Serializable
data class ExploreWorkspaceState(
    val activeMode: ExploreMode = ExploreMode.Pivot,
    val pivot: ExploreConfig,
    val worksheet: WorksheetConfig = WorksheetConfig(),
    val visualization: VisualizationConfig = VisualizationConfig(),
)

@Serializable
data class RecipeField(
    val column: String,
    val label: String,
    val dataType: String,
    val sourceTable: String? = null,
)

@Serializable
data class ExploreRecipe(
    val schemaVersion: Int = EXPLORE_RECIPE_SCHEMA_VERSION,
    val id: String,
    val name: String,
    val description: String = "",
    val createdAt: String,
    val updatedAt: String,
    val defaultMode: ExploreMode,
    val pivot: ExploreConfig? = null,
    val worksheet: WorksheetConfig? = null,
    val visualization: VisualizationConfig? = null,
    val requiredFields: List<RecipeField> = emptyList(),
    val querySpec: QuerySpec? = null,
) {
    val includedModes: Set<ExploreMode>
        get() = buildSet {
            if (pivot != null) add(ExploreMode.Pivot)
            if (worksheet != null) add(ExploreMode.Worksheet)
            if (visualization != null) add(ExploreMode.Visualization)
        }

    fun validate(): ExploreRecipe {
        require(schemaVersion == EXPLORE_RECIPE_SCHEMA_VERSION) { "Unsupported recipe version $schemaVersion" }
        require(name.isNotBlank()) { "Recipe name is required" }
        require(includedModes.isNotEmpty()) { "Recipe must include at least one Explore mode" }
        require(defaultMode in includedModes) { "Recipe default mode must be included" }
        return this
    }
}

data class RecipeFieldMapping(
    val resolved: Map<String, String>,
    val unresolved: List<RecipeField>,
)

fun ExploreConfig.withoutTransientState(): ExploreConfig = copy(
    collapsedRowPaths = emptySet(),
    collapsedColumnPaths = emptySet(),
)

fun WorksheetConfig.withoutTransientState(): WorksheetConfig = copy(collapsedGroupPaths = emptySet())

fun recipeFields(
    sample: QueryResult,
    spec: QuerySpec,
    pivot: ExploreConfig?,
    worksheet: WorksheetConfig?,
): List<RecipeField> {
    val referenced = linkedSetOf<String>()
    pivot?.let { config ->
        referenced += config.rowDimensions.map { it.column }
        referenced += config.effectiveColumnDimensions.map { it.column }
        referenced += config.measures.mapNotNull { it.sourceColumn }
        referenced += config.filters.map { it.column }
        (config.sort?.target as? ExploreSortTarget.Dimension)?.let { referenced += it.column }
    }
    worksheet?.let { config ->
        referenced += config.groups.map { it.column }
        referenced += config.filters.map { it.column }
        referenced += config.sorts.mapNotNull { (it.target as? WorksheetValueRef.Column)?.column }
        config.calculations.forEach { calculation ->
            when (calculation) {
                is WorksheetCalculation.Aggregate -> calculation.sourceColumn?.let(referenced::add)
                is WorksheetCalculation.GroupFormula -> Unit
                is WorksheetCalculation.RowFormula -> referenced += formulaReferences(calculation.formula)
                is WorksheetCalculation.Window -> {
                    (calculation.source as? WorksheetValueRef.Column)?.let { referenced += it.column }
                    referenced += calculation.restartColumns
                }
            }
        }
    }
    val labels = displayColumnLabels(sample.columns, spec.tables)
    val tableByAlias = spec.tables.associate { it.alias to it.name }
    return sample.columns.filter { it.name in referenced }.map { column ->
        val alias = column.name.substringBefore("__", missingDelimiterValue = "")
        RecipeField(
            column = column.name,
            label = labels[column.name] ?: displayColumnLabel(column.name),
            dataType = column.dataType,
            sourceTable = tableByAlias[alias],
        )
    }
}

fun formulaReferences(formula: String): Set<String> = Regex("\\[([^]]+)]")
    .findAll(formula)
    .map { it.groupValues[1].trim() }
    .filter { it.isNotEmpty() }
    .toSet()
