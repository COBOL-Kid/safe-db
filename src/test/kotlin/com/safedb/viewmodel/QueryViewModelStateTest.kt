package com.safedb.viewmodel

import com.safedb.model.ColumnInfo
import com.safedb.model.ColumnSel
import com.safedb.model.ConnectionDef
import com.safedb.model.FilterGroup
import com.safedb.model.FilterLiteral
import com.safedb.model.FilterNode
import com.safedb.model.FilterOp
import com.safedb.model.FilterSpec
import com.safedb.model.FilterValue
import com.safedb.model.GroupConnector
import com.safedb.model.GroupSpec
import com.safedb.model.HistoryEntry
import com.safedb.model.JoinSpec
import com.safedb.model.LiteralKind
import com.safedb.model.QueryResult
import com.safedb.model.QuerySpec
import com.safedb.model.SavedQuery
import com.safedb.model.Schema
import com.safedb.model.Settings
import com.safedb.model.SortDirection
import com.safedb.model.SortSpec
import com.safedb.model.TableInfo
import com.safedb.model.TableRef
import com.safedb.service.SafeDbService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope

class QueryViewModelStateTest {
    private val dispatcher = StandardTestDispatcher()

    @Test
    fun nestedFilterEditingPreservesIdsAndPrunesConnectorOverrides() {
        val viewModel = QueryViewModel(NoOpService(), TestScope(dispatcher))
        viewModel.addFilter(filter("", "t0", "id"))
        viewModel.addGroupToGroup(emptyList(), GroupConnector.And)
        viewModel.addFilterToGroup(listOf(1), filter("", "t1", "customer_id"))

        val rootLeaf = (viewModel.filters.children[0] as FilterNode.Leaf).spec
        val group = (viewModel.filters.children[1] as FilterNode.Group).group
        val nestedLeaf = (group.children.single() as FilterNode.Leaf).spec
        assertTrue(rootLeaf.id.isNotEmpty())
        assertTrue(group.id.isNotEmpty())
        assertTrue(nestedLeaf.id.isNotEmpty())

        viewModel.updateFilter(listOf(0), rootLeaf.copy(column = "renamed"))
        assertEquals(rootLeaf.id, (viewModel.filters.children[0] as FilterNode.Leaf).spec.id)
        viewModel.setChildConnector(listOf(1), GroupConnector.Or)
        assertEquals(GroupConnector.Or, viewModel.connectorOverrides[group.id])

        viewModel.removeFilterNode(listOf(0))

        assertTrue(viewModel.connectorOverrides.isEmpty())
        assertEquals(group.id, (viewModel.filters.children.single() as FilterNode.Group).group.id)
    }

    @Test
    fun removingTableCascadesColumnsJoinsFiltersAndOverrides() {
        val viewModel = QueryViewModel(NoOpService(), TestScope(dispatcher))
        viewModel.addTable(table("customers", "id"))
        viewModel.addTable(table("orders", "customer_id"))
        viewModel.toggleColumn("t0", "id")
        viewModel.toggleColumn("t1", "customer_id")
        viewModel.addJoin(JoinSpec("t0", "id", "t1", "customer_id"))
        viewModel.addFilter(filter("first", "t1", "customer_id"))
        viewModel.addFilter(filter("second", "t0", "id"))
        viewModel.cycleSort("t0", "id")
        viewModel.cycleSort("t1", "customer_id")
        viewModel.toggleGroup("t0", "id")
        viewModel.setChildConnector(listOf(1), GroupConnector.Or)

        viewModel.removeTable("t0")

        assertEquals(listOf("t1"), viewModel.canvasTables.map { it.alias })
        assertEquals(setOf("t1\u0000customer_id"), viewModel.selectedColumns)
        assertTrue(viewModel.joins.isEmpty())
        assertEquals(
            listOf("first"),
            viewModel.filters.children.map { (it as FilterNode.Leaf).spec.id },
        )
        assertTrue(viewModel.connectorOverrides.isEmpty())
        assertEquals(listOf("t1"), viewModel.sorts.map { it.tableAlias })
        assertEquals(listOf("t1"), viewModel.groups.map { it.tableAlias })
    }

