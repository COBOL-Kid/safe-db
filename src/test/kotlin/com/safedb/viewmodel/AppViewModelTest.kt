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
import com.safedb.query.QueryError
import com.safedb.query.QueryRiskAssessment
import com.safedb.query.QueryRiskDecision
import com.safedb.query.QueryRiskSeverity
import com.safedb.query.RiskDecisionReason
import com.safedb.query.RiskGateState
import com.safedb.explore.ExploreConfig
import com.safedb.explore.ExploreMode
import com.safedb.explore.ExploreRecipe
import com.safedb.model.QueryRiskGate
import com.safedb.service.SafeDbService
import com.safedb.service.QueryFailureException
import com.safedb.service.QueryRunRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertNotNull
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
        val viewModel = AppViewModel(service, dispatcher)
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
        val viewModel = AppViewModel(service, dispatcher)
        advanceUntilIdle()

        viewModel.lockCredentials()
        advanceUntilIdle()

        assertTrue(service.locked)
    }

    @Test
    fun openExploreCreatesSessionAndCloseClearsIt() = runTest(dispatcher) {
        val service = FakeSafeDbService()
        val viewModel = AppViewModel(service, dispatcher)
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
        val viewModel = AppViewModel(service, dispatcher)
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

    @Test
    fun queryViewModelRejectsDuplicateRunsBeforeCoroutineStarts() = runTest(dispatcher) {
        val service = FakeSafeDbService()
        val scope = TestScope(dispatcher)
        val query = QueryViewModel(service, scope)
        query.addTable(sampleTable())

        query.run("c1")
        query.run("c1")
        scope.advanceUntilIdle()

        assertEquals(1, service.queryAttempts)
        assertFalse(query.running)
    }

    @Test
    fun queryViewModelIgnoresCompletionAfterClear() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val service = FakeSafeDbService(queryGate = gate, queryStarted = started)
        val scope = TestScope(dispatcher)
        val query = QueryViewModel(service, scope)
        query.addTable(sampleTable())

        query.run("c1")
        assertTrue(query.running)
        scope.runCurrent()
        assertTrue(started.isCompleted)

        query.clear()
        gate.complete(Unit)
        scope.advanceUntilIdle()

        assertFalse(query.running)
        assertNull(query.results)
        assertNull(query.error)
        assertEquals(0, query.tableCount)
    }

    @Test
    fun queryViewModelDiscardsAssessmentAndResultAfterDraftFingerprintChanges() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val service = FakeSafeDbService(queryGate = gate, queryStarted = started)
        val scope = TestScope(dispatcher)
        val query = QueryViewModel(service, scope)
        query.addTable(sampleTable())

        query.run("c1")
        scope.runCurrent()
        assertTrue(started.isCompleted)

        query.setLimit(50)
        gate.complete(Unit)
        scope.advanceUntilIdle()

        assertFalse(query.running)
        assertNull(query.results)
        assertNull(query.riskEvaluation)
        assertEquals(50, query.limit)
    }

    @Test
    fun queryBackedRecipeRestoresRunsAndOpensMatchingExploreSession() = runTest(dispatcher) {
        val service = FakeSafeDbService()
        val viewModel = AppViewModel(service, dispatcher)
        advanceUntilIdle()
        val connection = testConnection()
        val recipe = ExploreRecipe(
            id = "r1", name = "Users pivot", createdAt = "1", updatedAt = "1",
            defaultMode = ExploreMode.Pivot, pivot = ExploreConfig(), querySpec = sampleSpec(),
        )

        viewModel.runRecipe(connection, recipe)
        advanceUntilIdle()

        val pending = viewModel.pendingRecipeRun.value
        assertEquals("r1", pending?.recipe?.id)
        val sample = assertNotNull(viewModel.query.currentSample(connection.id))
        viewModel.completePendingRecipeRun(connection, sample.result, sample.spec)

        assertNull(viewModel.pendingRecipeRun.value)
        assertEquals("r1", viewModel.explore.value?.appliedRecipeId)
    }

    @Test
    fun queryBackedRecipeRiskBlockTerminatesPendingRun() = runTest(dispatcher) {
        val service = FakeSafeDbService(riskGateFirstRun = true)
        val viewModel = AppViewModel(service, dispatcher)
        advanceUntilIdle()
        val connection = testConnection()
        val recipe = ExploreRecipe(
            id = "r5", name = "Risk blocked", createdAt = "1", updatedAt = "1",
            defaultMode = ExploreMode.Pivot, pivot = ExploreConfig(), querySpec = sampleSpec(),
        )

        viewModel.runRecipe(connection, recipe)
        advanceUntilIdle()

        assertTrue(viewModel.query.pendingRiskGate)
        assertNull(viewModel.pendingRecipeRun.value)
        assertNotNull(viewModel.query.error)
        assertNull(viewModel.explore.value)
    }

    @Test
    fun queryViewModelClearsSettledRiskGateFailureWhenExecutableQueryChanges() = runTest(dispatcher) {
        val service = FakeSafeDbService(riskGateFirstRun = true)
        val scope = TestScope(dispatcher)
        val query = QueryViewModel(service, scope)
        query.addTable(sampleTable())

        query.run("c1")
        scope.advanceUntilIdle()

        assertTrue(query.pendingRiskGate)
        assertNotNull(query.error)

        query.resizeTable("t0", width = 420f, height = 360f)
        assertTrue(query.pendingRiskGate)
        assertNotNull(query.error)

        query.moveTable("t0", x = 80f, y = 40f)
        assertTrue(query.pendingRiskGate)
        assertNotNull(query.error)

        query.setLimit(50)

        assertFalse(query.pendingRiskGate)
        assertNull(query.error)
        assertTrue(query.canRun)
    }

    @Test
    fun shouldCancelPendingRecipeOnlyForHardQueryFailures() {
        assertTrue(shouldCancelPendingRecipeOnQuerySettle(running = false, hasError = true))
        assertFalse(shouldCancelPendingRecipeOnQuerySettle(running = false, hasError = false))
        assertFalse(shouldCancelPendingRecipeOnQuerySettle(running = true, hasError = true))
    }

    @Test
    fun queryBackedRecipeIsCancelledWhenActiveConnectionChanges() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val service = FakeSafeDbService(queryGate = gate)
        val viewModel = AppViewModel(service, dispatcher)
        advanceUntilIdle()
        val connection = testConnection()
        val recipe = ExploreRecipe(
            id = "r3", name = "Switching", createdAt = "1", updatedAt = "1",
            defaultMode = ExploreMode.Pivot, pivot = ExploreConfig(), querySpec = sampleSpec(),
        )

        viewModel.runRecipe(connection, recipe)
        advanceUntilIdle()

        assertFalse(viewModel.cancelPendingRecipeRunIfConnectionChanged(connection.id))
        assertNotNull(viewModel.pendingRecipeRun.value)
        assertTrue(viewModel.cancelPendingRecipeRunIfConnectionChanged("c2"))
        assertNull(viewModel.pendingRecipeRun.value)
        gate.complete(Unit)
    }


    @Test
    fun queryBackedRecipeDoesNotRemainPendingWhenHydratedQueryCannotRun() = runTest(dispatcher) {
        val service = FakeSafeDbService(schemaTables = emptyList())
        val viewModel = AppViewModel(service, dispatcher)
        advanceUntilIdle()
        val connection = testConnection()
        val recipe = ExploreRecipe(
            id = "r4", name = "Missing tables", createdAt = "1", updatedAt = "1",
            defaultMode = ExploreMode.Pivot, pivot = ExploreConfig(), querySpec = sampleSpec(),
        )

        viewModel.runRecipe(connection, recipe)
        advanceUntilIdle()

        assertNull(viewModel.pendingRecipeRun.value)
        assertFalse(viewModel.query.canRun)
        assertNull(viewModel.query.currentSample(connection.id))
        assertNull(viewModel.explore.value)
        assertEquals(0, service.queryAttempts)
    }

    private fun testConnection() = ConnectionDef(
        id = "c1", name = "Local", dialect = Dialect.MySql, host = "localhost", port = 3306,
        database = "test", username = "reader",
    )
}

