package com.safedb.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.safedb.model.CURRENT_SCHEMA_VERSION
import com.safedb.model.ColumnSel
import com.safedb.model.FilterGroup
import com.safedb.model.FilterLiteral
import com.safedb.model.FilterNode
import com.safedb.model.FilterOp
import com.safedb.model.FilterSpec
import com.safedb.model.FilterValue
import com.safedb.model.GroupConnector
import com.safedb.model.JoinSpec
import com.safedb.model.LiteralKind
import com.safedb.model.QueryResult
import com.safedb.model.QuerySpec
import com.safedb.model.TableInfo
import com.safedb.model.TableRef
import com.safedb.model.ValueKind
import com.safedb.model.valueKind
import com.safedb.query.AliasRef
import com.safedb.query.CANVAS_CARD_HEIGHT
import com.safedb.query.CANVAS_CARD_WIDTH
import com.safedb.query.COST_GUARD_PREFIX
import com.safedb.query.DEFAULT_LIMIT
import com.safedb.query.QueryHydrationTarget
import com.safedb.query.clampDimension
import com.safedb.query.columnKey
import com.safedb.query.columnKeyPrefix
import com.safedb.query.formatHydrationWarning
import com.safedb.query.hydrateQueryFromSpec
import com.safedb.query.literalKindForColumn
import com.safedb.query.parseColumnKey
import com.safedb.query.parseLimit
import com.safedb.service.SafeDbService
import com.safedb.query.MAX_TABLE_HEIGHT
import com.safedb.query.MAX_TABLE_WIDTH
import com.safedb.query.MIN_TABLE_HEIGHT
import com.safedb.query.MIN_TABLE_WIDTH
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.UUID

data class CanvasTable(
    val tableInfo: TableInfo,
    val alias: String,
    val x: Float,
    val y: Float,
    val width: Float = CANVAS_CARD_WIDTH,
    val height: Float = CANVAS_CARD_HEIGHT,
)

typealias NewFilterSpec = FilterSpec