    @Test
    fun columnActionsTargetTheExactColumnAndCycleSortInStablePriorityOrder() {
        val viewModel = QueryViewModel(NoOpService(), TestScope(dispatcher))
        viewModel.addTable(table("customers", "id"))
        viewModel.addTable(table("orders", "customer_id"))

        viewModel.addFilterForColumn("t1", "customer_id", "int")
        val filter = (viewModel.filters.children.single() as FilterNode.Leaf).spec
        assertEquals("t1", filter.tableAlias)
        assertEquals("customer_id", filter.column)
        assertEquals(filter.id, viewModel.requestedFilterFocusId)

        viewModel.addFilterForColumn("t0", "flag", "boolean")
        assertEquals(null, viewModel.requestedFilterFocusId)
        viewModel.addFilterForColumn("t0", "deleted_at", "timestamp", FilterOp.IsNull)
        assertEquals(null, viewModel.requestedFilterFocusId)

        viewModel.cycleSort("t1", "customer_id")
        viewModel.cycleSort("t0", "id")
        assertEquals(listOf("t1", "t0"), viewModel.sorts.map { it.tableAlias })
        assertEquals(SortDirection.Asc, viewModel.sortForColumn("t1", "customer_id")?.direction)

        viewModel.cycleSort("t1", "customer_id")
        assertEquals(SortDirection.Desc, viewModel.sortForColumn("t1", "customer_id")?.direction)
        viewModel.clearSort("t1", "customer_id")
        assertEquals(null, viewModel.sortForColumn("t1", "customer_id"))
        assertEquals(listOf("t0"), viewModel.sorts.map { it.tableAlias })
    }

    @Test
    fun tableColumnToggleSelectsPartialAndClearsOnlyTheTargetTable() {
        val viewModel = QueryViewModel(NoOpService(), TestScope(dispatcher))
        viewModel.addTable(table("customers", "id", "name"))
        viewModel.addTable(table("orders", "id", "customer_id"))
        viewModel.toggleColumn("t0", "id")
        viewModel.toggleColumn("t1", "customer_id")

        viewModel.toggleAllColumns("t0")

        assertEquals(
            setOf("t0\u0000id", "t0\u0000name", "t1\u0000customer_id"),
            viewModel.selectedColumns,
        )

        viewModel.toggleAllColumns("t0")

        assertEquals(setOf("t1\u0000customer_id"), viewModel.selectedColumns)
    }

    @Test
    fun tableColumnToggleUsesColumnRulesWhileGroupingIsActive() {
        val viewModel = QueryViewModel(NoOpService(), TestScope(dispatcher))
        viewModel.addTable(table("orders", "id", "status"))
        viewModel.toggleColumn("t0", "id")
        viewModel.toggleGroup("t0", "id")

        viewModel.toggleAllColumns("t0")

        assertEquals(setOf("t0\u0000id", "t0\u0000status"), viewModel.selectedColumns)
        assertEquals(listOf(GroupSpec("t0", "id"), GroupSpec("t0", "status")), viewModel.groups)

        viewModel.toggleAllColumns("t0")

        assertTrue(viewModel.selectedColumns.isEmpty())
        assertTrue(viewModel.groups.isEmpty())
    }

    @Test
    fun groupAndSortRowsCanBeReorderedIndependently() {
        val viewModel = QueryViewModel(NoOpService(), TestScope(dispatcher))
        viewModel.addTable(table("orders", "id", "status", "created_at"))
        viewModel.setGroups(
            listOf(GroupSpec("t0", "id"), GroupSpec("t0", "status"), GroupSpec("t0", "created_at"))
        )
        viewModel.setSorts(
            listOf(
                SortSpec("t0", "id"),
                SortSpec("t0", "status", SortDirection.Desc),
                SortSpec("t0", "created_at"),
            )
        )

        viewModel.moveGroup(2, 0)
        viewModel.moveSort(0, 2)

        assertEquals(listOf("created_at", "id", "status"), viewModel.spec.groups.map { it.column })
        assertEquals(listOf("status", "created_at", "id"), viewModel.spec.sorts.map { it.column })
        assertEquals(SortDirection.Desc, viewModel.spec.sorts.first().direction)
    }

