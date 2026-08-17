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
import com.safedb.service.QueryRunRequest
import com.safedb.service.SafeDbService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// Retain ownership until the service settles because blocking JDBC work may ignore cancellation.
private class ActiveRun

// The run/risk/confirmation state machine shared by the builder and the SQL editor. Each editor
// owns its own instance so settled results survive a run in the other editor.
internal class QueryRunController(
    private val service: SafeDbService,
    private val scope: CoroutineScope,
) {
    private var runGeneration = 0
    private var activeRun: ActiveRun? = null
    private var observedActiveConnection = false
    private var activeConnectionId: String? = null
    private var observedQueryRiskGate: QueryRiskGate? = null
    private var riskEvaluationConnectionId: String? = null
    private var riskEvaluationSpec: QuerySpec? = null
    private var pendingConfirmationRequest: QueryRunRequest? = null
    private var pendingConfirmationOnSettled: ((Boolean) -> Unit)? = null

    var results by mutableStateOf<QueryResult?>(null)
        private set

    private var resultConnectionId by mutableStateOf<String?>(null)
    private var resultSpec by mutableStateOf<QuerySpec?>(null)

    var running by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
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
        val generation = ++runGeneration
        val run = ActiveRun()
        activeRun = run
        running = true
        error = null
        results = null
        resultConnectionId = null
        resultSpec = null
        riskEvaluation = null
        riskEvaluationConnectionId = null
        riskEvaluationSpec = null
        pendingRiskGateState = false
        scope.launch {
            var succeeded = false
            var awaitingConfirmation = false
            try {
                val completed = service.runQuery(request)
                if (generation == runGeneration) {
                    results = completed.queryResult
                    riskEvaluation = completed.riskEvaluation
                    riskEvaluationConnectionId = request.connectionId
                    riskEvaluationSpec = request.spec
                    resultConnectionId = request.connectionId
                    resultSpec = executedSpec
                    succeeded = true
                }
            } catch (failure: QueryFailureException) {
                if (generation != runGeneration) return@launch
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
                if (generation == runGeneration) error = e.message ?: e.toString()
            } finally {
                if (activeRun === run) {
                    activeRun = null
                    running = false
                    if (!awaitingConfirmation) onSettled?.invoke(succeeded)
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

    // The sample is only valid while the editor still produces the spec that ran.
    fun currentSample(connectionId: String?, currentSpec: QuerySpec?): BuilderQuerySample? {
        if (connectionId == null || resultConnectionId != connectionId) return null
        val result = results ?: return null
        val executedSpec = resultSpec ?: return null
        if (currentSpec != executedSpec) return null
        return BuilderQuerySample(connectionId, executedSpec, result)
    }

    // Do not clear running here; the prior JDBC call still owns the single-operation slot.
    fun onActiveConnectionChanged(connectionId: String?) {
        if (observedActiveConnection && activeConnectionId == connectionId) return
        observedActiveConnection = true
        activeConnectionId = connectionId
        if (running) runGeneration += 1
        results = null
        resultConnectionId = null
        resultSpec = null
        pendingRiskGateState = false
        riskEvaluation = null
        riskEvaluationConnectionId = null
        riskEvaluationSpec = null
        error = null
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
            runGeneration += 1
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

    fun invalidateSettledRunFailure() {
        if (running) {
            runGeneration += 1
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
        runGeneration += 1
        results = null
        resultConnectionId = null
        resultSpec = null
        error = null
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