private class FakeSafeDbService(
    private val riskGateFirstRun: Boolean = false,
    private val queryGate: CompletableDeferred<Unit>? = null,
    private val queryStarted: CompletableDeferred<Unit>? = null,
    private val schemaTables: List<TableInfo> = listOf(sampleTable()),
) : SafeDbService {
    var locked = false
    var queryAttempts = 0
        private set

    override suspend fun testConnection(def: ConnectionDef, password: String): String = "ok"
    override suspend fun createConnection(def: ConnectionDef, password: String): ConnectionDef = def
    override suspend fun updateConnection(def: ConnectionDef, password: String?) = Unit
    override suspend fun listConnections(): List<ConnectionDef> = emptyList()
    override suspend fun deleteConnection(id: String) = Unit

    override suspend fun lockCredentials() {
        locked = true
    }

    override suspend fun getSchema(connectionId: String): Schema = Schema(schemaTables)

    override suspend fun runQuery(request: QueryRunRequest): com.safedb.service.QueryRunResult {
        queryAttempts += 1
        queryStarted?.complete(Unit)
        queryGate?.await()
        if (riskGateFirstRun && queryAttempts == 1) {
            val assessment = QueryRiskAssessment(
                scoreVersion = 2,
                queryFingerprint = "blocked",
                score = 6,
                severity = QueryRiskSeverity.High,
                categoryScores = emptyMap(),
                signals = emptyList(),
                uncertainties = emptyList(),
            )
            val decision = QueryRiskDecision(
                queryFingerprint = "blocked",
                state = RiskGateState.Blocked,
                effectiveGate = QueryRiskGate.Standard,
                blockingBand = QueryRiskSeverity.High,
                reasons = listOf(RiskDecisionReason("risk_gate", "The query risk gate blocks this query.")),
            )
            throw QueryFailureException(
                QueryError.RiskGate(
                    evaluation = com.safedb.query.QueryRiskEvaluation(
                        staticAssessment = assessment,
                        finalAssessment = assessment,
                        planStatus = com.safedb.query.QueryPlanStatus.Available,
                        decision = decision,
                    ),
                    historySpec = request.spec,
                ),
            )
        }
        val result = QueryResult(
            columns = listOf(ResultColumn("name", "varchar")),
            rows = emptyList(),
            rowCount = 0,
            truncated = false,
            warnings = emptyList(),
        )
        val assessment = QueryRiskAssessment(2, "allowed", 0, QueryRiskSeverity.Minimal, emptyMap(), emptyList(), emptyList())
        return com.safedb.service.QueryRunResult(
            result,
            com.safedb.query.QueryRiskEvaluation(
                staticAssessment = assessment,
                finalAssessment = assessment,
                planStatus = com.safedb.query.QueryPlanStatus.Available,
                decision = QueryRiskDecision("allowed", RiskGateState.Allowed, QueryRiskGate.Standard, QueryRiskSeverity.High, emptyList()),
            ),
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
