package com.safedb.query

import com.safedb.model.FilterGroup
import com.safedb.model.FilterNode
import com.safedb.model.FilterValue
import com.safedb.model.GroupConnector
import com.safedb.model.GroupSpec
import com.safedb.model.JoinSpec
import com.safedb.model.QuerySpec
import com.safedb.model.SortSpec
import com.safedb.model.TableInfo

interface QueryHydrationTarget {
    fun clear()

    fun addTable(tableInfo: TableInfo)

    val tables: List<AliasRef>

    fun toggleColumn(alias: String, column: String)

    fun addJoin(join: JoinSpec)

    fun setFilters(group: FilterGroup)

    fun setConnectorOverrides(map: Map<String, GroupConnector>)

    fun setLimit(limit: Int)

    fun setDistinct(distinct: Boolean)

    fun setSorts(sorts: List<SortSpec>)

    fun setGroups(groups: List<GroupSpec>)
}

data class AliasRef(val alias: String)

data class HydrationWarnings(
    val droppedTables: List<String> = emptyList(),
    val droppedColumns: List<String> = emptyList(),
    val droppedJoins: Int = 0,
    val droppedFilters: Boolean = false,
    val droppedSorts: Int = 0,
    val droppedGroups: Int = 0,
)

private fun schemaKey(schema: String, name: String): String = "$schema\u0000$name"

fun countFilterLeaves(group: FilterGroup): Int =
    group.children.sumOf { child ->
        when (child) {
            is FilterNode.Leaf -> 1
            is FilterNode.Group -> countFilterLeaves(child.group)
        }
    }

private fun normalizeFilterValue(value: FilterValue?, dataType: String): FilterValue? {
    if (value == null) return null
    val kind = literalKindForColumn(dataType)
    return when (value) {
        is FilterValue.Single -> FilterValue.Single(value.literal.copy(kind = kind))
        is FilterValue.ListValue ->
            FilterValue.ListValue(value.literals.map { it.copy(kind = kind) })
        is FilterValue.Pair ->
            FilterValue.Pair(value.first.copy(kind = kind), value.second.copy(kind = kind))
    }
}

private fun remapFilterGroup(
    group: FilterGroup,
    aliasMap: Map<String, String>,
    tableByNewAlias: Map<String, TableInfo>,
): FilterGroup {
    val children = mutableListOf<FilterNode>()
    for (child in group.children) {
        when (child) {
            is FilterNode.Leaf -> {
                val tableAlias = aliasMap[child.spec.tableAlias]
                val tableInfo = tableAlias?.let { tableByNewAlias[it] }
                val columnInfo = tableInfo?.columns?.find { it.name == child.spec.column }
                if (tableAlias != null && columnInfo != null) {
                    children.add(
                        FilterNode.Leaf(
                            child.spec.copy(
                                tableAlias = tableAlias,
                                value = normalizeFilterValue(child.spec.value, columnInfo.dataType),
                            )
                        )
                    )
                }
            }
            is FilterNode.Group -> {
                val remapped = remapFilterGroup(child.group, aliasMap, tableByNewAlias)
                if (remapped.children.isNotEmpty()) {
                    children.add(FilterNode.Group(remapped))
                }
            }
        }
    }
    return group.copy(children = children)
}