class QueryViewModel(
    private val service: SafeDbService,
    private val scope: CoroutineScope,
) : QueryHydrationTarget {
    private var aliasCounter = 0

    val canvasTables: SnapshotStateList<CanvasTable> = mutableStateListOf()
    var selectedColumns by mutableStateOf(setOf<String>())
        private set
    val joins: SnapshotStateList<JoinSpec> = mutableStateListOf()
    private var filterGroupState by mutableStateOf(FilterGroup.empty())
    val filters: FilterGroup
        get() = filterGroupState
    private var queryLimit by mutableIntStateOf(DEFAULT_LIMIT)
    val limit: Int
        get() = queryLimit
    private var connectorOverrideState by mutableStateOf(mapOf<String, GroupConnector>())
    val connectorOverrides: Map<String, GroupConnector>
        get() = connectorOverrideState

    var results by mutableStateOf<QueryResult?>(null)
        private set
    var running by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var pendingCostGuard by mutableStateOf(false)
        private set
    var hydrationWarning by mutableStateOf<String?>(null)
        private set
    var warningPopupsDisabled by mutableStateOf(false)
        private set

    val tableCount: Int get() = canvasTables.size
    val canRun: Boolean get() = canvasTables.isNotEmpty() && !running
    val filterCount: Int get() = countLeaves(filters)

    val spec: QuerySpec
        get() {
            val tableRefs = canvasTables.map { t ->
                TableRef(schema = t.tableInfo.schema, name = t.tableInfo.name, alias = t.alias)
            }
            val columns = selectedColumns.map { key ->
                val (alias, column) = parseColumnKey(key)
                ColumnSel(tableAlias = alias, column = column)
            }
            return QuerySpec(
                tables = tableRefs,
                columns = columns,
                joins = joins.toList(),
                filters = filters,
                limit = limit,
                schemaVersion = CURRENT_SCHEMA_VERSION,
                connectorOverrides = connectorOverrides,
            )
        }

    override fun clear() {
        canvasTables.clear()
        selectedColumns = emptySet()
        joins.clear()
        filterGroupState = FilterGroup.empty()
        queryLimit = DEFAULT_LIMIT
        results = null
        error = null
        running = false
        pendingCostGuard = false
        hydrationWarning = null
        aliasCounter = 0
        connectorOverrideState = emptyMap()
    }

    override fun addTable(tableInfo: TableInfo) {
        val alias = "t${aliasCounter++}"
        val offset = canvasTables.size * 30f
        canvasTables.add(
            CanvasTable(
                tableInfo = tableInfo,
                alias = alias,
                x = 40f + offset,
                y = 40f + offset,
            ),
        )
    }

    override val tables: List<AliasRef>
        get() = canvasTables.map { AliasRef(it.alias) }

    fun removeTable(alias: String) {
        canvasTables.removeAll { it.alias == alias }
        selectedColumns = selectedColumns.filterNot { it.startsWith(columnKeyPrefix(alias)) }.toSet()
        joins.removeAll { it.leftAlias == alias || it.rightAlias == alias }
        filterGroupState = pruneFiltersReferencingAlias(filters, alias)
        connectorOverrideState = rebuildOverrides(filters, connectorOverrides)
    }

    fun moveTable(alias: String, x: Float, y: Float) {
        val index = canvasTables.indexOfFirst { it.alias == alias }
        if (index < 0) return
        val current = canvasTables[index]
        canvasTables[index] = current.copy(x = x, y = y)
    }

    fun resizeTable(alias: String, width: Float, height: Float) {
        val index = canvasTables.indexOfFirst { it.alias == alias }
        if (index < 0) return
        val current = canvasTables[index]
        canvasTables[index] = current.copy(
            width = clampDimension(width, MIN_TABLE_WIDTH, MAX_TABLE_WIDTH),
            height = clampDimension(height, MIN_TABLE_HEIGHT, MAX_TABLE_HEIGHT),
        )
    }

    override fun toggleColumn(alias: String, column: String) {
        val key = columnKey(alias, column)
        selectedColumns = if (selectedColumns.contains(key)) {
            selectedColumns - key
        } else {
            selectedColumns + key
        }
    }

    fun isColumnSelected(alias: String, column: String): Boolean =
        selectedColumns.contains(columnKey(alias, column))

    override fun addJoin(join: JoinSpec) {
        val exists = joins.any { it.matchesJoin(join) }
        if (!exists) {
            joins.add(join)
        }
    }

    fun removeJoin(index: Int) {
        if (index in joins.indices) {
            joins.removeAt(index)
        }
    }

    fun removeJoin(join: JoinSpec) {
        val index = joins.indexOfFirst { it.matchesJoin(join) }
        if (index >= 0) {
            joins.removeAt(index)
        }
    }

    fun addFilter(spec: NewFilterSpec) {
        filterGroupState = addLeafToGroup(filters, emptyList(), spec)
        connectorOverrideState = rebuildOverrides(filters, connectorOverrides)
    }

    override fun setFilters(group: FilterGroup) {
        filterGroupState = ensureGroupIds(group)
        connectorOverrideState = rebuildOverrides(filters, connectorOverrides)
    }

    fun addFilterToGroup(groupPath: List<Int>, spec: NewFilterSpec) {
        filterGroupState = addLeafToGroup(filters, groupPath, spec)
        connectorOverrideState = rebuildOverrides(filters, connectorOverrides)
    }

    fun addGroupToGroup(groupPath: List<Int>, connector: GroupConnector) {
        filterGroupState = addGroupToGroup(filters, groupPath, connector)
        connectorOverrideState = rebuildOverrides(filters, connectorOverrides)
    }

    fun updateFilter(path: List<Int>, spec: NewFilterSpec) {
        val existingId = getLeafIdAtPath(filters, path)
        val specWithId = spec.copy(id = existingId ?: spec.id.ifEmpty { newNodeId() })
        filterGroupState = updateNodeAtPath(filters, path, FilterNode.Leaf(specWithId))
        connectorOverrideState = rebuildOverrides(filters, connectorOverrides)
    }

    fun setGroupConnector(path: List<Int>, connector: GroupConnector) {
        filterGroupState = if (path.isEmpty()) {
            filters.copy(connector = connector)
        } else {
            val group = getGroupAtPath(filters, path)
            if (group != null) {
                updateNodeAtPath(filters, path, FilterNode.Group(group.copy(connector = connector)))
            } else {
                filters
            }
        }
        connectorOverrideState = rebuildOverrides(filters, connectorOverrides, path)
    }

    override fun setConnectorOverrides(map: Map<String, GroupConnector>) {
        connectorOverrideState = rebuildOverrides(filters, map)
    }

    fun pathKey(path: List<Int>): String? = childIdAtPath(filters, path)

    fun getConnectorForChild(path: List<Int>): GroupConnector {
        if (path.isEmpty()) return filters.connector
        val key = pathKey(path)
        if (key != null) {
            connectorOverrides[key]?.let { return it }
        }
        val parent = getGroupAtPath(filters, path.dropLast(1))
        return parent?.connector ?: GroupConnector.And
    }

    fun setChildConnector(path: List<Int>, connector: GroupConnector) {
        if (path.isEmpty() || path.last() == 0) return
        val key = pathKey(path) ?: return
        val parent = getGroupAtPath(filters, path.dropLast(1))
        val groupDefault = parent?.connector ?: filters.connector
        val next = connectorOverrides.toMutableMap()
        if (connector == groupDefault) {
            next.remove(key)
        } else {
            next[key] = connector
        }
        connectorOverrideState = next
    }

    fun toggleChildConnector(path: List<Int>) {
        val current = getConnectorForChild(path)
        setChildConnector(path, if (current == GroupConnector.And) GroupConnector.Or else GroupConnector.And)
    }

    fun removeFilterNode(path: List<Int>) {
        if (path.isEmpty()) return
        filterGroupState = removeNodeAtPath(filters, path)
        connectorOverrideState = rebuildOverrides(filters, connectorOverrides)
    }

    override fun setLimit(limit: Int) {
        queryLimit = parseLimit(limit)
    }

    fun run(connectionId: String, force: Boolean = false) {
        if (!canRun) return
        scope.launch {
            running = true
            error = null
            results = null
            pendingCostGuard = false
            try {
                results = service.runQuery(connectionId, spec, force)
            } catch (e: Exception) {
                val message = e.message ?: e.toString()
                if (!force && message.startsWith(COST_GUARD_PREFIX)) {
                    if (warningPopupsDisabled) {
                        try {
                            results = service.runQuery(connectionId, spec, force = true)
                        } catch (forced: Exception) {
                            error = forced.message ?: forced.toString()
                        }
                    } else {
                        pendingCostGuard = true
                        error = message.removePrefix(COST_GUARD_PREFIX)
                    }
                } else {
                    error = message
                }
            } finally {
                running = false
            }
        }
    }

    fun runForced(connectionId: String) {
        run(connectionId, force = true)
    }

    fun dismissHydrationWarning() {
        hydrationWarning = null
    }

    fun clearPendingCostGuard() {
        pendingCostGuard = false
        error = null
    }

    fun dismissError() {
        clearPendingCostGuard()
    }

    fun updateWarningPopupsDisabled(disabled: Boolean) {
        warningPopupsDisabled = disabled
    }

    fun restoreFromSpec(spec: QuerySpec, schemaTables: List<TableInfo>) {
        val warnings = hydrateQueryFromSpec(spec, schemaTables, this)
        hydrationWarning = formatHydrationWarning(warnings)
    }

    companion object {
        fun defaultFilterForColumn(tableAlias: String, columnName: String, dataType: String): FilterSpec {
            val kind = literalKindForColumn(dataType)
            val op = FilterOp.Eq
            val value = when (op.valueKind()) {
                ValueKind.None -> null
                ValueKind.Single -> FilterValue.Single(FilterLiteral(kind, ""))
                ValueKind.List -> FilterValue.ListValue(listOf(FilterLiteral(kind, "")))
                ValueKind.Pair -> FilterValue.Pair(
                    FilterLiteral(kind, ""),
                    FilterLiteral(kind, ""),
                )
            }
            return FilterSpec(
                id = newNodeId(),
                tableAlias = tableAlias,
                column = columnName,
                op = op,
                value = value,
            )
        }
    }
}