    @Test
    fun distinctIsIncludedRestoredAndResetWithBuilderState() {
        val viewModel = QueryViewModel(NoOpService(), TestScope(dispatcher))
        val orders = table("orders", "id")
        viewModel.addTable(orders)
        viewModel.setDistinct(true)

        assertTrue(viewModel.distinct)
        assertTrue(viewModel.spec.distinct)

        viewModel.clear()

        assertFalse(viewModel.distinct)
        viewModel.restoreFromSpec(
            QuerySpec(
                tables = listOf(TableRef("app", "orders", "saved_orders")),
                filters = FilterGroup.empty(),
                limit = 100,
                distinct = true,
            ),
            listOf(orders),
        )
        assertTrue(viewModel.distinct)
        assertTrue(viewModel.spec.distinct)
    }

    @Test
    fun distinctSortConflictsDisableRunAndOfferExplicitRepairs() {
        val viewModel = QueryViewModel(NoOpService(), TestScope(dispatcher))
        viewModel.addTable(table("orders", "id", "status", "created_at"))
        viewModel.toggleColumn("t0", "id")
        viewModel.setSort("t0", "status", SortDirection.Asc)
        viewModel.setSort("t0", "created_at", SortDirection.Desc)
        viewModel.setDistinct(true)

        assertEquals(
            listOf("status", "created_at"),
            viewModel.distinctSortConflicts.map { it.column },
        )
        assertFalse(viewModel.canRun)

        viewModel.selectDistinctSortColumns()

        assertTrue(viewModel.isColumnSelected("t0", "status"))
        assertTrue(viewModel.isColumnSelected("t0", "created_at"))
        assertTrue(viewModel.distinctSortConflicts.isEmpty())
        assertTrue(viewModel.canRun)

        viewModel.toggleColumn("t0", "status")
        assertEquals(listOf("status"), viewModel.distinctSortConflicts.map { it.column })
        assertFalse(viewModel.canRun)

        viewModel.removeDistinctSortConflicts()

        assertEquals(listOf("created_at"), viewModel.sorts.map { it.column })
        assertTrue(viewModel.distinctSortConflicts.isEmpty())
        assertTrue(viewModel.canRun)
    }

    @Test
    fun groupActionsAppendByPriorityAutoSelectAndKeepGroupedBuilderStateValid() {
        val viewModel = QueryViewModel(NoOpService(), TestScope(dispatcher))
        viewModel.addTable(table("customers", "id"))
        viewModel.addTable(table("orders", "customer_id"))

        viewModel.toggleColumn("t0", "id")
        viewModel.toggleGroup("t1", "customer_id")
        assertEquals(
            listOf(GroupSpec("t1", "customer_id"), GroupSpec("t0", "id")),
            viewModel.groups,
        )
        assertTrue(viewModel.isColumnSelected("t1", "customer_id"))

        viewModel.clearGroup("t1", "customer_id")
        viewModel.toggleGroup("t1", "customer_id")
        assertEquals(listOf("t0", "t1"), viewModel.groups.map { it.tableAlias })

        viewModel.cycleSort("t0", "id")
        assertEquals(SortDirection.Asc, viewModel.sortForColumn("t0", "id")?.direction)
    }

    @Test
    fun uncheckingAutoGroupedColumnRemovesItFromSelectionAndGrouping() {
        val viewModel = QueryViewModel(NoOpService(), TestScope(dispatcher))
        viewModel.addTable(table("orders", "id", "status"))
        viewModel.toggleColumn("t0", "id")
        viewModel.toggleGroup("t0", "id")

        viewModel.toggleColumn("t0", "status")

        assertTrue(viewModel.isColumnSelected("t0", "status"))
        assertEquals(listOf(GroupSpec("t0", "id"), GroupSpec("t0", "status")), viewModel.groups)

        viewModel.toggleColumn("t0", "status")

        assertFalse(viewModel.isColumnSelected("t0", "status"))
        assertEquals(listOf(GroupSpec("t0", "id")), viewModel.groups)
    }

