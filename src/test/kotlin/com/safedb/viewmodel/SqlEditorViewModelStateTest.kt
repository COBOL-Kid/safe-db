package com.safedb.viewmodel

import androidx.compose.ui.text.input.TextFieldValue
import com.safedb.model.ColumnSel
import com.safedb.model.FilterGroup
import com.safedb.model.QueryResult
import com.safedb.model.QueryRiskGate
import com.safedb.model.QuerySpec
import com.safedb.model.ResultColumn
import com.safedb.model.TableRef
import com.safedb.query.QueryConfirmationRequirement
import com.safedb.query.QueryError
import com.safedb.query.QueryExecutionConfirmation
import com.safedb.query.QueryPlanStatus
import com.safedb.query.QueryRiskDecision
import com.safedb.query.QueryRiskEvaluation
import com.safedb.query.RiskDecisionReason
import com.safedb.query.RiskGateState
import com.safedb.service.FakeSafeDbServiceSupport
import com.safedb.service.QueryFailureException
import com.safedb.service.QueryRunRequest
import com.safedb.service.QueryRunResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent

@OptIn(ExperimentalCoroutinesApi::class)
class SqlEditorViewModelStateTest {
    private val dispatcher = StandardTestDispatcher()

    private fun sampleSpec() =
        QuerySpec(
            tables = listOf(TableRef("public", "users", "users")),
            columns = listOf(ColumnSel("users", "id")),
            filters = FilterGroup(id = "g0"),
            limit = 500,
        )

    private fun sampleResult() =
        QueryResult(
            columns = listOf(ResultColumn("users__id", "int")),
            rows = emptyList(),
            rowCount = 0,
            truncated = false,
            warnings = emptyList(),
        )

    private fun evaluation(state: RiskGateState) =
        QueryRiskEvaluation(
            staticAssessment = null,
            finalAssessment = null,
            planStatus = QueryPlanStatus.Unavailable,
            decision =
                QueryRiskDecision(
                    queryFingerprint = "fp",
                    state = state,
                    effectiveGate = QueryRiskGate.Standard,
                    blockingBand = null,
                    reasons = listOf(RiskDecisionReason("code", "Reason text.")),
                ),
        )

    private fun requirement() =
        QueryConfirmationRequirement(
            confirmation =
                QueryExecutionConfirmation(
                    connectionId = "c1",
                    connectionFingerprint = "cf",
                    queryFingerprint = "fp",
                    conditions = emptySet(),
                ),
            reasons = listOf(RiskDecisionReason("plan", "Plan unavailable.")),
        )

    @Test
    fun successfulRunPopulatesResultsAndSample() {
        val scope = TestScope(dispatcher)
        val spec = sampleSpec()
        val viewModel =
            SqlEditorViewModel(
                service =
                    object : SqlStubService() {
                        override suspend fun runQuery(request: QueryRunRequest) =
                            queryRunResult(sampleResult())
                    },
                scope = scope,
            )

        viewModel.run("c1", spec)
        assertTrue(viewModel.running)
        scope.advanceUntilIdle()

        assertFalse(viewModel.running)
        assertNotNull(viewModel.results)
        assertNotNull(viewModel.riskEvaluationFor("c1", spec))
        assertNull(viewModel.riskEvaluationFor("other", spec))
        assertNotNull(viewModel.currentSample("c1", spec))
        assertNull(viewModel.currentSample("c1", spec.copy(limit = 10)))
        assertNull(viewModel.currentSample("c2", spec))
    }

    @Test
    fun validationFailureSurfacesErrorMessage() {
        val scope = TestScope(dispatcher)
        val viewModel =
            SqlEditorViewModel(
                service =
                    object : SqlStubService() {
                        override suspend fun runQuery(request: QueryRunRequest) =
                            throw QueryFailureException(QueryError.Validation("Bad query."))
                    },
                scope = scope,
            )

        viewModel.run("c1", sampleSpec())
        scope.advanceUntilIdle()

        assertEquals("Bad query.", viewModel.error)
        assertFalse(viewModel.pendingRiskGate)
        assertNull(viewModel.results)

        viewModel.dismissError()
        assertNull(viewModel.error)
    }

    @Test
    fun riskGateBlockSetsPendingGateAndClearsOnGateChange() {
        val scope = TestScope(dispatcher)
        val spec = sampleSpec()
        val viewModel =
            SqlEditorViewModel(
                service =
                    object : SqlStubService() {
                        override suspend fun runQuery(request: QueryRunRequest) =
                            throw QueryFailureException(
                                QueryError.RiskGate(evaluation(RiskGateState.Blocked), request.spec)
                            )
                    },
                scope = scope,
            )
        viewModel.onQueryRiskGateChanged(QueryRiskGate.Standard)

        viewModel.run("c1", spec)
        scope.advanceUntilIdle()

        assertTrue(viewModel.pendingRiskGate)
        assertEquals("Reason text.", viewModel.error)

        viewModel.onQueryRiskGateChanged(QueryRiskGate.Flexible)

        assertFalse(viewModel.pendingRiskGate)
        assertNull(viewModel.error)
        assertNull(viewModel.riskEvaluationFor("c1", spec))
    }

