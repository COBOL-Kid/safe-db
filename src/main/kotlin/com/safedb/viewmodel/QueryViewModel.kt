package com.safedb.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.safedb.canvas.CANVAS_CARD_HEIGHT
import com.safedb.canvas.CANVAS_CARD_WIDTH
import com.safedb.canvas.MAX_TABLE_HEIGHT
import com.safedb.canvas.MAX_TABLE_WIDTH
import com.safedb.canvas.MIN_TABLE_HEIGHT
import com.safedb.canvas.MIN_TABLE_WIDTH
import com.safedb.canvas.clampDimension
import com.safedb.model.CURRENT_SCHEMA_VERSION
import com.safedb.model.ColumnSel
import com.safedb.model.Dialect
import com.safedb.model.FilterGroup
import com.safedb.model.FilterLiteral
import com.safedb.model.FilterNode
import com.safedb.model.FilterOp
import com.safedb.model.FilterSpec
import com.safedb.model.FilterValue
import com.safedb.model.GroupConnector
import com.safedb.model.GroupSpec
import com.safedb.model.JoinSpec
import com.safedb.model.LiteralKind
import com.safedb.model.Outcome
import com.safedb.model.QueryResult
import com.safedb.model.QueryRiskGate
import com.safedb.model.QuerySpec
import com.safedb.model.Schema
import com.safedb.model.Settings
import com.safedb.model.SortDirection
import com.safedb.model.SortSpec
import com.safedb.model.TableInfo
import com.safedb.model.TableRef
import com.safedb.model.ValueKind
import com.safedb.model.valueKind
import com.safedb.query.AliasRef
import com.safedb.query.DEFAULT_LIMIT
import com.safedb.query.QueryConfirmationRequirement
import com.safedb.query.QueryHydrationTarget
import com.safedb.query.QueryRiskEvaluation
import com.safedb.query.addFilterGroup
import com.safedb.query.addFilterLeaf
import com.safedb.query.columnKey
import com.safedb.query.columnKeyPrefix
import com.safedb.query.countFilterLeaves
import com.safedb.query.distinctSortProjectionConflicts
import com.safedb.query.ensureFilterNodeIds
import com.safedb.query.evaluateQueryRisk
import com.safedb.query.filterGroupAtPath
import com.safedb.query.filterLeafIdAtPath
import com.safedb.query.filterNodeIdAtPath
import com.safedb.query.formatHydrationWarning
import com.safedb.query.hydrateQueryFromSpec
import com.safedb.query.literalKindForColumn
import com.safedb.query.parseColumnKey
import com.safedb.query.parseLimit
import com.safedb.query.pruneFiltersForAlias
import com.safedb.query.rebuildConnectorOverrides
import com.safedb.query.removeFilterNode
import com.safedb.query.updateFilterNode
import com.safedb.service.QueryRunRequest
import com.safedb.service.SafeDbService
import kotlinx.coroutines.CoroutineScope

data class CanvasTable(
    val tableInfo: TableInfo,
    val alias: String,
    val x: Float,
    val y: Float,
    val width: Float = CANVAS_CARD_WIDTH,
    val height: Float = CANVAS_CARD_HEIGHT,
)

data class BuilderQuerySample(
    val connectionId: String,
    val spec: QuerySpec,
    val result: QueryResult,
)

typealias NewFilterSpec = FilterSpec

class QueryViewModel(service: SafeDbService, scope: CoroutineScope) : QueryHydrationTarget {
    private val runController = QueryRunController(service, scope)
    private var aliasCounter = 0

    internal val canvasViewport = CanvasViewportState()

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

    private var distinctState by mutableStateOf(false)
    val distinct: Boolean
        get() = distinctState

    private var connectorOverrideState by mutableStateOf(mapOf<String, GroupConnector>())
    val connectorOverrides: Map<String, GroupConnector>
        get() = connectorOverrideState

    private var sortState by mutableStateOf(emptyList<SortSpec>())
    val sorts: List<SortSpec>
        get() = sortState

    private var groupState by mutableStateOf(emptyList<GroupSpec>())
    val groups: List<GroupSpec>
        get() = groupState

    private var requestedFilterFocusIdState by mutableStateOf<String?>(null)
    val requestedFilterFocusId: String?
        get() = requestedFilterFocusIdState

    fun consumeRequestedFilterFocus(id: String) {
        if (requestedFilterFocusIdState == id) requestedFilterFocusIdState = null
    }

    val results: QueryResult?
        get() = runController.results