fun hydrateQueryFromSpec(
    spec: QuerySpec,
    schemaTables: List<TableInfo>,
    target: QueryHydrationTarget,
): HydrationWarnings {
    target.clear()

    val schemaByKey = schemaTables.associateBy { schemaKey(it.schema, it.name) }
    val aliasMap = mutableMapOf<String, String>()
    val tableByNewAlias = mutableMapOf<String, TableInfo>()
    val droppedTables = mutableListOf<String>()
    val droppedColumns = mutableListOf<String>()

    for ((tableSchema, tableName, alias) in spec.tables) {
        val tableInfo = schemaByKey[schemaKey(tableSchema, tableName)]
        if (tableInfo == null) {
            droppedTables.add("$tableSchema.$tableName")
            continue
        }

        target.addTable(tableInfo)
        val newAlias = target.tables.lastOrNull()?.alias
        if (newAlias != null) {
            aliasMap[alias] = newAlias
            tableByNewAlias[newAlias] = tableInfo
        }
    }

    for ((tableAlias, column) in spec.columns) {
        val newAlias = aliasMap[tableAlias]
        val tableInfo = newAlias?.let { tableByNewAlias[it] }
        if (newAlias != null && tableInfo?.columns?.any { it.name == column } == true) {
            target.toggleColumn(newAlias, column)
        } else {
            droppedColumns.add("$tableAlias.$column")
        }
    }

    var droppedJoins = 0
    for ((joinLeftAlias, leftColumn, joinRightAlias, rightColumn) in spec.joins) {
        val leftAlias = aliasMap[joinLeftAlias]
        val rightAlias = aliasMap[joinRightAlias]
        val leftTable = leftAlias?.let { tableByNewAlias[it] }
        val rightTable = rightAlias?.let { tableByNewAlias[it] }
        val leftColumnExists = leftTable?.columns?.any { it.name == leftColumn } == true
        val rightColumnExists = rightTable?.columns?.any { it.name == rightColumn } == true
        if (leftAlias == null || rightAlias == null || !leftColumnExists || !rightColumnExists) {
            droppedJoins += 1
            continue
        }

        target.addJoin(
            JoinSpec(
                leftAlias = leftAlias,
                leftColumn = leftColumn,
                rightAlias = rightAlias,
                rightColumn = rightColumn,
            )
        )
    }

    val originalFilterLeaves = countFilterLeaves(spec.filters)
    val remappedFilters = remapFilterGroup(spec.filters, aliasMap, tableByNewAlias)
    val droppedFilters = countFilterLeaves(remappedFilters) < originalFilterLeaves
    target.setFilters(remappedFilters)

    target.setConnectorOverrides(spec.connectorOverrides)
    target.setLimit(spec.limit)
    target.setDistinct(spec.distinct)

    var droppedSorts = 0
    target.setSorts(
        spec.sorts.mapNotNull { sort ->
            val newAlias = aliasMap[sort.tableAlias]
            val tableInfo = newAlias?.let { tableByNewAlias[it] }
            if (newAlias == null || tableInfo?.columns?.none { it.name == sort.column } != false) {
                droppedSorts += 1
                null
            } else {
                sort.copy(tableAlias = newAlias)
            }
        }
    )

    var droppedGroups = 0
    target.setGroups(
        spec.groups.mapNotNull { group ->
            val newAlias = aliasMap[group.tableAlias]
            val tableInfo = newAlias?.let { tableByNewAlias[it] }
            if (newAlias == null || tableInfo?.columns?.none { it.name == group.column } != false) {
                droppedGroups += 1
                null
            } else {
                group.copy(tableAlias = newAlias)
            }
        }
    )

    return HydrationWarnings(
        droppedTables = droppedTables,
        droppedColumns = droppedColumns,
        droppedJoins = droppedJoins,
        droppedFilters = droppedFilters,
        droppedSorts = droppedSorts,
        droppedGroups = droppedGroups,
    )
}

fun formatHydrationWarning(warnings: HydrationWarnings): String? {
    val parts = mutableListOf<String>()
    if (warnings.droppedTables.isNotEmpty()) {
        parts.add("missing tables: ${warnings.droppedTables.joinToString(", ")}")
    }
    if (warnings.droppedColumns.isNotEmpty()) {
        val count = warnings.droppedColumns.size
        parts.add("$count selected column${if (count != 1) "s" else ""} could not be restored")
    }
    if (warnings.droppedJoins > 0) {
        val count = warnings.droppedJoins
        parts.add("$count join${if (count != 1) "s" else ""} could not be restored")
    }
    if (warnings.droppedFilters) {
        parts.add("some filters were dropped")
    }
    if (warnings.droppedSorts > 0) {
        val count = warnings.droppedSorts
        parts.add("$count sort${if (count != 1) "s" else ""} could not be restored")
    }
    if (warnings.droppedGroups > 0) {
        val count = warnings.droppedGroups
        parts.add("$count group${if (count != 1) "s" else ""} could not be restored")
    }
    if (parts.isEmpty()) return null
    return "Query restored partially (${parts.joinToString("; ")}). Review before running."
}