    @Test
    fun restoringGroupOnlyColumnPreservesExplicitOutputsAndAllowsSelectingItLater() {
        val viewModel = QueryViewModel(NoOpService(), TestScope(dispatcher))
        val orders = table("orders", "id", "status")
        val savedSpec =
            QuerySpec(
                tables = listOf(TableRef("app", "orders", "saved_orders")),
                columns = listOf(ColumnSel("saved_orders", "id")),
                joins = emptyList(),
                filters = FilterGroup.empty(),
                limit = 100,
                groups =
                    listOf(GroupSpec("saved_orders", "id"), GroupSpec("saved_orders", "status")),
            )

        viewModel.restoreFromSpec(savedSpec, listOf(orders))

        assertEquals(listOf(ColumnSel("t0", "id")), viewModel.spec.columns)
        assertEquals(listOf(GroupSpec("t0", "id"), GroupSpec("t0", "status")), viewModel.groups)

        viewModel.toggleColumn("t0", "status")

        assertTrue(ColumnSel("t0", "status") in viewModel.spec.columns)
    }

    @Test
    fun removingOnlySelectedRestoredGroupPromotesRemainingGroupToOutput() {
        val viewModel = QueryViewModel(NoOpService(), TestScope(dispatcher))
        val orders = table("orders", "id", "status")
        val savedSpec =
            QuerySpec(
                tables = listOf(TableRef("app", "orders", "saved_orders")),
                columns = listOf(ColumnSel("saved_orders", "id")),
                joins = emptyList(),
                filters = FilterGroup.empty(),
                limit = 100,
                groups =
                    listOf(GroupSpec("saved_orders", "id"), GroupSpec("saved_orders", "status")),
            )
        viewModel.restoreFromSpec(savedSpec, listOf(orders))

        viewModel.toggleGroup("t0", "id")

        assertEquals(listOf(GroupSpec("t0", "status")), viewModel.groups)
        assertEquals(listOf(ColumnSel("t0", "status")), viewModel.spec.columns)
    }

    @Test
    fun limitsAndClearRestoreStableDefaults() {
        val viewModel = QueryViewModel(NoOpService(), TestScope(dispatcher))
        viewModel.addTable(table("customers", "id"))
        viewModel.setLimit(0)
        assertEquals(1, viewModel.limit)
        viewModel.setLimit(Int.MAX_VALUE)
        assertEquals(5_000, viewModel.limit)

        viewModel.clear()

        assertEquals(100, viewModel.limit)
        assertEquals(0, viewModel.tableCount)
        assertTrue(viewModel.filters.id.isNotEmpty())
        assertNotEquals("", viewModel.filters.id)
    }

    @Test
    fun newTablesUseCanvasLocalCoordinates() {
        val viewModel = QueryViewModel(NoOpService(), TestScope(dispatcher))

        viewModel.addTable(table("orders", "id"))
        viewModel.addTable(table("customers", "id"))

        assertEquals(0f, viewModel.canvasTables[0].y)
        assertEquals(30f, viewModel.canvasTables[1].y)
    }

    private fun filter(id: String, alias: String, column: String) =
        FilterSpec(
            id = id,
            tableAlias = alias,
            column = column,
            op = FilterOp.Eq,
            value = FilterValue.Single(FilterLiteral(LiteralKind.Int, "1")),
        )

    private fun table(name: String, vararg columns: String) =
        TableInfo(
            schema = "app",
            name = name,
            columns =
                columns.map { column ->
                    ColumnInfo(column, "int", nullable = false, isIndexed = true)
                },
            indexes = emptyList(),
        )
}

private class NoOpService : SafeDbService {
    override suspend fun testConnection(def: ConnectionDef, password: String?) = "ok"

    override suspend fun createConnection(def: ConnectionDef, password: String) = def

    override suspend fun updateConnection(def: ConnectionDef, password: String?) = Unit

    override suspend fun listConnections() = emptyList<ConnectionDef>()

    override suspend fun deleteConnection(id: String) = Unit

    override suspend fun lockCredentials() = Unit

    override suspend fun getSchema(connectionId: String) = Schema(emptyList())

    override suspend fun runQuery(request: com.safedb.service.QueryRunRequest) =
        queryRunResult(QueryResult(emptyList(), emptyList(), 0, false, emptyList()))

    override suspend fun listSavedQueries() = emptyList<SavedQuery>()

    override suspend fun saveSavedQuery(query: SavedQuery) = Unit

    override suspend fun deleteSavedQuery(id: String) = Unit

    override suspend fun listHistory() = emptyList<HistoryEntry>()

    override suspend fun clearHistory() = Unit

    override suspend fun getSettings() = Settings.default()

    override suspend fun saveSettings(settings: Settings) = Unit
}