    val running: Boolean
        get() = runController.running

    val error: String?
        get() = runController.error

    val riskEvaluation: QueryRiskEvaluation?
        get() = runController.riskEvaluation

    val pendingRiskGate: Boolean
        get() = runController.pendingRiskGate

    val pendingConfirmation: QueryConfirmationRequirement?
        get() = runController.pendingConfirmation

    val pendingConfirmationReasons: List<String>
        get() = runController.pendingConfirmationReasons

    val pendingConfirmationReason: String?
        get() = pendingConfirmationReasons.joinToString(separator = " ").ifBlank { null }

    val occupiesQuerySlot: Boolean
        get() = runController.occupiesQuerySlot

    var hydrationWarning by mutableStateOf<String?>(null)
        private set

    val tableCount: Int
        get() = canvasTables.size

    val distinctSortConflicts: List<SortSpec>
        get() = distinctSortProjectionConflicts(spec)

    val canRun: Boolean
        get() =
            canvasTables.isNotEmpty() &&
                distinctSortConflicts.isEmpty() &&
                !running &&
                !pendingRiskGate &&
                pendingConfirmation == null

    val filterCount: Int
        get() = countFilterLeaves(filters)

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
                distinct = distinct,
                sorts = sorts,
                groups = groups,
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
        distinctState = false
        hydrationWarning = null
        aliasCounter = 0
        connectorOverrideState = emptyMap()
        sortState = emptyList()
        groupState = emptyList()
        requestedFilterFocusIdState = null
        canvasViewport.reset()
        runController.reset()
    }

    override fun addTable(tableInfo: TableInfo) {
        invalidateSettledRunFailure()
        val alias = "t${aliasCounter++}"
        val offset = canvasTables.size * 30f
        canvasTables.add(
            CanvasTable(tableInfo = tableInfo, alias = alias, x = 40f + offset, y = offset)
        )
    }

    override val tables: List<AliasRef>
        get() = canvasTables.map { AliasRef(it.alias) }

    fun removeTable(alias: String) {
        if (canvasTables.none { it.alias == alias }) return
        invalidateSettledRunFailure()
        canvasTables.removeAll { it.alias == alias }
        selectedColumns =
            selectedColumns.filterNot { it.startsWith(columnKeyPrefix(alias)) }.toSet()
        joins.removeAll { it.leftAlias == alias || it.rightAlias == alias }
        filterGroupState = pruneFiltersForAlias(filters, alias)
        connectorOverrideState = rebuildConnectorOverrides(filters, connectorOverrides)
        sortState = sorts.filterNot { it.tableAlias == alias }
        groupState = groups.filterNot { it.tableAlias == alias }
        if (canvasTables.isEmpty()) canvasViewport.reset()
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
        canvasTables[index] =
            current.copy(
                width = clampDimension(width, MIN_TABLE_WIDTH, MAX_TABLE_WIDTH),
                height = clampDimension(height, MIN_TABLE_HEIGHT, MAX_TABLE_HEIGHT),
            )
    }

    override fun toggleColumn(alias: String, column: String) {
        invalidateSettledRunFailure()
        val key = columnKey(alias, column)
        val grouped = groups.any { it.tableAlias == alias && it.column == column }
        if (grouped && key in selectedColumns) {
            clearGroup(alias, column)
            selectedColumns = selectedColumns - key
            return
        }
        selectedColumns =
            if (selectedColumns.contains(key)) {
                selectedColumns - key
            } else {
                selectedColumns + key
            }
        if (groups.isNotEmpty() && selectedColumns.contains(key)) {
            addGroup(alias, column)
        }
    }

    fun isColumnSelected(alias: String, column: String): Boolean =
        selectedColumns.contains(columnKey(alias, column))

    fun toggleAllColumns(alias: String) {
        val table = canvasTables.firstOrNull { it.alias == alias } ?: return
        val columns = table.tableInfo.columns.map { it.name }
        val allSelected = columns.isNotEmpty() && columns.all { isColumnSelected(alias, it) }
        columns.forEach { column ->
            if (isColumnSelected(alias, column) == allSelected) {
                toggleColumn(alias, column)
            }
        }
    }

    override fun addJoin(join: JoinSpec) {
        val exists = joins.any { it.matchesJoin(join) }
        if (!exists) {
            invalidateSettledRunFailure()
            joins.add(join)
        }
    }

    fun removeJoin(index: Int) {
        if (index in joins.indices) {
            invalidateSettledRunFailure()
            joins.removeAt(index)
        }
    }

    fun removeJoin(join: JoinSpec) {
        val index = joins.indexOfFirst { it.matchesJoin(join) }
        if (index >= 0) {
            invalidateSettledRunFailure()
            joins.removeAt(index)
        }
    }

    fun addFilter(spec: NewFilterSpec) {
        invalidateSettledRunFailure()
        filterGroupState = addFilterLeaf(filters, emptyList(), spec)
        connectorOverrideState = rebuildConnectorOverrides(filters, connectorOverrides)
    }

    fun addFilterForColumn(
        tableAlias: String,
        columnName: String,
        dataType: String,
        op: FilterOp = FilterOp.Eq,
    ) {
        val filter =
            defaultFilterForColumn(tableAlias, columnName, dataType).let { default ->
                if (op == default.op) default
                else default.copy(op = op, value = defaultValueFor(op, dataType))
            }
        addFilter(filter)
        requestedFilterFocusIdState = filter.id.takeIf { hasTextValueInput(op, dataType) }
    }

    fun cycleSort(tableAlias: String, columnName: String) {
        invalidateSettledRunFailure()
        val existing = sorts.firstOrNull { it.tableAlias == tableAlias && it.column == columnName }
        sortState =
            when (existing?.direction) {
                null -> {
                    if (groups.isNotEmpty()) addGroup(tableAlias, columnName)
                    sorts + SortSpec(tableAlias, columnName, SortDirection.Asc)
                }
                SortDirection.Asc ->
                    sorts.map {
                        if (it.tableAlias == tableAlias && it.column == columnName)
                            it.copy(direction = SortDirection.Desc)
                        else it
                    }
                SortDirection.Desc ->
                    sorts.filterNot { it.tableAlias == tableAlias && it.column == columnName }
            }
    }

    fun sortForColumn(tableAlias: String, columnName: String): SortSpec? = sorts.firstOrNull {
        it.tableAlias == tableAlias && it.column == columnName
    }

    fun hasFilterForColumn(tableAlias: String, columnName: String): Boolean =
        filters.containsFilter(tableAlias, columnName)

    fun setSort(tableAlias: String, columnName: String, direction: SortDirection) {
        invalidateSettledRunFailure()
        val existing = sortForColumn(tableAlias, columnName)
        sortState =
            if (existing == null) {
                if (groups.isNotEmpty()) addGroup(tableAlias, columnName)
                sorts + SortSpec(tableAlias, columnName, direction)
            } else {
                sorts.map { sort ->
                    if (sort.tableAlias == tableAlias && sort.column == columnName)
                        sort.copy(direction = direction)
                    else sort
                }
            }
    }

    fun clearSort(tableAlias: String, columnName: String) {
        if (sortForColumn(tableAlias, columnName) == null) return
        invalidateSettledRunFailure()
        sortState = sorts.filterNot { it.tableAlias == tableAlias && it.column == columnName }
    }

    fun selectDistinctSortColumns() {
        distinctSortConflicts.forEach { sort -> toggleColumn(sort.tableAlias, sort.column) }
    }

    fun removeDistinctSortConflicts() {
        val conflictKeys =
            distinctSortConflicts.mapTo(mutableSetOf()) { it.tableAlias to it.column }
        if (conflictKeys.isEmpty()) return
        invalidateSettledRunFailure()
        sortState = sorts.filterNot { (it.tableAlias to it.column) in conflictKeys }
    }

    fun moveSort(fromIndex: Int, toIndex: Int) {
        val moved = sorts.moveItem(fromIndex, toIndex)
        if (moved === sorts) return
        invalidateSettledRunFailure()
        sortState = moved
    }

    fun toggleGroup(tableAlias: String, columnName: String) {
        invalidateSettledRunFailure()
        if (groupForColumn(tableAlias, columnName) == null) {
            if (groups.isEmpty()) {
                val existingSelections =
                    selectedColumns
                        .map(::parseColumnKey)
                        .filterNot { (alias, column) ->
                            alias == tableAlias && column == columnName
                        }
                        .sortedWith(compareBy({ it.first }, { it.second }))
                groupState =
                    listOf(GroupSpec(tableAlias, columnName)) +
                        existingSelections.map { (alias, column) -> GroupSpec(alias, column) }
                selectedColumns = selectedColumns + columnKey(tableAlias, columnName)
            } else {
                addGroup(tableAlias, columnName)
            }
        } else {
            groupState = emptyList()
        }
    }

    fun groupForColumn(tableAlias: String, columnName: String): GroupSpec? = groups.firstOrNull {
        it.tableAlias == tableAlias && it.column == columnName
    }

    fun clearGroup(tableAlias: String, columnName: String) {
        if (groupForColumn(tableAlias, columnName) == null) return
        invalidateSettledRunFailure()
        val clearsLastGroup = groups.size == 1
        val remainingGroups = groups.filterNot {
            it.tableAlias == tableAlias && it.column == columnName
        }
        groupState = remainingGroups
        if (!clearsLastGroup) {
            selectedColumns = selectedColumns - columnKey(tableAlias, columnName)
            sortState = sorts.filterNot { it.tableAlias == tableAlias && it.column == columnName }
            if (selectedColumns.isEmpty()) {
                val fallback = remainingGroups.first()
                selectedColumns = setOf(columnKey(fallback.tableAlias, fallback.column))
            }
        }
    }

    fun moveGroup(fromIndex: Int, toIndex: Int) {
        val moved = groups.moveItem(fromIndex, toIndex)
        if (moved === groups) return
        invalidateSettledRunFailure()
        groupState = moved
    }

    private fun addGroup(tableAlias: String, columnName: String) {
        if (groupForColumn(tableAlias, columnName) != null) return
        groupState = groups + GroupSpec(tableAlias, columnName)
        val key = columnKey(tableAlias, columnName)
        if (key !in selectedColumns) selectedColumns = selectedColumns + key
    }

    override fun setFilters(group: FilterGroup) {
        invalidateSettledRunFailure()
        filterGroupState = ensureFilterNodeIds(group)
        connectorOverrideState = rebuildConnectorOverrides(filters, connectorOverrides)
    }

    override fun setSorts(sorts: List<SortSpec>) {
        invalidateSettledRunFailure()
        sortState = sorts
    }

    override fun setGroups(groups: List<GroupSpec>) {
        invalidateSettledRunFailure()
        groupState = groups.distinctBy { it.tableAlias to it.column }
    }

    override fun setDistinct(distinct: Boolean) {
        if (distinctState == distinct) return
        invalidateSettledRunFailure()
        distinctState = distinct
    }

    fun addFilterToGroup(groupPath: List<Int>, spec: NewFilterSpec) {
        invalidateSettledRunFailure()
        filterGroupState = addFilterLeaf(filters, groupPath, spec)
        connectorOverrideState = rebuildConnectorOverrides(filters, connectorOverrides)
    }

    fun addGroupToGroup(groupPath: List<Int>, connector: GroupConnector) {
        invalidateSettledRunFailure()
        filterGroupState = addFilterGroup(filters, groupPath, connector)
        connectorOverrideState = rebuildConnectorOverrides(filters, connectorOverrides)
    }

    fun updateFilter(path: List<Int>, spec: NewFilterSpec) {
        invalidateSettledRunFailure()
        val existingId = filterLeafIdAtPath(filters, path)
        val specWithId = spec.copy(id = existingId ?: spec.id.ifEmpty { newNodeId() })
        filterGroupState = updateFilterNode(filters, path, FilterNode.Leaf(specWithId))
        connectorOverrideState = rebuildConnectorOverrides(filters, connectorOverrides)
    }

    override fun setConnectorOverrides(map: Map<String, GroupConnector>) {
        invalidateSettledRunFailure()
        connectorOverrideState = rebuildConnectorOverrides(filters, map)
    }

    fun pathKey(path: List<Int>): String? = filterNodeIdAtPath(filters, path)

    fun getConnectorForChild(path: List<Int>): GroupConnector {
        if (path.isEmpty()) return filters.connector
        val key = pathKey(path)
        if (key != null) {
            connectorOverrides[key]?.let {
                return it
            }
        }
        val parent = filterGroupAtPath(filters, path.dropLast(1))
        return parent?.connector ?: GroupConnector.And
    }

    fun setChildConnector(path: List<Int>, connector: GroupConnector) {
        if (path.isEmpty() || path.last() == 0) return
        val key = pathKey(path) ?: return
        val parent = filterGroupAtPath(filters, path.dropLast(1))
        val groupDefault = parent?.connector ?: filters.connector
        val next = connectorOverrides.toMutableMap()
        if (connector == groupDefault) {
            next.remove(key)
        } else {
            next[key] = connector
        }
        if (next == connectorOverrides) return
        invalidateSettledRunFailure()
        connectorOverrideState = next
    }

    fun toggleChildConnector(path: List<Int>) {
        val current = getConnectorForChild(path)
        setChildConnector(
            path,
            if (current == GroupConnector.And) GroupConnector.Or else GroupConnector.And,
        )
    }

    fun removeFilterNode(path: List<Int>) {
        if (path.isEmpty()) return
        invalidateSettledRunFailure()
        filterGroupState = removeFilterNode(filters, path)
        connectorOverrideState = rebuildConnectorOverrides(filters, connectorOverrides)
    }

    override fun setLimit(limit: Int) {
        val parsed = parseLimit(limit)
        if (queryLimit == parsed) return
        invalidateSettledRunFailure()
        queryLimit = parsed
    }

    fun run(connectionId: String, onSettled: ((Boolean) -> Unit)? = null) {
        if (!canRun) return
        runController.run(QueryRunRequest(connectionId, spec), onSettled)
    }

    fun confirmPendingExecution(connectionId: String) {
        runController.confirmPendingExecution(connectionId, spec)
    }

    fun onActiveConnectionChanged(connectionId: String?) {
        runController.onActiveConnectionChanged(connectionId)
    }

    fun onQueryRiskGateChanged(gate: QueryRiskGate) {
        runController.onQueryRiskGateChanged(gate)
    }

    fun riskEvaluationFor(connectionId: String?): QueryRiskEvaluation? =
        runController.riskEvaluationFor(connectionId, spec)

    // This is only a static preview; execution still performs plan refinement and confirmation.
    fun evaluatePreliminaryRisk(
        schema: Schema?,
        settings: Settings,
        dialect: Dialect?,
    ): Outcome<QueryRiskEvaluation>? {
        if (schema == null || dialect == null || canvasTables.isEmpty()) return null
        return evaluateQueryRisk(spec, schema, settings, dialect)
    }

    fun currentSample(connectionId: String?): BuilderQuerySample? =
        runController.currentSample(connectionId, spec)

    fun dismissHydrationWarning() {
        hydrationWarning = null
    }

    fun dismissError() {
        invalidateSettledRunFailure()
    }

    private fun invalidateSettledRunFailure() {
        runController.invalidateSettledRunFailure()
    }

    fun restoreFromSpec(spec: QuerySpec, schemaTables: List<TableInfo>) {
        val warnings = hydrateQueryFromSpec(spec, schemaTables, this)
        hydrationWarning = formatHydrationWarning(warnings)
    }

    companion object {
        fun defaultFilterForColumn(
            tableAlias: String,
            columnName: String,
            dataType: String,
        ): FilterSpec {
            val op = FilterOp.Eq
            val value = defaultValueFor(op, dataType)
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

private fun defaultValueFor(op: FilterOp, dataType: String): FilterValue? {
    val kind = literalKindForColumn(dataType)
    return when (op.valueKind()) {
        ValueKind.None -> null
        ValueKind.Single -> FilterValue.Single(FilterLiteral(kind, ""))
        ValueKind.List -> FilterValue.ListValue(listOf(FilterLiteral(kind, "")))
        ValueKind.Pair -> FilterValue.Pair(FilterLiteral(kind, ""), FilterLiteral(kind, ""))
    }
}

private fun hasTextValueInput(op: FilterOp, dataType: String): Boolean =
    when (op.valueKind()) {
        ValueKind.None -> false
        ValueKind.Single -> literalKindForColumn(dataType) != LiteralKind.Bool
        ValueKind.Pair,
        ValueKind.List -> true
    }

private fun FilterGroup.containsFilter(tableAlias: String, columnName: String): Boolean =
    children.any { child ->
        when (child) {
            is FilterNode.Leaf ->
                child.spec.tableAlias == tableAlias && child.spec.column == columnName
            is FilterNode.Group -> child.group.containsFilter(tableAlias, columnName)
        }
    }

internal fun JoinSpec.matchesJoin(other: JoinSpec): Boolean =
    (leftAlias == other.leftAlias &&
        leftColumn == other.leftColumn &&
        rightAlias == other.rightAlias &&
        rightColumn == other.rightColumn) ||
        (leftAlias == other.rightAlias &&
            leftColumn == other.rightColumn &&
            rightAlias == other.leftAlias &&
            rightColumn == other.leftColumn)

private fun <T> List<T>.moveItem(fromIndex: Int, toIndex: Int): List<T> {
    if (fromIndex !in indices || toIndex !in indices || fromIndex == toIndex) return this
    return toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
}

private fun newNodeId(): String = java.util.UUID.randomUUID().toString()
