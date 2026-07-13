package com.safedb.viewmodel

import com.safedb.explore.exploreSpecHash
import com.safedb.model.ColumnInfo
import com.safedb.model.ConnectionDef
import com.safedb.model.Dialect
import com.safedb.model.FilterGroup
import com.safedb.model.HistoryEntry
import com.safedb.model.QueryResult
import com.safedb.model.QuerySpec
import com.safedb.model.ResultColumn
import com.safedb.model.ResultCell
import com.safedb.model.SavedQuery
import com.safedb.model.Schema
import com.safedb.model.Settings
import com.safedb.model.TableInfo
import com.safedb.model.TableRef
import com.safedb.query.COST_GUARD_PREFIX
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelTest {
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
    fun restoreQueryForConnectionLoadsSchemaAndHydratesSpec() = runTest(dispatcher) {
        val service = FakeSafeDbService()
        val viewModel = AppViewModel(service)
        advanceUntilIdle()

        var completed: Boolean? = null
        viewModel.restoreQueryForConnection("c1", sampleSpec()) { completed = it }
        advanceUntilIdle()

        assertEquals(true, completed)
        assertEquals("c1", viewModel.schema.loadedConnectionId)
        assertEquals(1, viewModel.query.tableCount)
        assertEquals("users", viewModel.query.spec.tables.single().name)
        assertEquals(25, viewModel.query.limit)
        assertNull(viewModel.query.hydrationWarning)
    }

    @Test
    fun lockCredentialsDelegatesThroughService() = runTest(dispatcher) {
        val service = FakeSafeDbService()
        val viewModel = AppViewModel(service)
        advanceUntilIdle()

        viewModel.lockCredentials()
        advanceUntilIdle()

        assertTrue(service.locked)
    }

    @Test
    fun openExploreCreatesSessionAndCloseClearsIt() = runTest(dispatcher) {
        val service = FakeSafeDbService()
        val viewModel = AppViewModel(service)
        advanceUntilIdle()
        val connection = ConnectionDef(
            id = "c1",
            name = "Local",
            dialect = Dialect.MySql,
            host = "localhost",
            port = 3306,
            database = "safedb",
            username = "root",
        )
        val result = QueryResult(
            columns = listOf(ResultColumn("t0__status", "varchar")),
            rows = listOf(listOf(ResultCell.text("pending"))),
            rowCount = 1,
            truncated = false,
            warnings = emptyList(),
        )

        viewModel.openExplore(connection, sampleSpec(), result)

        assertEquals("Local", viewModel.explore.value?.session?.connectionLabel)
        viewModel.closeExplore()
        assertNull(viewModel.explore.value)
    }

    @Test
    fun refreshExploreSampleReplacesSessionSampleAndSpecHash() = runTest(dispatcher) {
        val service = FakeSafeDbService()
        val viewModel = AppViewModel(service)
        advanceUntilIdle()
        val connection = ConnectionDef(
            id = "c1",
            name = "Local",
            dialect = Dialect.MySql,
            host = "localhost",
            port = 3306,
            database = "safedb",
            username = "root",
        )
        val sampleA = QueryResult(
            columns = listOf(ResultColumn("t0__status", "varchar")),
            rows = listOf(listOf(ResultCell.text("pending"))),
            rowCount = 1,
            truncated = false,
            warnings = emptyList(),
        )
        val sampleB = QueryResult(
            columns = listOf(ResultColumn("t0__status", "varchar")),
            rows = listOf(
                listOf(ResultCell.text("pending")),
                listOf(ResultCell.text("shipped")),
            ),
            rowCount = 2,
            truncated = false,
            warnings = emptyList(),
        )
        val specA = sampleSpec()
        val specB = sampleSpec().copy(limit = 50)

        viewModel.openExplore(connection, specA, sampleA)
        viewModel.refreshExploreSample(connection, specB, sampleB)

        val session = viewModel.explore.value?.session
        assertEquals(2, session?.sample?.rowCount)
        assertEquals(sampleB.rows, session?.sample?.rows)
        assertEquals(exploreSpecHash(specB), session?.baseSpecHash)
        assertEquals(50, session?.builderLimit)

        viewModel.refreshExploreSample(connection.copy(id = "c2", name = "Other"), specA, sampleA)
        assertEquals("c1", viewModel.explore.value?.session?.connectionId)
        assertEquals("Local", viewModel.explore.value?.session?.connectionLabel)
        assertEquals(sampleB.rows, viewModel.explore.value?.session?.sample?.rows)
    }

    @Test
    fun querySampleIsBoundToItsExecutedConnectionAndSpec() = runTest(dispatcher) {
        val scope = TestScope(dispatcher)
        val query = QueryViewModel(FakeSafeDbService(), scope)
        query.addTable(sampleTable())

        query.run("c1")
        scope.advanceUntilIdle()

        assertEquals("c1", query.currentSample("c1")?.connectionId)
        assertNull(query.currentSample("c2"))

        query.toggleColumn("t0", "name")

        assertNull(query.currentSample("c1"))
    }

    @Test
    fun queryViewModelMutedCostGuardRetriesWithForceForSessionOnly() = runTest(dispatcher) {
        val service = FakeSafeDbService(costGuardFirstRun = true)
        val scope = TestScope(dispatcher)
        val query = QueryViewModel(service, scope)
        query.addTable(sampleTable())
        query.updateWarningPopupsDisabled(true)

        query.run("c1")
        scope.advanceUntilIdle()

        assertEquals(listOf(false, true), service.forceCalls)
        assertFalse(query.pendingCostGuard)
        assertNull(query.error)
        assertEquals(0, query.results?.rowCount)

        query.clear()
        assertTrue(query.warningPopupsDisabled)
    }

    @Test
    fun queryViewModelKeepsCanvasResizeOutOfQuerySpec() = runTest(dispatcher) {
        val query = QueryViewModel(FakeSafeDbService(), TestScope(dispatcher))
        query.addTable(sampleTable())

        query.resizeTable("t0", width = 420f, height = 360f)

        assertEquals(420f, query.canvasTables.single().width)
        assertEquals(360f, query.canvasTables.single().height)
        assertEquals(
            listOf(TableRef(schema = "public", name = "users", alias = "t0")),
            query.spec.tables,
        )
    }
}

