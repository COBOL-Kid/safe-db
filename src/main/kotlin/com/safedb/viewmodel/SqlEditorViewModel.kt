package com.safedb.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.TextFieldValue
import com.safedb.model.QueryResult
import com.safedb.model.QueryRiskGate
import com.safedb.model.QuerySpec
import com.safedb.query.QueryConfirmationRequirement
import com.safedb.query.QueryRiskEvaluation
import com.safedb.service.QueryRunRequest
import com.safedb.service.SafeDbService
import kotlinx.coroutines.CoroutineScope

class SqlEditorViewModel(service: SafeDbService, scope: CoroutineScope) {
    private val runController = QueryRunController(service, scope)
    private var observedParsedSpec = false
    private var lastParsedSpec: QuerySpec? = null

    var text by mutableStateOf(TextFieldValue(""))
        private set

    val results: QueryResult?
        get() = runController.results

    val running: Boolean
        get() = runController.running

    val error: String?
        get() = runController.error

    val pendingRiskGate: Boolean
        get() = runController.pendingRiskGate

    val pendingConfirmation: QueryConfirmationRequirement?
        get() = runController.pendingConfirmation

    val pendingConfirmationReasons: List<String>
        get() = runController.pendingConfirmationReasons

    val occupiesQuerySlot: Boolean
        get() = runController.occupiesQuerySlot

    fun onTextChanged(value: TextFieldValue) {
        val edited = value.text != text.text
        text = value
        if (edited) runController.invalidateSettledRunFailure()
    }

    // sourceText is the editor text the spec was parsed from. A Run callback captured before a
    // recomposition can carry a spec for text the editor no longer shows; executing it would run
    // a query the user cannot see and later invalidation could not undo. Reject the stale snapshot
    // instead — the recomposed callback resubmits cleanly.
    fun run(connectionId: String, spec: QuerySpec, sourceText: String) {
        if (sourceText != text.text) return
        if (running || pendingRiskGate || pendingConfirmation != null) return
        runController.run(QueryRunRequest(connectionId, spec))
    }

    fun confirmPendingExecution(connectionId: String, currentSpec: QuerySpec?) {
        runController.confirmPendingExecution(connectionId, currentSpec)
    }

    fun dismissPendingConfirmation() {
        runController.invalidateSettledRunFailure()
    }

    fun currentSample(connectionId: String?, currentSpec: QuerySpec?): BuilderQuerySample? =
        runController.currentSample(connectionId, currentSpec)

    fun onActiveConnectionChanged(connectionId: String?) {
        runController.onActiveConnectionChanged(connectionId)
    }

    fun onQueryRiskGateChanged(gate: QueryRiskGate) {
        runController.onQueryRiskGateChanged(gate)
    }

    fun riskEvaluationFor(connectionId: String?, currentSpec: QuerySpec?): QueryRiskEvaluation? =
        runController.riskEvaluationFor(connectionId, currentSpec)

    fun dismissError() {
        runController.invalidateSettledRunFailure()
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
        runController.invalidateSettledRunFailure()
    }

    fun pendingConfirmationFor(
        connectionId: String?,
        spec: QuerySpec?,
    ): QueryConfirmationRequirement? = runController.pendingConfirmationFor(connectionId, spec)

    fun pendingRiskGateFor(connectionId: String?, spec: QuerySpec?): Boolean =
        runController.pendingRiskGateFor(connectionId, spec)
}
