package com.safedb.ui

import androidx.compose.runtime.Composable
import com.safedb.model.QueryRiskGate
import com.safedb.query.QueryConfirmationReasonCode
import com.safedb.query.QueryConfirmationRequirement
import com.safedb.query.QueryPlanStatus
import com.safedb.query.QueryRiskAssessment
import com.safedb.query.QueryRiskEvaluation
import com.safedb.query.RiskGateState
import com.safedb.query.blockingBand
import com.safedb.ui.components.ConfirmDialog

internal fun riskGateIndicatorText(gate: QueryRiskGate): String =
    when (gate) {
        QueryRiskGate.Disabled -> "Risk gate: Off"
        else -> "Risk gate: ${gate.name} · blocks ${blockingBand(gate)?.label} and above"
    }

internal fun queryRiskIndicatorText(
    preliminary: QueryRiskAssessment?,
    evaluation: QueryRiskEvaluation?,
    running: Boolean,
    gate: QueryRiskGate,
    validationError: String? = null,
    runAvailable: Boolean = true,
): String {
    val runEnabledLabel = if (runAvailable) "Run enabled" else "Run unavailable"
    val runAvailableLabel = if (runAvailable) "Run available" else "Run unavailable"
    return when {
        validationError != null -> "Query validation: $validationError"
        running -> "Assessing query risk · Run unavailable"
        evaluation?.decision?.state == RiskGateState.ConfirmationRequired ->
            "Query plan safeguard: Confirmation required · Run unavailable"
        evaluation?.decision?.state == RiskGateState.Blocked ->
            "Query risk: ${evaluation.finalAssessment?.severity?.label} · Run blocked"
        evaluation?.confirmationAccepted == true -> {
            val severity = evaluation.finalAssessment?.severity?.label
            if (severity == null) {
                "Query risk: Not required · plan safeguard confirmed · $runEnabledLabel"
            } else {
                "Query risk: $severity · plan safeguard confirmed · $runEnabledLabel"
            }
        }
        gate == QueryRiskGate.Disabled -> "Query risk: Not required · $runEnabledLabel"
        evaluation?.finalAssessment != null -> {
            val finalAssessment = requireNotNull(evaluation.finalAssessment)
            val refinement =
                if (evaluation.planStatus == QueryPlanStatus.Available) " · plan refined" else ""
            "Query risk: ${finalAssessment.severity.label}$refinement · $runEnabledLabel"
        }
        preliminary != null ->
            "Preliminary query risk: ${preliminary.severity.label} · $runAvailableLabel"
        else -> "Query risk: Ready to assess · $runEnabledLabel"
    }
}

internal data class QueryConfirmationDialogCopy(
    val title: String,
    val message: String,
    val confirmLabel: String,
)

internal fun queryConfirmationDialogCopy(
    requirement: QueryConfirmationRequirement
): QueryConfirmationDialogCopy {
    val reasonCodes = requirement.confirmation.reasonCodes
    val title =
        when {
            QueryConfirmationReasonCode.PlanUnavailable in reasonCodes -> "Query plan unavailable"
            QueryConfirmationReasonCode.OptimizerCostUnavailable in reasonCodes ->
                "Optimizer cost unavailable"
            else -> "Confirm query execution"
        }
    return QueryConfirmationDialogCopy(
        title = title,
        message = requirement.reasons.joinToString(separator = " ") { it.message },
        confirmLabel = "Run with safeguards",
    )
}

@Composable
internal fun QueryConfirmationDialog(
    requirement: QueryConfirmationRequirement,
    otherEditorBusy: Boolean,
    connectionId: String?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val copy = queryConfirmationDialogCopy(requirement)
    ConfirmDialog(
        open = true,
        title = copy.title,
        message = copy.message,
        confirmLabel = copy.confirmLabel,
        onConfirm = {
            if (otherEditorBusy) return@ConfirmDialog
            if (connectionId == null) onDismiss() else onConfirm(connectionId)
        },
        onCancel = onDismiss,
    )
}

internal fun planSafeguardBannerText(evaluation: QueryRiskEvaluation?): String? {
    evaluation ?: return null
    return when (evaluation.planStatus) {
        QueryPlanStatus.Unavailable ->
            when {
                evaluation.confirmationAccepted ->
                    "Plan unavailable; execution was explicitly confirmed."
                evaluation.confirmationRequirement != null ->
                    "Plan unavailable; explicit confirmation is required."
                else -> "Plan unavailable; static assessment used."
            }
        QueryPlanStatus.Incomplete ->
            when {
                evaluation.confirmationAccepted ->
                    "Optimizer cost unavailable; execution was explicitly confirmed."
                evaluation.confirmationRequirement != null ->
                    "Optimizer cost unavailable; explicit confirmation is required."
                else -> "Optimizer cost unavailable."
            }
        else -> null
    }
}
