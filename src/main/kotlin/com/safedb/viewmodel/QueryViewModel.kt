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
import com.safedb.query.DEFAULT_LIMIT
import com.safedb.query.QueryError
import com.safedb.query.QueryHydrationTarget
import com.safedb.query.clampDimension
import com.safedb.query.countFilterLeaves
import com.safedb.query.addFilterGroup
import com.safedb.query.addFilterLeaf
import com.safedb.query.columnKey
import com.safedb.query.columnKeyPrefix
import com.safedb.query.ensureFilterNodeIds
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
import com.safedb.service.QueryFailureException
import com.safedb.service.QueryRunRequest
import com.safedb.service.SafeDbService
import com.safedb.query.MAX_TABLE_HEIGHT
import com.safedb.query.MAX_TABLE_WIDTH
import com.safedb.query.MIN_TABLE_HEIGHT
import com.safedb.query.MIN_TABLE_WIDTH
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

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

data class PendingCostGuard(
    val request: QueryRunRequest,
    val reason: String,
)

typealias NewFilterSpec = FilterSpec

class QueryViewModel(
    private val service: SafeDbService,
    private val scope: CoroutineScope,
) : QueryHydrationTarget {
    private var aliasCounter = 0
    private var runGeneration = 0

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
    private var resultConnectionId by mutableStateOf<String?>(null)
    private var resultSpec by mutableStateOf<QuerySpec?>(null)
    var running by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private var pendingCostGuardState by mutableStateOf<PendingCostGuard?>(null)
    val pendingCostGuard: Boolean
        get() = pendingCostGuardState != null
    val pendingCostGuardReason: String?
        get() = pendingCostGuardState?.reason
    var hydrationWarning by mutableStateOf<String?>(null)
        private set
    var warningPopupsDisabled by mutableStateOf(false)
        private set

    val tableCount: Int get() = canvasTables.size
    val canRun: Boolean get() = canvasTables.isNotEmpty() && !running
    val filterCount: Int get() = countFilterLeaves(filters)

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
        runGeneration += 1
        canvasTables.clear()
        selectedColumns = emptySet()
        joins.clear()
        filterGroupState = FilterGroup.empty()
        queryLimit = DEFAULT_LIMIT
        results = null
        resultConnectionId = null
        resultSpec = null
        error = null
        running = false
        pendingCostGuardState = null
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
        filterGroupState = pruneFiltersForAlias(filters, alias)
        connectorOverrideState = rebuildConnectorOverrides(filters, connectorOverrides)
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
        filterGroupState = addFilterLeaf(filters, emptyList(), spec)
        connectorOverrideState = rebuildConnectorOverrides(filters, connectorOverrides)
    }

    override fun setFilters(group: FilterGroup) {
        filterGroupState = ensureFilterNodeIds(group)
        connectorOverrideState = rebuildConnectorOverrides(filters, connectorOverrides)
    }

    fun addFilterToGroup(groupPath: List<Int>, spec: NewFilterSpec) {
        filterGroupState = addFilterLeaf(filters, groupPath, spec)
        connectorOverrideState = rebuildConnectorOverrides(filters, connectorOverrides)
    }

    fun addGroupToGroup(groupPath: List<Int>, connector: GroupConnector) {
        filterGroupState = addFilterGroup(filters, groupPath, connector)
        connectorOverrideState = rebuildConnectorOverrides(filters, connectorOverrides)
    }

    fun updateFilter(path: List<Int>, spec: NewFilterSpec) {
        val existingId = filterLeafIdAtPath(filters, path)
        val specWithId = spec.copy(id = existingId ?: spec.id.ifEmpty { newNodeId() })
        filterGroupState = updateFilterNode(filters, path, FilterNode.Leaf(specWithId))
        connectorOverrideState = rebuildConnectorOverrides(filters, connectorOverrides)
    }

    fun setGroupConnector(path: List<Int>, connector: GroupConnector) {
        filterGroupState = if (path.isEmpty()) {
            filters.copy(connector = connector)
        } else {
            val group = filterGroupAtPath(filters, path)
            if (group != null) {
                updateFilterNode(filters, path, FilterNode.Group(group.copy(connector = connector)))
            } else {
                filters
            }
        }
        connectorOverrideState = rebuildConnectorOverrides(filters, connectorOverrides, path)
    }

    override fun setConnectorOverrides(map: Map<String, GroupConnector>) {
        connectorOverrideState = rebuildConnectorOverrides(filters, map)
    }

    fun pathKey(path: List<Int>): String? = filterNodeIdAtPath(filters, path)

    fun getConnectorForChild(path: List<Int>): GroupConnector {
        if (path.isEmpty()) return filters.connector
        val key = pathKey(path)
        if (key != null) {
            connectorOverrides[key]?.let { return it }
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
        connectorOverrideState = next
    }

    fun toggleChildConnector(path: List<Int>) {
        val current = getConnectorForChild(path)
        setChildConnector(path, if (current == GroupConnector.And) GroupConnector.Or else GroupConnector.And)
    }

    fun removeFilterNode(path: List<Int>) {
        if (path.isEmpty()) return
        filterGroupState = removeFilterNode(filters, path)
        connectorOverrideState = rebuildConnectorOverrides(filters, connectorOverrides)
    }

    override fun setLimit(limit: Int) {
        queryLimit = parseLimit(limit)
    }

    fun run(connectionId: String, force: Boolean = false) {
        if (!canRun) return
        run(QueryRunRequest(connectionId, spec, force))
    }

    private fun run(request: QueryRunRequest) {
        val executedSpec = request.spec
        val generation = ++runGeneration
        running = true
        error = null
        results = null
        resultConnectionId = null
        resultSpec = null
        pendingCostGuardState = null
        scope.launch {
            try {
                val completed = service.runQuery(request)
                if (generation == runGeneration) {
                    results = completed
                    resultConnectionId = request.connectionId
                    resultSpec = executedSpec
                }
            } catch (failure: QueryFailureException) {
                if (generation != runGeneration) return@launch
                val queryError = failure.queryError
                if (!request.force && queryError is QueryError.CostGuard) {
                    if (warningPopupsDisabled) {
                        try {
                            val completed = service.runQuery(request.copy(force = true))
                            if (generation == runGeneration) {
                                results = completed
                                resultConnectionId = request.connectionId
                                resultSpec = executedSpec
                            }
                        } catch (forced: Exception) {
                            if (generation == runGeneration) {
                                error = forced.message ?: forced.toString()
                            }
                        }
                    } else {
                        pendingCostGuardState = PendingCostGuard(request, queryError.reason)
                        error = queryError.message
                    }
                } else {
                    error = failure.message ?: failure.toString()
                }
            } catch (e: Exception) {
                if (generation == runGeneration) error = e.message ?: e.toString()
            } finally {
                if (generation == runGeneration) {
                    running = false
                }
            }
        }
    }

    fun currentSample(connectionId: String?): BuilderQuerySample? {
        if (connectionId == null || resultConnectionId != connectionId) return null
        val result = results ?: return null
        val executedSpec = resultSpec ?: return null
        if (spec != executedSpec) return null
        return BuilderQuerySample(connectionId, executedSpec, result)
    }

    fun confirmPendingCostGuard() {
        val pending = pendingCostGuardState ?: return
        run(pending.request.copy(force = true))
    }

    fun dismissHydrationWarning() {
        hydrationWarning = null
    }

    fun clearPendingCostGuard() {
        pendingCostGuardState = null
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

internal fun JoinSpec.matchesJoin(other: JoinSpec): Boolean =
    (leftAlias == other.leftAlias && leftColumn == other.leftColumn &&
        rightAlias == other.rightAlias && rightColumn == other.rightColumn) ||
        (leftAlias == other.rightAlias && leftColumn == other.rightColumn &&
            rightAlias == other.leftAlias && rightColumn == other.leftColumn)

private fun newNodeId(): String = java.util.UUID.randomUUID().toString()
