package com.safedb.viewmodel

import com.safedb.explore.ExploreConfig
import com.safedb.explore.ExploreMode
import com.safedb.explore.ExploreRecipe
import com.safedb.explore.exploreSpecHash
import com.safedb.model.ConnectionDef
import com.safedb.model.Dialect
import com.safedb.model.QueryResult
import com.safedb.model.QueryRiskGate
import com.safedb.model.ResultCell
import com.safedb.model.ResultColumn
import com.safedb.model.SavedQuery
import com.safedb.model.TableInfo
import com.safedb.model.TableRef
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

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
    fun restoreQueryForConnectionLoadsSchemaAndHydratesSpec() =
        runTest(dispatcher) {
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
            assertEquals("public", viewModel.schema.selectedSchema)
            assertEquals(0, service.settingsSaveCount)
        }

    @Test
    fun restoreCrossSchemaQuerySelectsFirstSchemaAndHydratesAllTables() =
        runTest(dispatcher) {
            val service =
                FakeSafeDbService(
                    schemaTables =
                        listOf(
                            sampleTable(),
                            TableInfo("reporting", "orders", emptyList(), emptyList()),
                        ),
                )
            val viewModel = AppViewModel(service, dispatcher)
            advanceUntilIdle()
            val spec =
                sampleSpec()
                    .copy(
                        tables =
                            listOf(
                                TableRef("reporting", "orders", "t0"),
                                TableRef("public", "users", "t1"),
                            ),
                    )

            viewModel.restoreQueryForConnection("c1", spec)
            advanceUntilIdle()

            assertEquals("reporting", viewModel.schema.selectedSchema)
            assertEquals(listOf("orders", "users"), viewModel.query.spec.tables.map { it.name })
            assertEquals(0, service.settingsSaveCount)
        }

    @Test
    fun lockCredentialsDelegatesThroughService() =
        runTest(dispatcher) {
            val service = FakeSafeDbService()
            val viewModel = AppViewModel(service, dispatcher)
            advanceUntilIdle()

            viewModel.lockCredentials()
            advanceUntilIdle()

            assertTrue(service.locked)
        }

    @Test
    fun openExploreCreatesSessionAndCloseClearsIt() =
        runTest(dispatcher) {
            val service = FakeSafeDbService()
            val viewModel = AppViewModel(service, dispatcher)
            advanceUntilIdle()
            val connection =
                ConnectionDef(
                    id = "c1",
                    name = "Local",
                    dialect = Dialect.MySql,
                    host = "localhost",
                    port = 3306,
                    database = "safedb",
                    username = "root",
                )
            val result =
                QueryResult(
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
    fun openExploreRecordsTheOriginScreen() =
        runTest(dispatcher) {
            val viewModel = AppViewModel(FakeSafeDbService(), dispatcher)
            try {
                advanceUntilIdle()
                val connection = testConnection()
                val result =
                    QueryResult(
                        columns = listOf(ResultColumn("t0__status", "varchar")),
                        rows = listOf(listOf(ResultCell.text("pending"))),
                        rowCount = 1,
                        truncated = false,
                        warnings = emptyList(),
                    )

                viewModel.openExplore(connection, sampleSpec(), result)
                assertEquals(ExploreOrigin.Builder, viewModel.exploreOrigin.value)

                viewModel.openExplore(connection, sampleSpec(), result, ExploreOrigin.Sql)
                assertEquals(ExploreOrigin.Sql, viewModel.exploreOrigin.value)
            } finally {
                viewModel.close()
            }
        }

    @Test
    fun refreshExploreSampleReplacesSessionSampleAndSpecHash() =
        runTest(dispatcher) {
            val service = FakeSafeDbService()
            val viewModel = AppViewModel(service, dispatcher)
            advanceUntilIdle()
            val connection =
                ConnectionDef(
                    id = "c1",
                    name = "Local",
                    dialect = Dialect.MySql,
                    host = "localhost",
                    port = 3306,
                    database = "safedb",
                    username = "root",
                )
            val sampleA =
                QueryResult(
                    columns = listOf(ResultColumn("t0__status", "varchar")),
                    rows = listOf(listOf(ResultCell.text("pending"))),
                    rowCount = 1,
                    truncated = false,
                    warnings = emptyList(),
                )
            val sampleB =
                QueryResult(
                    columns = listOf(ResultColumn("t0__status", "varchar")),
                    rows =
                        listOf(
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

            viewModel.refreshExploreSample(
                connection.copy(id = "c2", name = "Other"),
                specA,
                sampleA,
            )
            assertEquals("c1", viewModel.explore.value?.session?.connectionId)
            assertEquals("Local", viewModel.explore.value?.session?.connectionLabel)
            assertEquals(sampleB.rows, viewModel.explore.value?.session?.sample?.rows)
        }

    @Test
    fun appLoadingWaitsForSettingsAndConnectionsBeforeBecomingReady() =
        runTest(dispatcher) {
            val settingsGate = CompletableDeferred<Unit>()
            val connectionsGate = CompletableDeferred<Unit>()
            val viewModel =
                AppViewModel(
                    FakeSafeDbService(
                        settingsLoadGate = settingsGate,
                        connectionsLoadGate = connectionsGate,
                    ),
                    dispatcher,
                )
            runCurrent()

            assertTrue(viewModel.initialLoading.value)

            settingsGate.complete(Unit)
            runCurrent()

            assertTrue(viewModel.initialLoading.value)

            connectionsGate.complete(Unit)
            advanceUntilIdle()

            assertFalse(viewModel.initialLoading.value)
        }

    @Test
    fun queryBackedRecipeRestoresRunsAndOpensMatchingExploreSession() =
        runTest(dispatcher) {
            val service = FakeSafeDbService()
            val viewModel = AppViewModel(service, dispatcher)
            try {
                advanceUntilIdle()
                val connection = testConnection()
                val recipe =
                    ExploreRecipe(
                        id = "r1",
                        name = "Users pivot",
                        createdAt = "1",
                        updatedAt = "1",
                        defaultMode = ExploreMode.Pivot,
                        pivot = ExploreConfig(),
                        querySpec = sampleSpec(),
                    )

                viewModel.runRecipe(connection, recipe)
                advanceUntilIdle()

                val pending = viewModel.pendingRecipeRun.value
                assertEquals("r1", pending?.recipe?.id)
                assertNull(viewModel.recipeApplyNotice.value)
                assertNotNull(viewModel.query.currentSample(connection.id))
                viewModel.onQuerySettled(connection.id, listOf(connection))

                assertNull(viewModel.pendingRecipeRun.value)
                assertEquals("r1", viewModel.explore.value?.appliedRecipeId)
            } finally {
                viewModel.close()
            }
        }

    @Test
    fun queryBackedRecipeRemainsPendingAcrossConfirmation() =
        runTest(dispatcher) {
            val service = FakeSafeDbService(confirmationRequired = true)
            val viewModel = AppViewModel(service, dispatcher)
            try {
                advanceUntilIdle()
                viewModel.query.onQueryRiskGateChanged(QueryRiskGate.Standard)
                val connection = testConnection()
                val recipe =
                    ExploreRecipe(
                        id = "r-confirm",
                        name = "Confirmed users",
                        createdAt = "1",
                        updatedAt = "1",
                        defaultMode = ExploreMode.Pivot,
                        pivot = ExploreConfig(),
                        querySpec = sampleSpec(),
                    )

                viewModel.runRecipe(connection, recipe)
                advanceUntilIdle()

                assertNotNull(viewModel.query.pendingConfirmation)
                assertEquals("r-confirm", viewModel.pendingRecipeRun.value?.recipe?.id)

                viewModel.query.onQueryRiskGateChanged(QueryRiskGate.Flexible)
                assertNotNull(viewModel.query.pendingConfirmation)
                assertEquals("r-confirm", viewModel.pendingRecipeRun.value?.recipe?.id)
                viewModel.query.onQueryRiskGateChanged(QueryRiskGate.Standard)

                viewModel.query.confirmPendingExecution("c1")
                advanceUntilIdle()

                assertEquals("r-confirm", viewModel.pendingRecipeRun.value?.recipe?.id)
                assertNotNull(viewModel.query.currentSample(connection.id))
                viewModel.onQuerySettled(connection.id, listOf(connection))
                assertNull(viewModel.pendingRecipeRun.value)
                assertEquals("r-confirm", viewModel.explore.value?.appliedRecipeId)
            } finally {
                viewModel.close()
            }
        }

    @Test
    fun queryBackedRecipeRiskBlockTerminatesPendingRun() =
        runTest(dispatcher) {
            val service = FakeSafeDbService(riskGateFirstRun = true)
            val viewModel = AppViewModel(service, dispatcher)
            advanceUntilIdle()
            val connection = testConnection()
            val recipe =
                ExploreRecipe(
                    id = "r5",
                    name = "Risk blocked",
                    createdAt = "1",
                    updatedAt = "1",
                    defaultMode = ExploreMode.Pivot,
                    pivot = ExploreConfig(),
                    querySpec = sampleSpec(),
                )

            viewModel.runRecipe(connection, recipe)
            advanceUntilIdle()

            assertTrue(viewModel.query.pendingRiskGate)
            assertNull(viewModel.pendingRecipeRun.value)
            assertNotNull(viewModel.query.error)
            assertNull(viewModel.explore.value)
            assertEquals(listOf("h1"), viewModel.history.entries.value.map { it.id })
            assertNotNull(viewModel.history.entries.value.single().error)

            viewModel.query.onActiveConnectionChanged("c2")

            assertFalse(viewModel.query.pendingRiskGate)
            assertNull(viewModel.query.error)
            assertNull(viewModel.query.riskEvaluation)
            assertTrue(viewModel.query.canRun)
        }

    @Test
    fun queryBackedRecipeIsCancelledWhenTheBuilderQueryNoLongerMatchesTheRecipe() =
        runTest(dispatcher) {
            val service = FakeSafeDbService()
            val viewModel = AppViewModel(service, dispatcher)
            try {
                advanceUntilIdle()
                val connection = testConnection()
                val recipe =
                    ExploreRecipe(
                        id = "r6",
                        name = "Drifted spec",
                        createdAt = "1",
                        updatedAt = "1",
                        defaultMode = ExploreMode.Pivot,
                        pivot = ExploreConfig(),
                        querySpec = sampleSpec(),
                    )

                viewModel.runRecipe(connection, recipe)
                advanceUntilIdle()
                assertNotNull(viewModel.pendingRecipeRun.value)

                viewModel.query.setLimit(50)
                viewModel.onQuerySettled(connection.id, listOf(connection))

                assertNull(viewModel.pendingRecipeRun.value)
                assertNull(viewModel.explore.value)
            } finally {
                viewModel.close()
            }
        }

    @Test
    fun queryBackedRecipeIsCancelledWhenActiveConnectionChanges() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<Unit>()
            val service = FakeSafeDbService(queryGate = gate)
            val viewModel = AppViewModel(service, dispatcher)
            advanceUntilIdle()
            val connection = testConnection()
            val recipe =
                ExploreRecipe(
                    id = "r3",
                    name = "Switching",
                    createdAt = "1",
                    updatedAt = "1",
                    defaultMode = ExploreMode.Pivot,
                    pivot = ExploreConfig(),
                    querySpec = sampleSpec(),
                )

            viewModel.runRecipe(connection, recipe)
            advanceUntilIdle()

            viewModel.onQuerySettled(connection.id, listOf(connection))
            assertNotNull(viewModel.pendingRecipeRun.value)
            viewModel.onQuerySettled("c2", listOf(connection))
            assertNull(viewModel.pendingRecipeRun.value)
            gate.complete(Unit)
        }

    @Test
    fun queryBackedRecipeDoesNotRemainPendingWhenHydratedQueryCannotRun() =
        runTest(dispatcher) {
            val service = FakeSafeDbService(schemaTables = emptyList())
            val viewModel = AppViewModel(service, dispatcher)
            advanceUntilIdle()
            val connection = testConnection()
            val recipe =
                ExploreRecipe(
                    id = "r4",
                    name = "Missing tables",
                    createdAt = "1",
                    updatedAt = "1",
                    defaultMode = ExploreMode.Pivot,
                    pivot = ExploreConfig(),
                    querySpec = sampleSpec(),
                )

            viewModel.runRecipe(connection, recipe)
            advanceUntilIdle()

            assertNull(viewModel.pendingRecipeRun.value)
            assertNull(viewModel.recipeApplyNotice.value)
            assertFalse(viewModel.query.canRun)
            assertNull(viewModel.query.currentSample(connection.id))
            assertNull(viewModel.explore.value)
            assertEquals(0, service.queryAttempts)
        }

    @Test
    fun restoreReusesLoadedSchemaAndRecipeRunDoesNotReload() =
        runTest(dispatcher) {
            val service = FakeSafeDbService()
            val viewModel = AppViewModel(service, dispatcher)
            try {
                advanceUntilIdle()
                viewModel.restoreQueryForConnection("c1", sampleSpec())
                advanceUntilIdle()
                assertEquals(1, service.schemaLoadCount)
                assertEquals(1, viewModel.query.tableCount)

                val recipe =
                    ExploreRecipe(
                        id = "r-reuse",
                        name = "Reuse schema",
                        createdAt = "1",
                        updatedAt = "1",
                        defaultMode = ExploreMode.Pivot,
                        pivot = ExploreConfig(),
                        querySpec = sampleSpec(),
                    )
                viewModel.runRecipe(testConnection(), recipe)
                advanceUntilIdle()

                assertEquals(1, service.schemaLoadCount)
                assertEquals(1, service.queryAttempts)
                assertEquals("r-reuse", viewModel.pendingRecipeRun.value?.recipe?.id)
            } finally {
                viewModel.close()
            }
        }

    @Test
    fun recipeRestoreInvokesOnRestoredWhenQueryCannotRun() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<Unit>()
            val started = CompletableDeferred<Unit>()
            val service = FakeSafeDbService(queryGate = gate, queryStarted = started)
            val viewModel = AppViewModel(service, dispatcher)
            try {
                advanceUntilIdle()
                viewModel.restoreQueryForConnection("c1", sampleSpec())
                advanceUntilIdle()

                viewModel.query.run("c1")
                runCurrent()
                assertTrue(started.isCompleted)
                assertTrue(viewModel.query.running)
                assertFalse(viewModel.query.canRun)

                var restored = false
                val recipe =
                    ExploreRecipe(
                        id = "r-busy",
                        name = "Busy canvas",
                        createdAt = "1",
                        updatedAt = "1",
                        defaultMode = ExploreMode.Pivot,
                        pivot = ExploreConfig(),
                        querySpec = sampleSpec(),
                    )
                viewModel.runRecipe(testConnection(), recipe) { restored = true }
                advanceUntilIdle()

                assertTrue(restored)
                assertNull(viewModel.pendingRecipeRun.value)
                assertEquals(1, service.queryAttempts)
                assertEquals(
                    "The recipe is on the canvas but was not run because a query is already running or waiting for confirmation. Wait, then press Run.",
                    viewModel.recipeApplyNotice.value,
                )

                viewModel.dismissRecipeApplyNotice()
                assertNull(viewModel.recipeApplyNotice.value)

                gate.complete(Unit)
                advanceUntilIdle()
            } finally {
                viewModel.close()
            }
        }

    @Test
    fun recipeRunIsSuppressedWhileTheSqlEditorHasAQueryInFlight() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<Unit>()
            val started = CompletableDeferred<Unit>()
            val service = FakeSafeDbService(queryGate = gate, queryStarted = started)
            val viewModel = AppViewModel(service, dispatcher)
            try {
                advanceUntilIdle()

                viewModel.sqlEditor.run("c1", sampleSpec(), viewModel.sqlEditor.text.text)
                runCurrent()
                assertTrue(started.isCompleted)
                assertTrue(viewModel.sqlEditor.running)

                val recipe =
                    ExploreRecipe(
                        id = "r-sql-busy",
                        name = "SQL busy",
                        createdAt = "1",
                        updatedAt = "1",
                        defaultMode = ExploreMode.Pivot,
                        pivot = ExploreConfig(),
                        querySpec = sampleSpec(),
                    )
                viewModel.runRecipe(testConnection(), recipe)
                advanceUntilIdle()

                assertEquals(1, viewModel.query.tableCount)
                assertEquals(1, service.queryAttempts)
                assertFalse(viewModel.query.running)
                assertNull(viewModel.pendingRecipeRun.value)
                assertNull(viewModel.explore.value)
                assertEquals(
                    "The recipe is on the canvas but was not run because a query is already running or waiting for confirmation. Wait, then press Run.",
                    viewModel.recipeApplyNotice.value,
                )

                gate.complete(Unit)
                advanceUntilIdle()
            } finally {
                viewModel.close()
            }
        }

    @Test
    fun recipeRunIsSuppressedWhileTheSqlEditorAwaitsConfirmation() =
        runTest(dispatcher) {
            val service = FakeSafeDbService(confirmationRequired = true)
            val viewModel = AppViewModel(service, dispatcher)
            try {
                advanceUntilIdle()

                viewModel.sqlEditor.run("c1", sampleSpec(), viewModel.sqlEditor.text.text)
                advanceUntilIdle()
                assertTrue(viewModel.sqlEditor.occupiesQuerySlot)
                assertFalse(viewModel.sqlEditor.running)
                assertNotNull(viewModel.sqlEditor.pendingConfirmation)

                val recipe =
                    ExploreRecipe(
                        id = "r-sql-confirm",
                        name = "SQL confirmation",
                        createdAt = "1",
                        updatedAt = "1",
                        defaultMode = ExploreMode.Pivot,
                        pivot = ExploreConfig(),
                        querySpec = sampleSpec(),
                    )
                viewModel.runRecipe(testConnection(), recipe)
                advanceUntilIdle()

                assertEquals(1, service.queryAttempts)
                assertFalse(viewModel.query.running)
                assertNull(viewModel.pendingRecipeRun.value)
                assertEquals(
                    "The recipe is on the canvas but was not run because a query is already running or waiting for confirmation. Wait, then press Run.",
                    viewModel.recipeApplyNotice.value,
                )
            } finally {
                viewModel.close()
            }
        }

    @Test
    fun settledSqlRiskRejectionDoesNotBlockBuilderOrRecipes() =
        runTest(dispatcher) {
            val service = FakeSafeDbService(riskGateFirstRun = true)
            val viewModel = AppViewModel(service, dispatcher)
            try {
                advanceUntilIdle()

                viewModel.sqlEditor.run("c1", sampleSpec(), viewModel.sqlEditor.text.text)
                advanceUntilIdle()
                assertTrue(viewModel.sqlEditor.pendingRiskGate)
                assertFalse(viewModel.sqlEditor.running)

                // The rejection has settled, so the SQL editor no longer occupies the shared slot.
                assertFalse(viewModel.sqlEditor.occupiesQuerySlot)

                // Applying a recipe must run instead of reporting a nonexistent active query.
                val recipe =
                    ExploreRecipe(
                        id = "r-after-risk",
                        name = "After risk rejection",
                        createdAt = "1",
                        updatedAt = "1",
                        defaultMode = ExploreMode.Pivot,
                        pivot = ExploreConfig(),
                        querySpec = sampleSpec(),
                    )
                viewModel.runRecipe(testConnection(), recipe)
                advanceUntilIdle()

                assertNull(viewModel.recipeApplyNotice.value)
                assertEquals(2, service.queryAttempts)
            } finally {
                viewModel.close()
            }
        }

    @Test
    fun completedBuilderAndSqlQueriesAppearInHistoryWithoutReload() =
        runTest(dispatcher) {
            val service = FakeSafeDbService()
            val viewModel = AppViewModel(service, dispatcher)
            try {
                advanceUntilIdle()
                assertTrue(viewModel.history.entries.value.isEmpty())

                viewModel.query.addTable(sampleTable())
                viewModel.query.run("c1")
                advanceUntilIdle()

                assertEquals(listOf("h1"), viewModel.history.entries.value.map { it.id })
                assertEquals("c1", viewModel.history.entries.value.single().connectionId)
                assertNull(viewModel.history.entries.value.single().error)

                viewModel.sqlEditor.run("c1", sampleSpec(), viewModel.sqlEditor.text.text)
                advanceUntilIdle()

                assertEquals(listOf("h2", "h1"), viewModel.history.entries.value.map { it.id })

                viewModel.history.delete("h1")
                advanceUntilIdle()
                assertEquals(listOf("h2"), viewModel.history.entries.value.map { it.id })

                val saved =
                    SavedQuery(
                        id = "q-saved",
                        name = "Users sample",
                        connectionId = "c1",
                        spec = sampleSpec(),
                        createdAt = "1",
                    )
                viewModel.savedQueries.save(saved)
                advanceUntilIdle()
                assertEquals(listOf("q-saved"), viewModel.savedQueries.queries.value.map { it.id })
                assertEquals(listOf("h2"), viewModel.history.entries.value.map { it.id })
            } finally {
                viewModel.close()
            }
        }

    @Test
    fun settledBuilderRiskRejectionDoesNotBlockTheSqlEditor() =
        runTest(dispatcher) {
            val service = FakeSafeDbService(riskGateFirstRun = true)
            val viewModel = AppViewModel(service, dispatcher)
            try {
                advanceUntilIdle()

                viewModel.query.addTable(sampleTable())
                viewModel.query.run("c1")
                advanceUntilIdle()
                assertTrue(viewModel.query.pendingRiskGate)
                assertFalse(viewModel.query.running)

                // The builder's settled rejection releases the shared slot, so the SQL editor's
                // own run may start.
                assertFalse(viewModel.query.occupiesQuerySlot)
                if (!viewModel.query.occupiesQuerySlot) {
                    viewModel.sqlEditor.run("c1", sampleSpec(), viewModel.sqlEditor.text.text)
                }
                advanceUntilIdle()

                assertEquals(2, service.queryAttempts)
                assertNotNull(viewModel.sqlEditor.results)
            } finally {
                viewModel.close()
            }
        }

    @Test
    fun exploreOriginContextAndRefreshActiveSampleHandleBuilderAndSqlOrigins() =
        runTest(dispatcher) {
            val service = FakeSafeDbService()
            val viewModel = AppViewModel(service, dispatcher)
            try {
                advanceUntilIdle()
                val connection = testConnection()
                val connections = listOf(connection)

                assertFalse(
                    viewModel.exploreOriginContext("c1", null, connections).sampleRefreshEnabled,
                )

                viewModel.query.addTable(sampleTable())
                viewModel.query.run("c1")
                advanceUntilIdle()
                val builderSample = viewModel.query.currentSample("c1")!!

                viewModel.openExplore(connection, builderSample.spec, builderSample.result)
                val builderContext =
                    viewModel.exploreOriginContext("c1", null, connections, ExploreOrigin.Builder)
                assertTrue(builderContext.sampleRefreshEnabled)
                assertEquals(builderSample.spec, builderContext.currentSpec)

                viewModel.query.setLimit(40)
                viewModel.query.run("c1")
                advanceUntilIdle()
                viewModel.refreshActiveExploreSample("c1", null, connections)
                assertEquals(40, viewModel.explore.value?.session?.builderLimit)

                assertFalse(
                    viewModel.exploreOriginContext("c2", null, connections).sampleRefreshEnabled,
                )
                viewModel.refreshActiveExploreSample("c2", null, connections)
                assertEquals(40, viewModel.explore.value?.session?.builderLimit)

                val sqlSpec = sampleSpec().copy(limit = 15)
                viewModel.sqlEditor.run("c1", sqlSpec, viewModel.sqlEditor.text.text)
                advanceUntilIdle()
                val sqlSample = viewModel.sqlEditor.currentSample("c1", sqlSpec)!!
                viewModel.openExplore(
                    connection,
                    sqlSpec,
                    sqlSample.result,
                    ExploreOrigin.Sql,
                    sampleRunId = sqlSample.runId,
                )

                val sqlContext =
                    viewModel.exploreOriginContext("c1", sqlSpec, connections, ExploreOrigin.Sql)
                assertTrue(sqlContext.sampleRefreshEnabled)
                assertEquals(sqlSpec, sqlContext.currentSpec)
                assertFalse(
                    viewModel.explore.value!!.isStale(
                        sqlContext.currentSpec,
                        "c1",
                        sqlContext.latestSampleRunId,
                    ),
                )

                // Re-running the exact same SQL must still mark Explore stale via runId.
                viewModel.sqlEditor.run("c1", sqlSpec, viewModel.sqlEditor.text.text)
                advanceUntilIdle()
                val rerunContext =
                    viewModel.exploreOriginContext("c1", sqlSpec, connections, ExploreOrigin.Sql)
                assertTrue(
                    viewModel.explore.value!!.hasNewerSample(rerunContext.latestSampleRunId),
                )
                assertTrue(
                    viewModel.explore.value!!.isStale(
                        rerunContext.currentSpec,
                        "c1",
                        rerunContext.latestSampleRunId,
                    ),
                )
                viewModel.refreshActiveExploreSample("c1", sqlSpec, connections)
                val refreshedContext =
                    viewModel.exploreOriginContext("c1", sqlSpec, connections, ExploreOrigin.Sql)
                assertFalse(
                    viewModel.explore.value!!.isStale(
                        refreshedContext.currentSpec,
                        "c1",
                        refreshedContext.latestSampleRunId,
                    ),
                )

                val updatedSqlSpec = sampleSpec().copy(limit = 30)
                viewModel.sqlEditor.run("c1", updatedSqlSpec, viewModel.sqlEditor.text.text)
                advanceUntilIdle()
                viewModel.refreshActiveExploreSample("c1", updatedSqlSpec, connections)
                assertEquals(30, viewModel.explore.value?.session?.builderLimit)
            } finally {
                viewModel.close()
            }
        }

    @Test
    fun runRecipeFromExploreSelectsConnectionClosesExploreAndNavigates() =
        runTest(dispatcher) {
            val service = FakeSafeDbService()
            val viewModel = AppViewModel(service, dispatcher)
            try {
                advanceUntilIdle()
                val connection = testConnection()
                val sample =
                    QueryResult(
                        columns = listOf(ResultColumn("t0__status", "varchar")),
                        rows = listOf(listOf(ResultCell.text("pending"))),
                        rowCount = 1,
                        truncated = false,
                        warnings = emptyList(),
                    )
                viewModel.openExplore(connection, sampleSpec(), sample)
                assertNotNull(viewModel.explore.value)

                var selectedSchema: String? = null
                var navigated = false
                val recipe =
                    ExploreRecipe(
                        id = "r-from-explore",
                        name = "From explore",
                        createdAt = "1",
                        updatedAt = "1",
                        defaultMode = ExploreMode.Pivot,
                        pivot = ExploreConfig(),
                        querySpec = sampleSpec(),
                    )

                viewModel.runRecipeFromExplore(
                    connection = connection,
                    recipe = recipe,
                    onSelectConnection = { selectedSchema = it.schema },
                    onRestored = { navigated = true },
                )
                assertNull(viewModel.explore.value)
                assertEquals("public", selectedSchema)

                advanceUntilIdle()
                assertTrue(navigated)
                assertEquals("r-from-explore", viewModel.pendingRecipeRun.value?.recipe?.id)
            } finally {
                viewModel.close()
            }
        }

    @Test
    fun settingsLoadDefaultSchemaOptionsReusesSchemaLoadedInAppViewModel() =
        runTest(dispatcher) {
            val service = FakeSafeDbService()
            val viewModel = AppViewModel(service, dispatcher)
            try {
                advanceUntilIdle()
                viewModel.schema.load("c1")
                advanceUntilIdle()
                assertEquals(1, service.schemaLoadCount)

                viewModel.settings.loadDefaultSchemaOptions("c1")
                advanceUntilIdle()

                assertEquals(listOf("public"), viewModel.settings.defaultSchemaOptions.value)
                assertEquals(1, service.schemaLoadCount)
            } finally {
                viewModel.close()
            }
        }
}
