package com.safedb.viewmodel

import com.safedb.model.ConnectionDef
import com.safedb.model.Dialect
import com.safedb.model.FilterGroup
import com.safedb.model.HistoryEntry
import com.safedb.model.QueryResult
import com.safedb.model.QuerySpec
import com.safedb.model.ResultColumn
import com.safedb.model.SavedQuery
import com.safedb.model.Schema
import com.safedb.model.Settings
import com.safedb.model.TableInfo
import com.safedb.model.TransportSecurity
import com.safedb.service.SafeDbService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ViewModelsTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun connectionsViewModelLoadsAndDeletes() = runTest(dispatcher) {
        val service = RecordingSafeDbService()
        val scope = TestScope(dispatcher)
        val viewModel = ConnectionsViewModel(service, scope)

        viewModel.refresh()
        advanceUntilIdle()
        assertEquals(1, viewModel.connections.value.size)

        viewModel.delete("c1") {}
        advanceUntilIdle()
        assertTrue(viewModel.connections.value.isEmpty())
        assertEquals("c1", service.deletedIds.single())
    }

    @Test
    fun schemaViewModelLoadsAndFiltersTables() = runTest(dispatcher) {
        val service = RecordingSafeDbService()
        val scope = TestScope(dispatcher)
        val viewModel = SchemaViewModel(service, scope)

        viewModel.load("c1")
        advanceUntilIdle()
        assertEquals("c1", viewModel.loadedConnectionId)
        assertEquals(2, viewModel.tables.size)

        viewModel.search = "order"
        assertEquals(1, viewModel.filteredTables.size)
        assertEquals("orders", viewModel.filteredTables.single().name)
    }

    @Test
    fun historyViewModelLoadsAndClears() = runTest(dispatcher) {
        val service = RecordingSafeDbService()
        val scope = TestScope(dispatcher)
        val viewModel = HistoryViewModel(service, scope)

        viewModel.refresh()
        advanceUntilIdle()
        assertEquals(1, viewModel.entries.value.size)

        viewModel.clear()
        advanceUntilIdle()
        assertTrue(viewModel.entries.value.isEmpty())
        assertTrue(service.historyCleared)
    }

    @Test
    fun savedQueriesViewModelLoadsAndDeletes() = runTest(dispatcher) {
        val service = RecordingSafeDbService()
        val scope = TestScope(dispatcher)
        val viewModel = SavedQueriesViewModel(service, scope)

        viewModel.refresh()
        advanceUntilIdle()
        assertEquals(1, viewModel.queries.value.size)

        viewModel.delete("q1")
        advanceUntilIdle()
        assertTrue(viewModel.queries.value.isEmpty())
        assertEquals("q1", service.deletedSavedIds.single())
    }

    @Test
    fun settingsViewModelLoadsAndTogglesTheme() = runTest(dispatcher) {
        val service = RecordingSafeDbService()
        val scope = TestScope(dispatcher)
        val viewModel = SettingsViewModel(service, scope)

        viewModel.load()
        advanceUntilIdle()
        assertEquals("light", viewModel.settings.value.theme)

        viewModel.toggleTheme()
        advanceUntilIdle()
        assertEquals("dark", viewModel.settings.value.theme)
        assertEquals("dark", service.savedSettings?.theme)
    }

    @Test
    fun settingsViewModelRejectsInvalidThreshold() = runTest(dispatcher) {
        val service = RecordingSafeDbService()
        val scope = TestScope(dispatcher)
        val viewModel = SettingsViewModel(service, scope)
        viewModel.load()
        advanceUntilIdle()

        viewModel.saveThresholds(mapOf(Dialect.Postgres to 0.5))
        advanceUntilIdle()

        assertNull(service.savedSettings)
        assertTrue(viewModel.saveError.value?.contains("PostgreSQL") == true)
    }
}

private class RecordingSafeDbService : SafeDbService {
    val deletedIds = mutableListOf<String>()
    val deletedSavedIds = mutableListOf<String>()
    var historyCleared = false
    var savedSettings: Settings? = null

    private val connection = ConnectionDef(
        id = "c1",
        name = "Local MySQL",
        dialect = Dialect.MySql,
        host = "localhost",
        port = 3306,
        database = "safedb_test",
        username = "root",
        transportSecurity = TransportSecurity(),
    )

    override suspend fun testConnection(def: ConnectionDef, password: String): String = "ok"
    override suspend fun saveConnection(def: ConnectionDef, password: String?) = Unit
    override suspend fun createConnection(def: ConnectionDef, password: String): ConnectionDef = def
    override suspend fun updateConnection(def: ConnectionDef, password: String?) = Unit
    override suspend fun listConnections(): List<ConnectionDef> =
        if (deletedIds.isEmpty()) listOf(connection) else emptyList()

    override suspend fun deleteConnection(id: String) {
        deletedIds.add(id)
    }

    override suspend fun lockCredentials() = Unit

    override suspend fun getSchema(connectionId: String): Schema = Schema(
        tables = listOf(
            TableInfo("safedb_test", "customers", emptyList(), emptyList()),
            TableInfo("safedb_test", "orders", emptyList(), emptyList()),
        ),
    )

    override suspend fun runQuery(connectionId: String, spec: QuerySpec, force: Boolean): QueryResult =
        QueryResult(emptyList(), emptyList(), 0, false, emptyList())

    override suspend fun listSavedQueries(): List<SavedQuery> =
        if (deletedSavedIds.isEmpty()) {
            listOf(SavedQuery("q1", "Customers", "c1", emptyQuerySpec(), "1"))
        } else {
            emptyList()
        }

    override suspend fun saveSavedQuery(query: SavedQuery) = Unit
    override suspend fun deleteSavedQuery(id: String) {
        deletedSavedIds.add(id)
    }

    override suspend fun listHistory(): List<HistoryEntry> =
        if (historyCleared) emptyList() else listOf(
            HistoryEntry("h1", "c1", "Local MySQL", emptyQuerySpec(), 3, emptyList(), timestamp = "1"),
        )

    override suspend fun clearHistory() {
        historyCleared = true
    }

    override suspend fun getSettings(): Settings = Settings.default()
    override suspend fun saveSettings(settings: Settings) {
        savedSettings = settings
    }
}

private fun emptyQuerySpec() = QuerySpec(
    tables = emptyList(),
    columns = emptyList(),
    joins = emptyList(),
    filters = FilterGroup.empty(),
    limit = 100,
)
