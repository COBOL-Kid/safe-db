package com.safedb.viewmodel

import com.safedb.SchemaSelectionIntent
import com.safedb.SchemaSelectionSource
import com.safedb.model.ConnectionDef
import com.safedb.model.Dialect
import com.safedb.model.FilterGroup
import com.safedb.model.HistoryEntry
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
    fun connectionCreateSuccessIsIndependentFromRefreshFailure() =
        runTest(dispatcher) {
            val service = RecordingSafeDbService().apply { failConnectionList = true }
            val viewModel = ConnectionsViewModel(service, TestScope(dispatcher))
            val created =
                ConnectionDef(
                    id = "created",
                    name = "Created",
                    dialect = Dialect.Postgres,
                    host = "localhost",
                    port = 5432,
                    database = "db",
                    username = "user",
                )

            assertEquals(created, viewModel.createConnection(created, "secret"))
            assertEquals(listOf(created), service.createdConnections)
            assertEquals(0, service.connectionListCalls)

            viewModel.refresh()
            advanceUntilIdle()

            assertEquals(1, service.connectionListCalls)
            assertEquals("connection list failed", viewModel.error.value)
        }

    @Test
    fun connectionUpdateSuccessIsIndependentFromRefreshFailure() =
        runTest(dispatcher) {
            val service = RecordingSafeDbService().apply { failConnectionList = true }
            val viewModel = ConnectionsViewModel(service, TestScope(dispatcher))
            val updated =
                ConnectionDef(
                    id = "c1",
                    name = "Updated",
                    dialect = Dialect.MySql,
                    host = "localhost",
                    port = 3306,
                    database = "db",
                    username = "user",
                )

            var saveResult: Result<ConnectionDef>? = null
            viewModel.saveConnection(
                def = updated,
                password = null,
                editing = true,
                credentialMaterialChanged = false,
                onComplete = { saveResult = it },
            )
            advanceUntilIdle()
            assertEquals(Result.success(updated), saveResult)
            assertEquals(listOf(updated), service.updatedConnections)
            assertEquals(1, service.connectionListCalls)
            assertEquals("connection list failed", viewModel.error.value)
        }

    @Test
    fun saveConnectionRefreshesAndNotifiesChangesOnViewModelScope() =
        runTest(dispatcher) {
            val service = RecordingSafeDbService()
            val vmScope = TestScope(dispatcher)
            val viewModel = ConnectionsViewModel(service, vmScope)
            val gate = CompletableDeferred<Unit>()
            service.updateConnectionGate = gate

            val edited =
                ConnectionDef(
                    id = "c1",
                    name = "Updated",
                    dialect = Dialect.MySql,
                    host = "db.internal",
                    port = 3306,
                    database = "db",
                    username = "user",
                )

            val changedIds = mutableListOf<String>()
            var formMounted = true
            var formOutcome: Result<ConnectionDef>? = null
            viewModel.saveConnection(
                def = edited,
                password = "new-secret",
                editing = true,
                credentialMaterialChanged = true,
                onConnectionChanged = { changedIds += it },
                onComplete = { result -> if (formMounted) formOutcome = result },
            )
            runCurrent()
            // Simulate navigating away from ConnectionForm mid-save.
            formMounted = false
            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(listOf(edited), service.updatedConnections)
            assertEquals(listOf("c1"), changedIds)
            assertEquals(1, service.connectionListCalls)
            assertEquals(1, viewModel.connections.value.size)
            assertNull(formOutcome)

            // Non-credential edits do not invalidate schemas/active connection; create skips it
            // too.
            changedIds.clear()
            var createResult: Result<ConnectionDef>? = null
            val created = edited.copy(id = "c2", name = "Created")
            viewModel.saveConnection(
                def = created,
                password = "secret",
                editing = false,
                credentialMaterialChanged = true,
                onConnectionChanged = { changedIds += it },
                onComplete = { createResult = it },
            )
            advanceUntilIdle()

            assertEquals(listOf(created), service.createdConnections)
            assertTrue(changedIds.isEmpty())
            assertEquals(Result.success(created), createResult)
            assertEquals(2, service.connectionListCalls)

            var noCredChangeResult: Result<ConnectionDef>? = null
            viewModel.saveConnection(
                def = edited.copy(name = "Renamed"),
                password = null,
                editing = true,
                credentialMaterialChanged = false,
                onConnectionChanged = { changedIds += it },
                onComplete = { noCredChangeResult = it },
            )
            advanceUntilIdle()
            assertTrue(changedIds.isEmpty())
            assertTrue(noCredChangeResult?.isSuccess == true)
        }

    @Test
    fun saveConnectionSurfacesErrorsWithoutRefreshingOrInvalidating() =
        runTest(dispatcher) {
            val service = RecordingSafeDbService().apply { failConnectionSave = true }
            val viewModel = ConnectionsViewModel(service, TestScope(dispatcher))
            val def =
                ConnectionDef(
                    id = "c1",
                    name = "Broken",
                    dialect = Dialect.Postgres,
                    host = "localhost",
                    port = 5432,
                    database = "db",
                    username = "user",
                )

            val changedIds = mutableListOf<String>()
            var outcome: Result<ConnectionDef>? = null
            viewModel.saveConnection(
                def = def,
                password = "secret",
                editing = true,
                credentialMaterialChanged = true,
                onConnectionChanged = { changedIds += it },
                onComplete = { outcome = it },
            )
            advanceUntilIdle()

            assertTrue(outcome?.isFailure == true)
            assertEquals("connection save failed", outcome.exceptionOrNull()?.message)
            assertTrue(changedIds.isEmpty())
            assertEquals(0, service.connectionListCalls)
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
    fun schemaViewModelOverlappingLoadReportsFirstCallbackFalse() =
        runTest(dispatcher) {
            val service = RecordingSafeDbService()
            val gate = CompletableDeferred<Schema>()
            service.schemaResponses["c1"] = gate
            val viewModel = SchemaViewModel(service, TestScope(dispatcher))

            var first: Boolean? = null
            var second: Boolean? = null
            viewModel.load("c1") { first = it }
            runCurrent()
            viewModel.load("c1") { second = it }
            runCurrent()
            gate.complete(
                Schema(
                    tables =
                        listOf(
                            TableInfo("safedb_test", "customers", emptyList(), emptyList()),
                            TableInfo("safedb_test", "orders", emptyList(), emptyList()),
                            TableInfo("reporting", "events", emptyList(), emptyList()),
                        ),
                ),
            )
            advanceUntilIdle()

            assertEquals(false, first)
            assertEquals(true, second)
            assertEquals("c1", viewModel.loadedConnectionId)
        }

    @Test
    fun historyViewModelLoadsAndClears() =
        runTest(dispatcher) {
            val service = RecordingSafeDbService()
            val scope = TestScope(dispatcher)
            val viewModel = HistoryViewModel(service, scope)

            viewModel.refresh()
            advanceUntilIdle()
            assertEquals(2, viewModel.entries.value.size)

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
    fun settingsViewModelPersistsOnlyAnInRangeQueryTimeout() =
        runTest(dispatcher) {
            val service = RecordingSafeDbService()
            val scope = TestScope(dispatcher)
            val viewModel = SettingsViewModel(service, scope)
            viewModel.load()
            advanceUntilIdle()
            assertEquals(10, viewModel.settings.value.queryTimeoutSeconds)

            viewModel.setQueryTimeoutSeconds(0)
            viewModel.setQueryTimeoutSeconds(121)
            advanceUntilIdle()
            assertEquals(0, service.settingsSaveCount)
            assertEquals(10, viewModel.settings.value.queryTimeoutSeconds)

            viewModel.setQueryTimeoutSeconds(30)
            advanceUntilIdle()
            assertEquals(30, viewModel.settings.value.queryTimeoutSeconds)
            assertEquals(30, service.savedSettings?.queryTimeoutSeconds)
            assertEquals(1, service.settingsSaveCount)

            viewModel.setQueryTimeoutSeconds(30)
            advanceUntilIdle()
            assertEquals(30, viewModel.settings.value.queryTimeoutSeconds)
            assertEquals(1, service.settingsSaveCount)
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
                Schema(listOf(TableInfo("current", "events", emptyList(), emptyList()))),
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
            assertEquals(2, viewModel.entries.value.size)
            assertEquals("history clear failed", viewModel.error.value)

            service.failHistoryClear = false
            viewModel.clear { completed = true }
            advanceUntilIdle()
            assertTrue(completed)
            assertTrue(viewModel.entries.value.isEmpty())
            assertNull(viewModel.error.value)
        }

    @Test
    fun historyDeleteRemovesEntryAndSettlesAfterSuccess() =
        runTest(dispatcher) {
            val service = RecordingSafeDbService()
            val scope = TestScope(dispatcher)
            val viewModel = HistoryViewModel(service, scope)

            viewModel.refresh()
            advanceUntilIdle()

            var settled = false
            viewModel.delete("h1") { settled = true }
            advanceUntilIdle()

            assertTrue(settled)
            assertEquals(listOf("h2"), viewModel.entries.value.map { it.id })
            assertEquals(listOf("h1"), service.deletedHistoryIds)
            assertNull(viewModel.error.value)
        }

    @Test
    fun historyDeleteSettlesAfterFailureAndPreservesEntries() =
        runTest(dispatcher) {
            val service = RecordingSafeDbService().apply { failHistoryDelete = true }
            val scope = TestScope(dispatcher)
            val viewModel = HistoryViewModel(service, scope)

            viewModel.refresh()
            advanceUntilIdle()

            var settled = false
            viewModel.delete("h1") { settled = true }
            advanceUntilIdle()

            assertTrue(settled)
            assertEquals(listOf("h1", "h2"), viewModel.entries.value.map { it.id })
            assertEquals("history delete failed", viewModel.error.value)
            assertTrue(service.deletedHistoryIds.isEmpty())
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

    @Test
    fun settingsLoadDefaultSchemaOptionsReusesCachedSchemaAndFallsBackWhenAbsent() =
        runTest(dispatcher) {
            val service = RecordingSafeDbService()
            val cachedById = mutableMapOf<String, Schema>()
            cachedById["c1"] =
                Schema(
                    tables =
                        listOf(
                            TableInfo("zeta", "t1", emptyList(), emptyList()),
                            TableInfo("alpha", "t2", emptyList(), emptyList()),
                            TableInfo("alpha", "t3", emptyList(), emptyList()),
                        ),
                )
            cachedById["c-empty"] = Schema(tables = emptyList())
            val viewModel =
                SettingsViewModel(service, TestScope(dispatcher), cachedSchema = cachedById::get)
            viewModel.load()
            advanceUntilIdle()

            viewModel.loadDefaultSchemaOptions("c1")
            assertEquals(listOf("alpha", "zeta"), viewModel.defaultSchemaOptions.value)
            assertFalse(viewModel.defaultSchemaLoading.value)
            assertNull(viewModel.defaultSchemaError.value)
            assertEquals(0, service.schemaLoadCount)

            var saved = false
            viewModel.saveDefaultLocation("c1", "alpha") { saved = true }
            advanceUntilIdle()
            assertTrue(saved)

            viewModel.loadDefaultSchemaOptions("c-empty")
            assertTrue(viewModel.defaultSchemaOptions.value.isEmpty())
            assertEquals(
                "No schemas containing visible tables were found.",
                viewModel.defaultSchemaError.value,
            )
            assertEquals(0, service.schemaLoadCount)

            viewModel.loadDefaultSchemaOptions("c2")
            advanceUntilIdle()
            assertEquals(listOf("reporting", "safedb_test"), viewModel.defaultSchemaOptions.value)
            assertEquals(1, service.settingsSaveCount.coerceAtLeast(0))
            assertEquals(1, service.schemaLoadCount)
        }

    @Test
    fun settingsViewModelCoversClearDefaultForgetLastClearSchemaSelectionsAndErrors() =
        runTest(dispatcher) {
            val service = RecordingSafeDbService()
            val scope = TestScope(dispatcher)
            val viewModel = SettingsViewModel(service, scope)

            service.failSettingsGet = true
            viewModel.load()
            advanceUntilIdle()
            assertEquals("settings get failed", viewModel.loadError.value)

            service.failSettingsGet = false
            viewModel.load()
            advanceUntilIdle()
            assertNull(viewModel.loadError.value)

            // No-op clearDefaultLocation invokes onSuccess without persisting.
            var clearedNoOp = false
            viewModel.clearDefaultLocation { clearedNoOp = true }
            advanceUntilIdle()
            assertTrue(clearedNoOp)
            assertEquals(0, service.settingsSaveCount)

            // Mismatched or unloaded schema save sets validation error.
            viewModel.saveDefaultLocation("c1", "reporting")
            advanceUntilIdle()
            assertEquals(
                "Select a schema loaded from the chosen database.",
                viewModel.saveError.value,
            )

            viewModel.loadDefaultSchemaOptions("c1")
            advanceUntilIdle()
            viewModel.saveDefaultLocation("c1", "reporting")
            viewModel.rememberLastSchema("c1", "reporting")
            advanceUntilIdle()

            // clearSchemaSelectionsForConnection keeps defaultConnectionId while clearing schema &
            // history.
            viewModel.clearSchemaSelectionsForConnection("c1")
            advanceUntilIdle()
            assertEquals("c1", viewModel.settings.value.defaultConnectionId)
            assertNull(viewModel.settings.value.defaultSchema)
            assertTrue(viewModel.settings.value.lastSelectedSchemas.isEmpty())
            assertTrue(viewModel.defaultSchemaOptions.value.isEmpty())

            // forgetLastSchema removes remembered schema and clearDefaultLocation clears
            // defaultConnectionId.
            viewModel.rememberLastSchema("c1", "safedb_test")
            advanceUntilIdle()
            viewModel.forgetLastSchema("c1")
            advanceUntilIdle()
            assertTrue(viewModel.settings.value.lastSelectedSchemas.isEmpty())

            service.failSettingsSave = true
            var clearedAfterFail = false
            viewModel.clearDefaultLocation { clearedAfterFail = true }
            advanceUntilIdle()
            assertFalse(clearedAfterFail)
            assertEquals("c1", viewModel.settings.value.defaultConnectionId)

            service.failSettingsSave = false
            var clearedAfterSuccess = false
            viewModel.clearDefaultLocation { clearedAfterSuccess = true }
            advanceUntilIdle()
            assertTrue(clearedAfterSuccess)
            assertNull(viewModel.settings.value.defaultConnectionId)

            // clearDefaultIfConnection on unrelated id is a no-op calling onSuccess.
            var unrelatedCleared = false
            viewModel.clearDefaultIfConnection("other") { unrelatedCleared = true }
            advanceUntilIdle()
            assertTrue(unrelatedCleared)
        }

    @Test
    fun schemaViewModelHandlesFailuresStaleFailuresAndUnavailableWarnings() =
        runTest(dispatcher) {
            val service = RecordingSafeDbService()
            val first = CompletableDeferred<Schema>()
            val second = CompletableDeferred<Schema>()
            service.schemaResponses["c1"] = first
            service.schemaResponses["c2"] = second
            val reachable = mutableListOf<String>()
            val viewModel =
                SchemaViewModel(
                    service,
                    TestScope(dispatcher),
                    onConnectionReachable = { reachable += it },
                )

            var firstComplete: Boolean? = null
            var secondComplete: Boolean? = null
            viewModel.load("c1") { firstComplete = it }
            runCurrent()
            viewModel.load("c2") { secondComplete = it }
            runCurrent()

            // Newer request completes first, then older request fails; stale failure must not
            // clobber.
            second.complete(Schema(listOf(TableInfo("public", "orders", emptyList(), emptyList()))))
            runCurrent()
            first.completeExceptionally(IllegalStateException("stale failure"))
            advanceUntilIdle()

            assertEquals(true, secondComplete)
            assertEquals(false, firstComplete)
            assertEquals("c2", viewModel.loadedConnectionId)
            assertNull(viewModel.error)
            assertEquals(listOf("c2"), reachable)

            service.failSchemaLoad = true
            var failComplete: Boolean? = null
            viewModel.load("c3") { failComplete = it }
            advanceUntilIdle()
            assertEquals(false, failComplete)
            assertNull(viewModel.loadedConnectionId)
            assertEquals("schema load failed", viewModel.error)

            service.failSchemaLoad = false
            service.schemaResponses.clear()
            viewModel.load(
                "c1",
                SchemaSelectionIntent("missing", SchemaSelectionSource.StartupDefault),
            )
            advanceUntilIdle()
            assertEquals(
                "Default schema \"missing\" is unavailable. Select a schema or update Settings.",
                viewModel.preferredSchemaWarning,
            )

            viewModel.load(
                "c1",
                SchemaSelectionIntent("missing", SchemaSelectionSource.User),
            )
            advanceUntilIdle()
            assertEquals(
                "Schema \"missing\" is unavailable. Select another schema.",
                viewModel.preferredSchemaWarning,
            )
        }
}

private class RecordingSafeDbService : FakeSafeDbServiceSupport() {
    val deletedIds = mutableListOf<String>()
    val deletedSavedIds = mutableListOf<String>()
    var historyCleared = false
    val deletedHistoryIds = mutableListOf<String>()
    var failHistoryDelete = false
    var savedSettings: Settings? = null
    var failHistoryClear = false
    var failSavedMutation = false
    var failSettingsGet = false
    var failSettingsSave = false
    var failSchemaLoad = false
    var failConnectionList = false
    var failConnectionSave = false
    var connectionListCalls = 0
    val createdConnections = mutableListOf<ConnectionDef>()
    val updatedConnections = mutableListOf<ConnectionDef>()
    var updateConnectionGate: CompletableDeferred<Unit>? = null
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

    override suspend fun createConnection(def: ConnectionDef, password: String): ConnectionDef {
        if (failConnectionSave) error("connection save failed")
        createdConnections += def
        return def
    }

    override suspend fun updateConnection(def: ConnectionDef, password: String?) {
        if (failConnectionSave) error("connection save failed")
        updateConnectionGate?.await()
        updatedConnections += def
    }

    override suspend fun listConnections(): List<ConnectionDef> {
        connectionListCalls += 1
        if (failConnectionList) error("connection list failed")
        return if (deletedIds.isEmpty()) listOf(connection) else emptyList()
    }

    override suspend fun deleteConnection(id: String) {
        deletedIds.add(id)
    }

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
                ),
        )
    }

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
                    ),
                    HistoryEntry(
                        "h2",
                        "c1",
                        "Local MySQL",
                        emptyQuerySpec(),
                        3,
                        emptyList(),
                        timestamp = "2",
                    ),
                )
                .filter { it.id !in deletedHistoryIds }

    override suspend fun deleteHistoryEntry(id: String) {
        if (failHistoryDelete) error("history delete failed")
        deletedHistoryIds.add(id)
    }

    override suspend fun clearHistory() {
        if (failHistoryClear) error("history clear failed")
        historyCleared = true
    }

    override suspend fun getSettings(): Settings {
        if (failSettingsGet) error("settings get failed")
        return Settings.default()
    }

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
