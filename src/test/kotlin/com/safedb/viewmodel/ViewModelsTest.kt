package com.safedb.viewmodel

import com.safedb.SchemaSelectionIntent
import com.safedb.SchemaSelectionSource
import com.safedb.model.ConnectionDef
import com.safedb.model.Dialect
import com.safedb.model.FilterGroup
import com.safedb.model.HistoryEntry
import com.safedb.model.JoinSpec
import com.safedb.model.QueryResult
import com.safedb.model.QueryRiskGate
import com.safedb.model.QuerySpec
import com.safedb.model.SavedQuery
import com.safedb.model.Schema
import com.safedb.model.Settings
import com.safedb.model.TableInfo
import com.safedb.model.ThemePalette
import com.safedb.model.TransportSecurity
import com.safedb.service.FakeSafeDbServiceSupport
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

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
    fun connectionsViewModelLoadsAndDeletes() =
        runTest(dispatcher) {
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
    fun schemaViewModelLoadsAndFiltersTables() =
        runTest(dispatcher) {
            val service = RecordingSafeDbService()
            val scope = TestScope(dispatcher)
            val viewModel = SchemaViewModel(service, scope)

            viewModel.load("c1")
            advanceUntilIdle()
            assertEquals("c1", viewModel.loadedConnectionId)
            assertEquals(3, viewModel.tables.size)
            assertNull(viewModel.selectedSchema)
            assertTrue(viewModel.filteredTables.isEmpty())

            viewModel.selectSchema("safedb_test")
            viewModel.search = "order"
            assertEquals(1, viewModel.filteredTables.size)
            assertEquals("orders", viewModel.filteredTables.single().name)
        }

    @Test
    fun schemaViewModelReloadsAnInvalidatedConnection() =
        runTest(dispatcher) {
            val service = RecordingSafeDbService()
            val viewModel = SchemaViewModel(service, TestScope(dispatcher))

            viewModel.load("c1")
            advanceUntilIdle()
            assertEquals(1, service.schemaLoadCount)

            viewModel.invalidateConnection("c1")
            assertNull(viewModel.schema)
            assertNull(viewModel.loadedConnectionId)

            viewModel.load("c1")
            advanceUntilIdle()
            assertEquals(2, service.schemaLoadCount)
            assertEquals("c1", viewModel.loadedConnectionId)
        }

    @Test
    fun schemaViewModelAppliesPreferredSchemaBeforeSearch() =
        runTest(dispatcher) {
            val service = RecordingSafeDbService()
            val scope = TestScope(dispatcher)
            val viewModel = SchemaViewModel(service, scope)

            viewModel.load(
                "c1",
                selection =
                    SchemaSelectionIntent("reporting", SchemaSelectionSource.ConnectionHistory),
            )
            advanceUntilIdle()

            assertEquals(listOf("reporting", "safedb_test"), viewModel.schemaOptions)
            assertEquals("reporting", viewModel.selectedSchema)
            assertEquals(listOf("events"), viewModel.filteredTables.map { it.name })

            viewModel.search = "event"
            assertEquals(listOf("events"), viewModel.filteredTables.map { it.name })
            viewModel.selectSchema(null)
            assertTrue(viewModel.filteredTables.isEmpty())
        }

    @Test
    fun schemaViewModelFallsBackVisiblyWhenPreferredSchemaIsMissing() =
        runTest(dispatcher) {
            val service = RecordingSafeDbService()
            val scope = TestScope(dispatcher)
            val viewModel = SchemaViewModel(service, scope)

            var unavailable: SchemaSelectionIntent? = null
            viewModel.load(
                "c1",
                selection =
                    SchemaSelectionIntent("missing", SchemaSelectionSource.ConnectionHistory),
                onUnavailableSelection = { unavailable = it },
            )
            advanceUntilIdle()

            assertNull(viewModel.selectedSchema)
            assertTrue(viewModel.preferredSchemaWarning?.contains("missing") == true)
            assertTrue(viewModel.filteredTables.isEmpty())
            assertEquals(SchemaSelectionSource.ConnectionHistory, unavailable?.source)
        }

    @Test
    fun historyViewModelLoadsAndClears() =
        runTest(dispatcher) {
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
    fun savedQueriesViewModelLoadsAndDeletes() =
        runTest(dispatcher) {
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
    fun settingsViewModelLoadsAndTogglesTheme() =
        runTest(dispatcher) {
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
    fun settingsViewModelPersistsModeAndColorSchemeWithoutRedundantSaves() =
        runTest(dispatcher) {
            val service = RecordingSafeDbService()
            val scope = TestScope(dispatcher)
            val viewModel = SettingsViewModel(service, scope)
            viewModel.load()
            advanceUntilIdle()

            viewModel.setDarkMode(false)
            viewModel.setColorScheme(ThemePalette.ControlBlue)
            advanceUntilIdle()
            assertEquals(0, service.settingsSaveCount)

            viewModel.setColorScheme(ThemePalette.Oxide)
            advanceUntilIdle()
            assertEquals(ThemePalette.Oxide.id, viewModel.settings.value.colorScheme)
            assertEquals(ThemePalette.Oxide.id, service.savedSettings?.colorScheme)
            assertEquals(1, service.settingsSaveCount)

            viewModel.setDarkMode(true)
            advanceUntilIdle()
            assertEquals("dark", viewModel.settings.value.theme)
            assertEquals(2, service.settingsSaveCount)

            viewModel.setQueryRiskGate(QueryRiskGate.Flexible)
            advanceUntilIdle()
            assertEquals(QueryRiskGate.Flexible, viewModel.settings.value.queryRiskGate)
            assertEquals(QueryRiskGate.Flexible, service.savedSettings?.queryRiskGate)
            assertEquals(3, service.settingsSaveCount)
        }

    @Test
    fun settingsViewModelSerializesMutationsWithoutLostUpdates() =
        runTest(dispatcher) {
            val service = RecordingSafeDbService()
            val firstSaveStarted = CompletableDeferred<Unit>()
            val saveGate = CompletableDeferred<Unit>()
            service.settingsSaveStarted = firstSaveStarted
            service.settingsSaveGate = saveGate
            val viewModel = SettingsViewModel(service, TestScope(dispatcher))
            viewModel.load()

            viewModel.setDarkMode(true)
            viewModel.setColorScheme(ThemePalette.Oxide)
            viewModel.setQueryRiskGate(QueryRiskGate.Flexible)
            runCurrent()

            assertTrue(firstSaveStarted.isCompleted)
            assertEquals(1, service.settingsSaveCount)

            saveGate.complete(Unit)
            advanceUntilIdle()

            assertEquals("dark", viewModel.settings.value.theme)
            assertEquals(ThemePalette.Oxide.id, viewModel.settings.value.colorScheme)
            assertEquals(QueryRiskGate.Flexible, viewModel.settings.value.queryRiskGate)
            assertEquals(viewModel.settings.value, service.savedSettings)
            assertEquals(3, service.settingsSaveCount)
        }

    @Test
    fun settingsViewModelLoadsSchemasAndSavesDefaultLocationAtomically() =
        runTest(dispatcher) {
            val service = RecordingSafeDbService()
            val scope = TestScope(dispatcher)
            val viewModel = SettingsViewModel(service, scope)
            viewModel.load()
            advanceUntilIdle()

            viewModel.loadDefaultSchemaOptions("c1")
            advanceUntilIdle()
            assertEquals(listOf("reporting", "safedb_test"), viewModel.defaultSchemaOptions.value)
            assertNull(viewModel.defaultSchemaError.value)

            var saved = false
            viewModel.saveDefaultLocation("c1", "reporting") { saved = true }
            advanceUntilIdle()

            assertTrue(saved)
            assertEquals("c1", viewModel.settings.value.defaultConnectionId)
            assertEquals("reporting", viewModel.settings.value.defaultSchema)
            assertEquals("c1", service.savedSettings?.defaultConnectionId)
            assertEquals("reporting", service.savedSettings?.defaultSchema)
        }

    @Test
    fun settingsViewModelPersistsIndependentPerConnectionSchemaHistory() =
        runTest(dispatcher) {
            val service = RecordingSafeDbService()
            val viewModel = SettingsViewModel(service, TestScope(dispatcher))
            viewModel.load()
            advanceUntilIdle()

            viewModel.rememberLastSchema(" c1 ", " reporting ")
            viewModel.rememberLastSchema("c2", "analytics")
            advanceUntilIdle()

            assertEquals(
                mapOf("c1" to "reporting", "c2" to "analytics"),
                viewModel.settings.value.lastSelectedSchemas,
            )
            assertEquals(
                viewModel.settings.value.lastSelectedSchemas,
                service.savedSettings?.lastSelectedSchemas,
            )
            assertNull(viewModel.schemaHistoryError.value)
        }

    @Test
    fun settingsViewModelKeepsLastPersistedHistoryWhenSaveFails() =
        runTest(dispatcher) {
            val service = RecordingSafeDbService()
            val viewModel = SettingsViewModel(service, TestScope(dispatcher))
            viewModel.load()
            advanceUntilIdle()
            viewModel.rememberLastSchema("c1", "reporting")
            advanceUntilIdle()

            service.failSettingsSave = true
            viewModel.rememberLastSchema("c1", "analytics")
            advanceUntilIdle()

            assertEquals(mapOf("c1" to "reporting"), viewModel.settings.value.lastSelectedSchemas)
            assertEquals("settings save failed", viewModel.schemaHistoryError.value)
        }

    @Test
    fun settingsViewModelKeepsDefaultWhenSchemaLoadOrSaveFails() =
        runTest(dispatcher) {
            val service = RecordingSafeDbService()
            val scope = TestScope(dispatcher)
            val viewModel = SettingsViewModel(service, scope)
            viewModel.load()
            advanceUntilIdle()
            viewModel.loadDefaultSchemaOptions("c1")
            advanceUntilIdle()
            viewModel.saveDefaultLocation("c1", "safedb_test")
            advanceUntilIdle()

            service.failSchemaLoad = true
            viewModel.loadDefaultSchemaOptions("c2")
            advanceUntilIdle()
            assertTrue(viewModel.defaultSchemaError.value?.contains("schema load failed") == true)
            assertTrue(viewModel.defaultSchemaOptions.value.isEmpty())
            assertEquals("c1", viewModel.settings.value.defaultConnectionId)

            service.failSchemaLoad = false
            viewModel.loadDefaultSchemaOptions("c2")
            advanceUntilIdle()
            service.failSettingsSave = true
            viewModel.saveDefaultLocation("c2", "reporting")
            advanceUntilIdle()
            assertEquals("c1", viewModel.settings.value.defaultConnectionId)
            assertEquals("safedb_test", viewModel.settings.value.defaultSchema)
        }

    @Test
    fun settingsViewModelIgnoresStaleSchemaResponses() =
        runTest(dispatcher) {
            val service = RecordingSafeDbService()
            val first = CompletableDeferred<Schema>()
            val second = CompletableDeferred<Schema>()
            service.schemaResponses["c1"] = first
            service.schemaResponses["c2"] = second
            val viewModel = SettingsViewModel(service, TestScope(dispatcher))

            viewModel.loadDefaultSchemaOptions("c1")
            runCurrent()
            viewModel.loadDefaultSchemaOptions("c2")
            runCurrent()
            second.complete(
                Schema(listOf(TableInfo("current", "events", emptyList(), emptyList())))
            )
            runCurrent()
            first.complete(Schema(listOf(TableInfo("stale", "events", emptyList(), emptyList()))))
            advanceUntilIdle()

            assertEquals(listOf("current"), viewModel.defaultSchemaOptions.value)
            assertNull(viewModel.defaultSchemaError.value)
        }

    @Test
    fun settingsViewModelClearsDefaultOnlyForMatchingDeletedConnection() =
        runTest(dispatcher) {
            val service = RecordingSafeDbService()
            val scope = TestScope(dispatcher)
            val viewModel = SettingsViewModel(service, scope)
            viewModel.load()
            advanceUntilIdle()
            viewModel.loadDefaultSchemaOptions("c1")
            advanceUntilIdle()
            viewModel.saveDefaultLocation("c1", "safedb_test")
            viewModel.rememberLastSchema("c1", "reporting")
            viewModel.rememberLastSchema("c2", "analytics")
            advanceUntilIdle()

            viewModel.clearDefaultIfConnection("c2")
            advanceUntilIdle()
            assertEquals("c1", viewModel.settings.value.defaultConnectionId)
            assertEquals(mapOf("c1" to "reporting"), viewModel.settings.value.lastSelectedSchemas)

            viewModel.clearDefaultIfConnection("c1")
            advanceUntilIdle()
            assertNull(viewModel.settings.value.defaultConnectionId)
            assertNull(viewModel.settings.value.defaultSchema)
            assertTrue(viewModel.settings.value.lastSelectedSchemas.isEmpty())
        }

    @Test
    fun queryViewModelRemovesJoinByExactOrReversedMatch() {
        val service = RecordingSafeDbService()
        val scope = TestScope(dispatcher)
        val viewModel = QueryViewModel(service, scope)
        val customerJoin = JoinSpec("t0", "customer_id", "t1", "id")
        val productJoin = JoinSpec("t2", "product_id", "t3", "id")

        viewModel.addJoin(customerJoin)
        viewModel.addJoin(productJoin)

        viewModel.removeJoin(JoinSpec("t1", "id", "t0", "customer_id"))

        assertEquals(listOf(productJoin), viewModel.joins.toList())

        viewModel.removeJoin(JoinSpec("t4", "id", "t5", "id"))
        assertEquals(listOf(productJoin), viewModel.joins.toList())

        viewModel.removeJoin(productJoin)
        assertTrue(viewModel.joins.isEmpty())
    }

    @Test
    fun historyFailurePreservesEntriesAndOnlyCompletesAfterRetry() =
        runTest(dispatcher) {
            val service = RecordingSafeDbService()
            val scope = TestScope(dispatcher)
            val viewModel = HistoryViewModel(service, scope)
            viewModel.refresh()
            advanceUntilIdle()

            service.failHistoryClear = true
            var completed = false
            viewModel.clear { completed = true }
            advanceUntilIdle()

            assertFalse(completed)
            assertEquals(1, viewModel.entries.value.size)
            assertEquals("history clear failed", viewModel.error.value)

            service.failHistoryClear = false
            viewModel.clear { completed = true }
            advanceUntilIdle()
            assertTrue(completed)
            assertTrue(viewModel.entries.value.isEmpty())
            assertNull(viewModel.error.value)
        }

    @Test
    fun savedQueryFailurePreservesLastGoodListAndSuppressesCallback() =
        runTest(dispatcher) {
            val service = RecordingSafeDbService()
            val scope = TestScope(dispatcher)
            val viewModel = SavedQueriesViewModel(service, scope)
            viewModel.refresh()
            advanceUntilIdle()

            service.failSavedMutation = true
            var completed = false
            viewModel.save(SavedQuery("q2", "New", "c1", emptyQuerySpec(), "2")) {
                completed = true
            }
            advanceUntilIdle()

            assertFalse(completed)
            assertEquals(listOf("q1"), viewModel.queries.value.map { it.id })
            assertEquals("saved query mutation failed", viewModel.error.value)
        }

    @Test
    fun settingsSaveFailureKeepsCurrentSettingsAndExposesError() =
        runTest(dispatcher) {
            val service = RecordingSafeDbService()
            val scope = TestScope(dispatcher)
            val viewModel = SettingsViewModel(service, scope)
            viewModel.load()
            advanceUntilIdle()
            service.failSettingsSave = true

            viewModel.toggleTheme()
            advanceUntilIdle()

            assertEquals("light", viewModel.settings.value.theme)
            assertEquals("settings save failed", viewModel.saveError.value)
        }
}

private class RecordingSafeDbService : FakeSafeDbServiceSupport() {
    val deletedIds = mutableListOf<String>()
    val deletedSavedIds = mutableListOf<String>()
    var historyCleared = false
    var savedSettings: Settings? = null
    var failHistoryClear = false
    var failSavedMutation = false
    var failSettingsSave = false
    var failSchemaLoad = false
    var schemaLoadCount = 0
    val schemaResponses = mutableMapOf<String, CompletableDeferred<Schema>>()
    var settingsSaveCount = 0
    var settingsSaveStarted: CompletableDeferred<Unit>? = null
    var settingsSaveGate: CompletableDeferred<Unit>? = null

    private val connection =
        ConnectionDef(
            id = "c1",
            name = "Local MySQL",
            dialect = Dialect.MySql,
            host = "localhost",
            port = 3306,
            database = "safedb_test",
            username = "root",
            transportSecurity = TransportSecurity(),
        )

    override suspend fun testConnection(def: ConnectionDef, password: String?): String = "ok"

    override suspend fun createConnection(def: ConnectionDef, password: String): ConnectionDef = def

    override suspend fun updateConnection(def: ConnectionDef, password: String?) = Unit

    override suspend fun listConnections(): List<ConnectionDef> =
        if (deletedIds.isEmpty()) listOf(connection) else emptyList()

    override suspend fun deleteConnection(id: String) {
        deletedIds.add(id)
    }

    override suspend fun lockCredentials() = Unit

    override suspend fun getSchema(connectionId: String): Schema {
        schemaLoadCount += 1
        if (failSchemaLoad) error("schema load failed")
        schemaResponses[connectionId]?.let {
            return it.await()
        }
        return Schema(
            tables =
                listOf(
                    TableInfo("safedb_test", "customers", emptyList(), emptyList()),
                    TableInfo("safedb_test", "orders", emptyList(), emptyList()),
                    TableInfo("reporting", "events", emptyList(), emptyList()),
                )
        )
    }

    override suspend fun runQuery(request: com.safedb.service.QueryRunRequest) =
        queryRunResult(QueryResult(emptyList(), emptyList(), 0, false, emptyList()))

    override suspend fun listSavedQueries(): List<SavedQuery> =
        if (deletedSavedIds.isEmpty()) {
            listOf(SavedQuery("q1", "Customers", "c1", emptyQuerySpec(), "1"))
        } else {
            emptyList()
        }

    override suspend fun saveSavedQuery(query: SavedQuery) {
        if (failSavedMutation) error("saved query mutation failed")
    }

    override suspend fun deleteSavedQuery(id: String) {
        if (failSavedMutation) error("saved query mutation failed")
        deletedSavedIds.add(id)
    }

    override suspend fun listHistory(): List<HistoryEntry> =
        if (historyCleared) emptyList()
        else
            listOf(
                HistoryEntry(
                    "h1",
                    "c1",
                    "Local MySQL",
                    emptyQuerySpec(),
                    3,
                    emptyList(),
                    timestamp = "1",
                )
            )

    override suspend fun clearHistory() {
        if (failHistoryClear) error("history clear failed")
        historyCleared = true
    }

    override suspend fun getSettings(): Settings = Settings.default()

    override suspend fun saveSettings(settings: Settings) {
        if (failSettingsSave) error("settings save failed")
        settingsSaveCount += 1
        settingsSaveStarted?.complete(Unit)
        settingsSaveGate?.await()
        savedSettings = settings
    }
}

private fun emptyQuerySpec() =
    QuerySpec(
        tables = emptyList(),
        columns = emptyList(),
        joins = emptyList(),
        filters = FilterGroup.empty(),
        limit = 100,
    )