    @Test
    fun confirmationRequiredThenConfirmReRunsWithConfirmation() {
        val scope = TestScope(dispatcher)
        val spec = sampleSpec()
        val confirmations = mutableListOf<QueryRunRequest>()
        val viewModel =
            SqlEditorViewModel(
                service =
                    object : SqlStubService() {
                        override suspend fun runQuery(request: QueryRunRequest) =
                            if (request.confirmation == null) {
                                throw QueryFailureException(
                                    QueryError.ConfirmationRequired(
                                        evaluation(RiskGateState.ConfirmationRequired),
                                        requirement(),
                                        request.spec,
                                    )
                                )
                            } else {
                                confirmations.add(request)
                                queryRunResult(sampleResult())
                            }
                    },
                scope = scope,
            )

        viewModel.run("c1", spec)
        scope.advanceUntilIdle()

        assertNotNull(viewModel.pendingConfirmation)
        assertEquals(listOf("Plan unavailable."), viewModel.pendingConfirmationReasons)
        assertNotNull(viewModel.pendingConfirmationFor("c1", spec))
        assertNull(viewModel.pendingConfirmationFor("c1", spec.copy(limit = 10)))
        assertNull(viewModel.error)

        viewModel.confirmPendingExecution("c1", spec)
        scope.advanceUntilIdle()

        assertEquals(1, confirmations.size)
        assertNotNull(confirmations.single().confirmation)
        assertNotNull(viewModel.results)
        assertNull(viewModel.pendingConfirmation)
    }

    @Test
    fun confirmingWithChangedSpecDropsTheConfirmation() {
        val scope = TestScope(dispatcher)
        val spec = sampleSpec()
        var executions = 0
        val viewModel =
            SqlEditorViewModel(
                service =
                    object : SqlStubService() {
                        override suspend fun runQuery(request: QueryRunRequest) =
                            if (request.confirmation == null) {
                                throw QueryFailureException(
                                    QueryError.ConfirmationRequired(
                                        evaluation(RiskGateState.ConfirmationRequired),
                                        requirement(),
                                        request.spec,
                                    )
                                )
                            } else {
                                executions++
                                queryRunResult(sampleResult())
                            }
                    },
                scope = scope,
            )

        viewModel.run("c1", spec)
        scope.advanceUntilIdle()
        assertNotNull(viewModel.pendingConfirmation)

        viewModel.confirmPendingExecution("c1", spec.copy(limit = 10))
        scope.advanceUntilIdle()

        assertEquals(0, executions)
        assertNull(viewModel.pendingConfirmation)
        // The abandoned request's evaluation must go too, or the header keeps demanding a
        // confirmation whose dialog no longer exists.
        assertNull(viewModel.riskEvaluationFor("c1", spec))
    }

    @Test
    fun dismissingConfirmationClearsTheEvaluationAndReenablesRun() {
        val scope = TestScope(dispatcher)
        val spec = sampleSpec()
        var attempts = 0
        var executions = 0
        val viewModel =
            SqlEditorViewModel(
                service =
                    object : SqlStubService() {
                        override suspend fun runQuery(request: QueryRunRequest) =
                            // Only the first attempt demands confirmation; a later run must be able
                            // to proceed once the stale decision has been cleared.
                            if (attempts++ == 0) {
                                throw QueryFailureException(
                                    QueryError.ConfirmationRequired(
                                        evaluation(RiskGateState.ConfirmationRequired),
                                        requirement(),
                                        request.spec,
                                    )
                                )
                            } else {
                                executions++
                                queryRunResult(sampleResult())
                            }
                    },
                scope = scope,
            )

        viewModel.run("c1", spec)
        scope.advanceUntilIdle()
        assertNotNull(viewModel.pendingConfirmation)
        assertNotNull(viewModel.riskEvaluationFor("c1", spec))

        viewModel.dismissPendingConfirmation()

        assertNull(viewModel.pendingConfirmation)
        assertNull(viewModel.riskEvaluationFor("c1", spec))

        // Cancelling leaves the editor runnable again rather than stuck behind a stale decision.
        viewModel.run("c1", spec)
        scope.advanceUntilIdle()
        assertEquals(1, executions)
    }

