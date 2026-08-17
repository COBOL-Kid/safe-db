package com.safedb.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.TextFieldValue
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
private class ActiveSqlRun

class SqlEditorViewModel(private val service: SafeDbService, private val scope: CoroutineScope) {
    private var runGeneration = 0
    private var activeRun: ActiveSqlRun? = null
    private var observedActiveConnection = false
    private var activeConnectionId: String? = null
    private var observedQueryRiskGate: QueryRiskGate? = null
    private var riskEvaluationConnectionId: String? = null
    private var riskEvaluationSpec: QuerySpec? = null
    private var pendingConfirmationRequest: QueryRunRequest? = null
    private var observedParsedSpec = false
    private var lastParsedSpec: QuerySpec? = null

    var text by mutableStateOf(TextFieldValue(""))
        private set

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

    fun onTextChanged(value: TextFieldValue) {
        val edited = value.text != text.text
        text = value
        if (edited) invalidateSettledRunFailure()
    }

    // sourceText is the editor text the spec was parsed from. A Run callback captured before a
    // recomposition can carry a spec for text the editor no longer shows; executing it would run
    // a query the user cannot see and later invalidation could not undo. Reject the stale snapshot
    // instead — the recomposed callback resubmits cleanly.
    fun run(connectionId: String, spec: QuerySpec, sourceText: String) {
        if (sourceText != text.text) return
        if (running || pendingRiskGateState || pendingConfirmation != null) return
        run(QueryRunRequest(connectionId, spec))
    }

    private fun run(request: QueryRunRequest) {
        if (activeRun != null) return
        observedActiveConnection = true
        activeConnectionId = request.connectionId
        val executedSpec = request.spec
        val generation = ++runGeneration
        val run = ActiveSqlRun()
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
            try {
                val completed = service.runQuery(request)
                if (generation == runGeneration) {
                    results = completed.queryResult
                    riskEvaluation = completed.riskEvaluation
                    riskEvaluationConnectionId = request.connectionId
                    riskEvaluationSpec = request.spec
                    resultConnectionId = request.connectionId
                    resultSpec = executedSpec
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
                        pendingConfirmationRequest = request
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
                }
            }
        }
    }

    fun confirmPendingExecution(connectionId: String, currentSpec: QuerySpec?) {
        if (running || activeRun != null) return
        val request = pendingConfirmationRequest ?: return
        val requirement = pendingConfirmation ?: return
        if (request.connectionId != connectionId || request.spec != currentSpec) {
            // The evaluation described the request we are abandoning, so drop it too — otherwise
            // the header and plan-safeguard banner keep demanding a confirmation that is gone.
            invalidateSettledRunFailure()
            return
        }
        clearPendingConfirmation()
        run(request.copy(confirmation = requirement.confirmation))
    }

    fun dismissPendingConfirmation() {
        invalidateSettledRunFailure()
    }

    // The sample is only valid while the editor still parses to the spec that produced it.
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
        clearPendingConfirmation()
    }

    // Gate changes invalidate descriptive decisions, but the service rechecks hard plan conditions.
    fun onQueryRiskGateChanged(gate: QueryRiskGate) {
        val previous = observedQueryRiskGate ?: riskEvaluation?.decision?.effectiveGate
        observedQueryRiskGate = gate
        if (previous == null || previous == gate) return

        if (running) {
            runGeneration += 1
            return
        }
        if (riskEvaluation?.decision?.effectiveGate == gate) return
        if (pendingConfirmation != null) return

        val wasRiskGateBlock = pendingRiskGateState
        pendingRiskGateState = false
        riskEvaluation = null
        riskEvaluationConnectionId = null
        riskEvaluationSpec = null
        if (wasRiskGateBlock) error = null
    }

    // Keyed by spec as well as connection, like currentSample: editing the SQL or switching the
    // default schema reparses to a different spec, and the old decision no longer describes it.
    fun riskEvaluationFor(connectionId: String?, currentSpec: QuerySpec?): QueryRiskEvaluation? =
        riskEvaluation.takeIf {
            connectionId != null &&
                connectionId == riskEvaluationConnectionId &&
                currentSpec != null &&
                currentSpec == riskEvaluationSpec
        }

    fun dismissError() {
        invalidateSettledRunFailure()
    }

    // Skip the first observation so composing SQL does not wipe a live gate as null → current spec.
    fun onParsedSpecChanged(spec: QuerySpec?) {
        if (!observedParsedSpec) {
            observedParsedSpec = true
            lastParsedSpec = spec
            return
        }
        if (lastParsedSpec == spec) return
        lastParsedSpec = spec
        invalidateSettledRunFailure()
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

    private fun invalidateSettledRunFailure() {
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
        clearPendingConfirmation()
    }

    private fun clearPendingConfirmation() {
        pendingConfirmationRequest = null
        pendingConfirmation = null
    }
}