private class FakeSafeDbService(
    private val costGuardFirstRun: Boolean = false,
) : SafeDbService {
    var locked = false
    val forceCalls = mutableListOf<Boolean>()

    override suspend fun testConnection(def: ConnectionDef, password: String): String = "ok"
    override suspend fun saveConnection(def: ConnectionDef, password: String?) = Unit
    override suspend fun createConnection(def: ConnectionDef, password: String): ConnectionDef = def
    override suspend fun updateConnection(def: ConnectionDef, password: String?) = Unit
    override suspend fun listConnections(): List<ConnectionDef> = emptyList()
    override suspend fun deleteConnection(id: String) = Unit

    override suspend fun lockCredentials() {
        locked = true
    }

    override suspend fun getSchema(connectionId: String): Schema = Schema(listOf(sampleTable()))

    override suspend fun runQuery(connectionId: String, spec: QuerySpec, force: Boolean): QueryResult {
        forceCalls.add(force)
        if (costGuardFirstRun && !force) {
            throw IllegalArgumentException("${COST_GUARD_PREFIX}EXPLAIN failed. Confirm to run this query anyway.")
        }
        return QueryResult(
            columns = listOf(ResultColumn("name", "varchar")),
            rows = emptyList(),
            rowCount = 0,
            truncated = false,
            warnings = emptyList(),
        )
    }

    override suspend fun listSavedQueries(): List<SavedQuery> = emptyList()
    override suspend fun saveSavedQuery(query: SavedQuery) = Unit
    override suspend fun deleteSavedQuery(id: String) = Unit
    override suspend fun listHistory(): List<HistoryEntry> = emptyList()
    override suspend fun clearHistory() = Unit
    override suspend fun getSettings(): Settings = Settings.default()
    override suspend fun saveSettings(settings: Settings) = Unit
}

private fun sampleTable() = TableInfo(
    schema = "public",
    name = "users",
    columns = listOf(
        ColumnInfo("id", "int", nullable = false, isIndexed = true),
        ColumnInfo("name", "varchar", nullable = false, isIndexed = false),
    ),
    indexes = emptyList(),
)

private fun sampleSpec() = QuerySpec(
    tables = listOf(TableRef("public", "users", "saved_t0")),
    columns = emptyList(),
    joins = emptyList(),
    filters = FilterGroup.empty(),
    limit = 25,
)