private fun JoinSpec.matchesJoin(other: JoinSpec): Boolean =
    (leftAlias == other.leftAlias && leftColumn == other.leftColumn &&
        rightAlias == other.rightAlias && rightColumn == other.rightColumn) ||
        (leftAlias == other.rightAlias && leftColumn == other.rightColumn &&
            rightAlias == other.leftAlias && rightColumn == other.leftColumn)

private fun newNodeId(): String = UUID.randomUUID().toString()

private fun countLeaves(group: FilterGroup): Int =
    group.children.sumOf { child ->
        when (child) {
            is FilterNode.Leaf -> 1
            is FilterNode.Group -> countLeaves(child.group)
        }
    }

private fun getGroupAtPath(group: FilterGroup, path: List<Int>): FilterGroup? {
    if (path.isEmpty()) return group
    val head = path.first()
    val child = group.children.getOrNull(head) ?: return null
    return when (child) {
        is FilterNode.Group -> if (path.size == 1) child.group else getGroupAtPath(child.group, path.drop(1))
        is FilterNode.Leaf -> null
    }
}

private fun addLeafToGroup(group: FilterGroup, path: List<Int>, spec: NewFilterSpec): FilterGroup {
    val specWithId = spec.copy(id = spec.id.ifEmpty { newNodeId() })
    if (path.isEmpty()) {
        return group.copy(children = group.children + FilterNode.Leaf(specWithId))
    }
    val head = path.first()
    val child = group.children.getOrNull(head) as? FilterNode.Group ?: return group
    val newChildren = group.children.toMutableList()
    newChildren[head] = FilterNode.Group(addLeafToGroup(child.group, path.drop(1), specWithId))
    return group.copy(children = newChildren)
}

private fun addGroupToGroup(group: FilterGroup, path: List<Int>, connector: GroupConnector): FilterGroup {
    if (path.isEmpty()) {
        return group.copy(
            children = group.children + FilterNode.Group(
                FilterGroup(id = newNodeId(), connector = connector, children = emptyList()),
            ),
        )
    }
    val head = path.first()
    val child = group.children.getOrNull(head) as? FilterNode.Group ?: return group
    val newChildren = group.children.toMutableList()
    newChildren[head] = FilterNode.Group(addGroupToGroup(child.group, path.drop(1), connector))
    return group.copy(children = newChildren)
}

