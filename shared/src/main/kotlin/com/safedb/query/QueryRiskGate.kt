package com.safedb.query

import com.safedb.model.QueryRiskGate

fun applyRiskGate(
    assessment: QueryRiskAssessment?,
    userSetting: QueryRiskGate,
    validationBlocked: Boolean = false,
): QueryRiskDecision {
    val fingerprint = assessment?.queryFingerprint.orEmpty()
    if (validationBlocked) {
        return QueryRiskDecision(
            fingerprint,
            RiskGateState.Blocked,
            userSetting,
            blockingBand(userSetting),
            listOf(
                RiskDecisionReason("validation_block", "Query validation blocks this query.", true)
            ),
        )
    }
    if (userSetting == QueryRiskGate.Disabled) {
        return QueryRiskDecision(fingerprint, RiskGateState.Allowed, userSetting, null, emptyList())
    }
    if (assessment == null) {
        return QueryRiskDecision(
            fingerprint,
            RiskGateState.AssessmentPending,
            userSetting,
            blockingBand(userSetting),
            listOf(RiskDecisionReason("assessment_pending", "Query risk assessment is pending.")),
        )
    }
    val mandatory = assessment.signals.filter(RiskSignal::mandatoryBlockWhenGateEnabled)
    val band = blockingBand(userSetting)!!
    val blocked = mandatory.isNotEmpty() || assessment.severity.ordinal >= band.ordinal
    val reasons =
        if (blocked) {
            assessment.signals
                .sortedWith(
                    compareByDescending<RiskSignal> { it.mandatoryBlockWhenGateEnabled }
                        .thenByDescending { it.confidence.ordinal.let { ordinal -> -ordinal } }
                        .thenByDescending(RiskSignal::points)
                )
                .take(3)
                .map { signal ->
                    RiskDecisionReason(
                        signal.code.name,
                        signalMessage(signal),
                        signal.mandatoryBlockWhenGateEnabled,
                    )
                }
                .ifEmpty {
                    listOf(
                        RiskDecisionReason(
                            "severity_gate",
                            "Query risk is ${assessment.severity.label}.",
                        )
                    )
                }
        } else {
            emptyList()
        }
    return QueryRiskDecision(
        fingerprint,
        if (blocked) RiskGateState.Blocked else RiskGateState.Allowed,
        userSetting,
        band,
        reasons,
    )
}

fun blockingBand(gate: QueryRiskGate): QueryRiskSeverity? =
    when (gate) {
        QueryRiskGate.Cautious -> QueryRiskSeverity.Elevated
        QueryRiskGate.Standard -> QueryRiskSeverity.High
        QueryRiskGate.Flexible -> QueryRiskSeverity.VeryHigh
        QueryRiskGate.Disabled -> null
    }
