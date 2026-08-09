package com.safedb.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.safedb.model.QueryRiskGate
import com.safedb.query.QueryConfirmationReasonCode
import com.safedb.query.QueryConfirmationRequirement
import com.safedb.query.QueryPlanStatus
import com.safedb.query.QueryRiskAssessment
import com.safedb.query.QueryRiskEvaluation
import com.safedb.query.RiskGateState
import com.safedb.query.blockingBand

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
): String =
    when {
        validationError != null -> "Query validation: $validationError"
        running -> "Assessing query risk · Run unavailable"
        evaluation?.decision?.state == RiskGateState.ConfirmationRequired ->
            "Query plan safeguard: Confirmation required · Run unavailable"
        evaluation?.decision?.state == RiskGateState.Blocked ->
            "Query risk: ${evaluation.finalAssessment?.severity?.label} · Run blocked"
        evaluation?.confirmationAccepted == true -> {
            val severity = evaluation.finalAssessment?.severity?.label
            if (severity == null) {
                "Query risk: Not required · plan safeguard confirmed · Run enabled"
            } else {
                "Query risk: $severity · plan safeguard confirmed · Run enabled"
            }
        }
        gate == QueryRiskGate.Disabled -> "Query risk: Not required · Run enabled"
        evaluation?.finalAssessment != null -> {
            val finalAssessment = requireNotNull(evaluation.finalAssessment)
            val refinement =
                if (evaluation.planStatus == QueryPlanStatus.Available) " · plan refined" else ""
            "Query risk: ${finalAssessment.severity.label}$refinement · Run enabled"
        }
        preliminary != null ->
            "Preliminary query risk: ${preliminary.severity.label} · Run available"
        else -> "Query risk: Ready to assess · Run enabled"
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