private fun updateNodeAtPath(group: FilterGroup, path: List<Int>, newNode: FilterNode): FilterGroup {
    if (path.isEmpty()) return group
    if (path.size == 1) {
        val idx = path[0]
        if (idx !in group.children.indices) return group
        val newChildren = group.children.toMutableList()
        newChildren[idx] = newNode
        return group.copy(children = newChildren)
    }
    val head = path.first()
    val child = group.children.getOrNull(head) as? FilterNode.Group ?: return group
    val newChildren = group.children.toMutableList()
    newChildren[head] = FilterNode.Group(updateNodeAtPath(child.group, path.drop(1), newNode))
    return group.copy(children = newChildren)
}

private fun removeNodeAtPath(group: FilterGroup, path: List<Int>): FilterGroup {
    if (path.isEmpty()) return group
    if (path.size == 1) {
        return group.copy(children = group.children.filterIndexed { index, _ -> index != path[0] })
    }
    val head = path.first()
    val child = group.children.getOrNull(head) as? FilterNode.Group ?: return group
    val newChildren = group.children.toMutableList()
    newChildren[head] = FilterNode.Group(removeNodeAtPath(child.group, path.drop(1)))
    return group.copy(children = newChildren)
}

private fun pruneFiltersReferencingAlias(group: FilterGroup, alias: String): FilterGroup {
    val children = group.children.mapNotNull { child ->
        when (child) {
            is FilterNode.Leaf -> if (child.spec.tableAlias == alias) null else child
            is FilterNode.Group -> {
                val pruned = pruneFiltersReferencingAlias(child.group, alias)
                if (pruned.children.isEmpty()) null else FilterNode.Group(pruned)
            }
        }
    }
    return group.copy(children = children)
}

private fun childIdAtPath(group: FilterGroup, path: List<Int>): String? {
    if (path.isEmpty()) return group.id.ifEmpty { null }
    val head = path.first()
    val child = group.children.getOrNull(head) ?: return null
    return when (child) {
        is FilterNode.Leaf -> if (path.size == 1) child.spec.id.ifEmpty { null } else null
        is FilterNode.Group -> childIdAtPath(child.group, path.drop(1))
    }
}

private fun getLeafIdAtPath(group: FilterGroup, path: List<Int>): String? {
    if (path.isEmpty()) return null
    val head = path.first()
    val child = group.children.getOrNull(head) ?: return null
    return when (child) {
        is FilterNode.Leaf -> if (path.size == 1) child.spec.id.ifEmpty { null } else null
        is FilterNode.Group -> getLeafIdAtPath(child.group, path.drop(1))
    }
}

private fun ensureGroupIds(group: FilterGroup): FilterGroup =
    group.copy(
        id = group.id.ifEmpty { newNodeId() },
        children = group.children.map { child ->
            when (child) {
                is FilterNode.Leaf -> {
                    val spec = child.spec
                    FilterNode.Leaf(
                        if (spec.id.isNotEmpty()) spec else spec.copy(id = newNodeId()),
                    )
                }
                is FilterNode.Group -> FilterNode.Group(ensureGroupIds(child.group))
            }
        },
    )

private fun rebuildOverrides(
    group: FilterGroup,
    overrides: Map<String, GroupConnector>,
    modifiedGroupPath: List<Int>? = null,
): Map<String, GroupConnector> {
    val ids = mutableSetOf<String>()
    val parentMap = mutableMapOf<String, FilterGroup>()

    fun walk(g: FilterGroup) {
        if (g.id.isNotEmpty()) ids.add(g.id)
        for (child in g.children) {
            when (child) {
                is FilterNode.Leaf -> {
                    if (child.spec.id.isNotEmpty()) {
                        ids.add(child.spec.id)
                        parentMap[child.spec.id] = g
                    }
                }
                is FilterNode.Group -> {
                    if (child.group.id.isNotEmpty()) {
                        ids.add(child.group.id)
                        parentMap[child.group.id] = g
                    }
                    walk(child.group)
                }
            }
        }
    }
    walk(group)

    val modifiedGroup = modifiedGroupPath?.let { getGroupAtPath(group, it) }
    val pruneByParent = modifiedGroup != null

    val next = mutableMapOf<String, GroupConnector>()
    for ((key, value) in overrides) {
        if (!ids.contains(key)) continue
        if (pruneByParent) {
            val parent = parentMap[key]
            if (parent != null && parent.id == modifiedGroup.id && parent.connector == value) {
                continue
            }
        }
        next[key] = value
    }
    return next
}
