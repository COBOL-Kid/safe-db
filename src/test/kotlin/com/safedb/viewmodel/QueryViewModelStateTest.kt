package com.safedb.viewmodel

import com.safedb.model.ColumnInfo
import com.safedb.model.ConnectionDef
import com.safedb.model.FilterLiteral
import com.safedb.model.FilterNode
import com.safedb.model.FilterOp
import com.safedb.model.FilterSpec
import com.safedb.model.FilterValue
import com.safedb.model.GroupConnector
import com.safedb.model.HistoryEntry
import com.safedb.model.JoinSpec
import com.safedb.model.LiteralKind
import com.safedb.model.QueryResult
import com.safedb.model.QuerySpec
import com.safedb.model.SavedQuery
import com.safedb.model.Schema
import com.safedb.model.Settings
import com.safedb.model.TableInfo
import com.safedb.service.SafeDbService
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

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
        viewModel.setChildConnector(listOf(1), GroupConnector.Or)

        viewModel.removeTable("t0")

        assertEquals(listOf("t1"), viewModel.canvasTables.map { it.alias })
        assertEquals(setOf("t1\u0000customer_id"), viewModel.selectedColumns)
        assertTrue(viewModel.joins.isEmpty())
        assertEquals(listOf("first"), viewModel.filters.children.map { (it as FilterNode.Leaf).spec.id })
        assertTrue(viewModel.connectorOverrides.isEmpty())
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

    private fun filter(id: String, alias: String, column: String) = FilterSpec(
        id = id,
        tableAlias = alias,
        column = column,
        op = FilterOp.Eq,
        value = FilterValue.Single(FilterLiteral(LiteralKind.Int, "1")),
    )

    private fun table(name: String, column: String) = TableInfo(
        schema = "app",
        name = name,
        columns = listOf(ColumnInfo(column, "int", nullable = false, isIndexed = true)),
        indexes = emptyList(),
    )
}

private class NoOpService : SafeDbService {
    override suspend fun testConnection(def: ConnectionDef, password: String) = "ok"
    override suspend fun saveConnection(def: ConnectionDef, password: String?) = Unit
    override suspend fun createConnection(def: ConnectionDef, password: String) = def
    override suspend fun updateConnection(def: ConnectionDef, password: String?) = Unit
    override suspend fun listConnections() = emptyList<ConnectionDef>()
    override suspend fun deleteConnection(id: String) = Unit
    override suspend fun lockCredentials() = Unit
    override suspend fun getSchema(connectionId: String) = Schema(emptyList())
    override suspend fun runQuery(connectionId: String, spec: QuerySpec, force: Boolean) =
        QueryResult(emptyList(), emptyList(), 0, false, emptyList())
    override suspend fun listSavedQueries() = emptyList<SavedQuery>()
    override suspend fun saveSavedQuery(query: SavedQuery) = Unit
    override suspend fun deleteSavedQuery(id: String) = Unit
    override suspend fun listHistory() = emptyList<HistoryEntry>()
    override suspend fun clearHistory() = Unit
    override suspend fun getSettings() = Settings.default()
    override suspend fun saveSettings(settings: Settings) = Unit
}