    @Test
    fun textEditClearsPendingConfirmationAndError() {
        val scope = TestScope(dispatcher)
        val viewModel =
            SqlEditorViewModel(
                service =
                    object : SqlStubService() {
                        override suspend fun runQuery(request: QueryRunRequest) =
                            throw QueryFailureException(
                                QueryError.ConfirmationRequired(
                                    evaluation(RiskGateState.ConfirmationRequired),
                                    requirement(),
                                    request.spec,
                                )
                            )
                    },
                scope = scope,
            )

        viewModel.onTextChanged(TextFieldValue("SELECT id FROM users"))
        viewModel.run("c1", sampleSpec())
        scope.advanceUntilIdle()
        assertNotNull(viewModel.pendingConfirmation)

        // A selection-only change must not disturb pending state.
        viewModel.onTextChanged(TextFieldValue("SELECT id FROM users"))
        assertNotNull(viewModel.pendingConfirmation)

        viewModel.onTextChanged(TextFieldValue("SELECT id FROM users LIMIT 5"))
        assertNull(viewModel.pendingConfirmation)

        viewModel.confirmPendingExecution("c1", sampleSpec())
        scope.advanceUntilIdle()
        assertNull(viewModel.results)
    }

    @Test
    fun connectionChangeResetsRunState() {
        val scope = TestScope(dispatcher)
        val spec = sampleSpec()
        val viewModel =
            SqlEditorViewModel(
                service =
                    object : SqlStubService() {
                        override suspend fun runQuery(request: QueryRunRequest) =
                            queryRunResult(sampleResult())
                    },
                scope = scope,
            )

        viewModel.onActiveConnectionChanged("c1")
        viewModel.run("c1", spec)
        scope.advanceUntilIdle()
        assertNotNull(viewModel.currentSample("c1", spec))

        viewModel.onActiveConnectionChanged("c2")

        assertNull(viewModel.results)
        assertNull(viewModel.currentSample("c1", spec))
        assertNull(viewModel.error)
        assertFalse(viewModel.pendingRiskGate)
    }

    @Test
    fun parsedSpecChangeClearsSettledRiskGate() {
        val scope = TestScope(dispatcher)
        val specA = sampleSpec()
        val specB = sampleSpec().copy(limit = 10)
        val viewModel =
            SqlEditorViewModel(
                service =
                    object : SqlStubService() {
                        override suspend fun runQuery(request: QueryRunRequest) =
                            throw QueryFailureException(
                                QueryError.RiskGate(evaluation(RiskGateState.Blocked), request.spec)
                            )
                    },
                scope = scope,
            )

        viewModel.run("c1", specA)
        scope.advanceUntilIdle()
        assertTrue(viewModel.pendingRiskGate)
        assertTrue(viewModel.pendingRiskGateFor("c1", specA))
        assertFalse(viewModel.pendingRiskGateFor("c1", specB))

        viewModel.onParsedSpecChanged(specA)
        assertTrue(viewModel.pendingRiskGate)
        assertTrue(viewModel.pendingRiskGateFor("c1", specA))

        viewModel.onParsedSpecChanged(specB)
        assertFalse(viewModel.pendingRiskGate)
        assertFalse(viewModel.pendingRiskGateFor("c1", specA))
        assertFalse(viewModel.pendingRiskGateFor("c1", specB))
        assertNull(viewModel.error)
    }

    @Test
    fun inFlightConfirmationDoesNotApplyAfterSpecChange() {
        val scope = TestScope(dispatcher)
        val specA = sampleSpec()
        val specB = sampleSpec().copy(limit = 10)
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val viewModel =
            SqlEditorViewModel(
                service =
                    object : SqlStubService() {
                        override suspend fun runQuery(request: QueryRunRequest): QueryRunResult {
                            started.complete(Unit)
                            gate.await()
                            throw QueryFailureException(
                                QueryError.ConfirmationRequired(
                                    evaluation(RiskGateState.ConfirmationRequired),
                                    requirement(),
                                    request.spec,
                                )
                            )
                        }
                    },
                scope = scope,
            )

        viewModel.run("c1", specA)
        scope.runCurrent()
        assertTrue(started.isCompleted)
        assertTrue(viewModel.running)

        viewModel.onParsedSpecChanged(specA)
        viewModel.onParsedSpecChanged(specB)
        assertTrue(viewModel.running)
        assertNull(viewModel.pendingConfirmation)

        gate.complete(Unit)
        scope.advanceUntilIdle()

        assertFalse(viewModel.running)
        assertNull(viewModel.pendingConfirmation)
        assertNull(viewModel.pendingConfirmationFor("c1", specA))
        assertNull(viewModel.pendingConfirmationFor("c1", specB))
    }
}

private open class SqlStubService : FakeSafeDbServiceSupport() {
    override suspend fun runQuery(request: QueryRunRequest) =
        queryRunResult(QueryResult(emptyList(), emptyList(), 0, false, emptyList()))
}
