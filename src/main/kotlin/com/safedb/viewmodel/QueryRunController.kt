package com.safedb.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.safedb.model.QueryResult
import com.safedb.model.QueryRiskGate
import com.safedb.model.QuerySpec
import com.safedb.query.QueryConfirmationRequirement
import com.safedb.query.QueryError
import com.safedb.query.QueryRiskEvaluation
import com.safedb.service.QueryFailureException
import com.safedb.service.QueryHistoryRecording
import com.safedb.service.QueryRunRequest
import com.safedb.service.SafeDbService
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// Retain ownership until the service settles because blocking JDBC work may ignore cancellation.
private class ActiveRun

// Each editor owns its own instance so settled results survive a run in the other editor.
internal class QueryRunController(
    private val service: SafeDbService,
    private val scope: CoroutineScope,
    private val onRunFinished: () -> Unit = {},
    private val onConnectionReachable: (String) -> Unit = {},
) {
    private val runGeneration = AtomicLong(0L)
    private var activeRun: ActiveRun? = null
    private var observedActiveConnection = false
    private var activeConnectionId: String? = null
    private var observedQueryRiskGate: QueryRiskGate? = null
    private var riskEvaluationConnectionId: String? = null
    private var riskEvaluationSpec: QuerySpec? = null
    private var pendingConfirmationRequest: QueryRunRequest? = null
    private var pendingConfirmationOnSettled: ((Boolean) -> Unit)? = null

    private var settledSample by mutableStateOf<BuilderQuerySample?>(null)

    val results: QueryResult?
        get() = settledSample?.result

    var running by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    var discardedRunNotice by mutableStateOf<String?>(null)
        private set

    var riskEvaluation by mutableStateOf<QueryRiskEvaluation?>(null)
        private set

    private var pendingRiskGateState by mutableStateOf(false)
    val pendingRiskGate: Boolean
        get() = pendingRiskGateState

    var pendingConfirmation by mutableStateOf<QueryConfirmationRequirement?>(null)
        private set

    val pendingConfirmationReasons: List<String>
        get() = pendingConfirmation?.reasons.orEmpty().map { it.message }

    // A settled risk-gate failure only blocks this editor's own Run; the shared slot is occupied
    // solely by work that is still live (running or awaiting the user's confirmation).
    val occupiesQuerySlot: Boolean
        get() = running || pendingConfirmation != null

    fun run(request: QueryRunRequest, onSettled: ((Boolean) -> Unit)? = null) {
        if (activeRun != null) return
        observedActiveConnection = true
        activeConnectionId = request.connectionId
        val executedSpec = request.spec
        val generation = runGeneration.incrementAndGet()
        val run = ActiveRun()
        activeRun = run
        running = true
        error = null
        discardedRunNotice = null
        settledSample = null
        riskEvaluation = null
        riskEvaluationConnectionId = null
        riskEvaluationSpec = null
        pendingRiskGateState = false
        // Keep the JDBC call running: adapters ignore coroutine cancellation, and the shared
        // query slot stays occupied until the session closes. History is skipped instead when
        // this generation is no longer current (edit, connection switch, or other invalidation).
        val trackedRequest =
            request.copy(
                historyRecording = QueryHistoryRecording { runGeneration.get() == generation },
            )
        scope.launch {
            var succeeded = false
            var awaitingConfirmation = false
            try {
                val completed = service.runQuery(trackedRequest)
                if (generation == runGeneration.get()) {
                    settledSample =
                        BuilderQuerySample(
                            connectionId = request.connectionId,
                            spec = executedSpec,
                            result = completed.queryResult,
                            stale = false,
                            runId = generation,
                        )
                    riskEvaluation = completed.riskEvaluation
                    riskEvaluationConnectionId = request.connectionId
                    riskEvaluationSpec = request.spec
                    succeeded = true
                    onConnectionReachable(request.connectionId)
                }
            } catch (failure: QueryFailureException) {
                if (generation != runGeneration.get()) return@launch
                when (val queryError = failure.queryError) {
                    is QueryError.RiskGate -> {
                        pendingRiskGateState = true
                        riskEvaluation = queryError.evaluation
                        riskEvaluationConnectionId = request.connectionId
                        riskEvaluationSpec = request.spec
                        error = queryError.message
                    }
                    is QueryError.ConfirmationRequired -> {
                        awaitingConfirmation = true
                        pendingConfirmationRequest = request
                        pendingConfirmationOnSettled = onSettled
                        pendingConfirmation = queryError.requirement
                        riskEvaluation = queryError.evaluation
                        riskEvaluationConnectionId = request.connectionId
                        riskEvaluationSpec = request.spec
                    }
                    else -> error = failure.message ?: failure.toString()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == runGeneration.get()) error = e.message ?: e.toString()
            } finally {
                if (activeRun === run) {
                    activeRun = null
                    running = false
                    if (!awaitingConfirmation) onSettled?.invoke(succeeded)
                    // Refresh is best-effort: current success and QueryCore failures record
                    // history, discarded in-flight runs and early setup failures may not. A
                    // single call point in finally is intentional.
                    onRunFinished()
                }
            }
        }
    }

    fun confirmPendingExecution(connectionId: String, currentSpec: QuerySpec?) {
        if (running || activeRun != null) return
        val request = pendingConfirmationRequest ?: return
        val requirement = pendingConfirmation ?: return
        val onSettled = pendingConfirmationOnSettled
        if (request.connectionId != connectionId || request.spec != currentSpec) {
            // The evaluation described the request we are abandoning, so drop it too — otherwise
            // the header and plan-safeguard banner keep demanding a confirmation that is gone.
            invalidateSettledRunFailure()
            return
        }
        clearPendingConfirmation(settle = false)
        run(request.copy(confirmation = requirement.confirmation), onSettled)
    }

    // The sample is only valid while the editor still produces the spec that ran. Limit-only
    // edits keep the last result visible so changing ALL/row cap does not collapse the pane.
    fun currentSample(
        connectionId: String?,
        currentSpec: QuerySpec?,
        allowStaleLimit: Boolean = false,
    ): BuilderQuerySample? {
        val sample = settledSample ?: return null
        if (connectionId == null || sample.connectionId != connectionId) return null
        if (currentSpec == sample.spec) return sample
        if (
            allowStaleLimit &&
                currentSpec != null &&
                currentSpec.copy(limit = sample.spec.limit, uncapped = sample.spec.uncapped) ==
                    sample.spec
        ) {
            return sample.copy(stale = true)
        }
        return null
    }

    // Do not clear running here; the prior JDBC call still owns the single-operation slot.
    fun onActiveConnectionChanged(connectionId: String?) {
        if (observedActiveConnection && activeConnectionId == connectionId) return
        observedActiveConnection = true
        activeConnectionId = connectionId
        if (running) runGeneration.incrementAndGet()
        settledSample = null
        pendingRiskGateState = false
        riskEvaluation = null
        riskEvaluationConnectionId = null
        riskEvaluationSpec = null
        error = null
        discardedRunNotice = null
        clearPendingConfirmation(settle = true)
    }

    // Gate changes invalidate descriptive decisions, but the service rechecks hard plan conditions.
    fun onQueryRiskGateChanged(gate: QueryRiskGate) {
        val previous = observedQueryRiskGate ?: riskEvaluation?.decision?.effectiveGate
        observedQueryRiskGate = gate
        if (previous == null || previous == gate) return

        if (running) {
            // The service snapshots settings before EXPLAIN/execution. Keep ownership until that
            // call settles, but discard its old-policy completion and require a fresh Run.
            runGeneration.incrementAndGet()
            return
        }
        // A fast run may already have observed the newly persisted gate before this UI callback.
        if (riskEvaluation?.decision?.effectiveGate == gate) return
        if (pendingConfirmation != null) return

        val wasRiskGateBlock = pendingRiskGateState
        pendingRiskGateState = false
        riskEvaluation = null
        riskEvaluationConnectionId = null
        riskEvaluationSpec = null
        if (wasRiskGateBlock) error = null
    }

    // Keyed by spec as well as connection, like currentSample: editing the query reshapes the
    // spec, and the old decision no longer describes it.
    fun riskEvaluationFor(connectionId: String?, currentSpec: QuerySpec?): QueryRiskEvaluation? =
        riskEvaluation.takeIf {
            connectionId != null &&
                connectionId == riskEvaluationConnectionId &&
                currentSpec != null &&
                currentSpec == riskEvaluationSpec
        }

    fun pendingConfirmationFor(
        connectionId: String?,
        spec: QuerySpec?,
    ): QueryConfirmationRequirement? {
        val request = pendingConfirmationRequest ?: return null
        return pendingConfirmation.takeIf {
            connectionId != null &&
                spec != null &&
                request.connectionId == connectionId &&
                request.spec == spec
        }
    }

    fun pendingRiskGateFor(connectionId: String?, spec: QuerySpec?): Boolean =
        pendingRiskGateState &&
            connectionId != null &&
            spec != null &&
            connectionId == riskEvaluationConnectionId &&
            spec == riskEvaluationSpec

    fun dismissDiscardedRunNotice() {
        discardedRunNotice = null
    }

    fun invalidateForEdit() {
        if (running) {
            discardedRunNotice = DISCARDED_RUN_NOTICE
        }
        invalidateSettledRunFailure()
    }

    fun invalidateSettledRunFailure() {
        if (running) {
            runGeneration.incrementAndGet()
        }
        if (
            error == null &&
                !pendingRiskGateState &&
                riskEvaluation == null &&
                pendingConfirmation == null
        )
            return
        pendingRiskGateState = false
        riskEvaluation = null
        riskEvaluationConnectionId = null
        riskEvaluationSpec = null
        error = null
        clearPendingConfirmation(settle = true)
    }

    fun reset() {
        runGeneration.incrementAndGet()
        settledSample = null
        error = null
        discardedRunNotice = null
        riskEvaluation = null
        riskEvaluationConnectionId = null
        riskEvaluationSpec = null
        pendingRiskGateState = false
        clearPendingConfirmation(settle = true)
    }

    private fun clearPendingConfirmation(settle: Boolean) {
        val onSettled = pendingConfirmationOnSettled
        pendingConfirmationRequest = null
        pendingConfirmationOnSettled = null
        pendingConfirmation = null
        if (settle) onSettled?.invoke(false)
    }
}

private const val DISCARDED_RUN_NOTICE =
    "Query edited while a run was in progress; the in-flight result will be discarded."
