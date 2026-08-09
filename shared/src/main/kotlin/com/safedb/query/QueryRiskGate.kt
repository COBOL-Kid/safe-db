package com.safedb.query

import com.safedb.model.QueryRiskGate

fun applyRiskGate(
    assessment: QueryRiskAssessment?,
    userSetting: QueryRiskGate,
    validationBlocked: Boolean = false,
): QueryRiskDecision {
    val effective = userSetting
    val fingerprint = assessment?.queryFingerprint.orEmpty()
    if (validationBlocked) {
        return QueryRiskDecision(
            fingerprint,
            RiskGateState.Blocked,
            effective,
            blockingBand(effective),
            listOf(
                RiskDecisionReason("validation_block", "Query validation blocks this query.", true)
            ),
        )
    }
    if (effective == QueryRiskGate.Disabled) {
        return QueryRiskDecision(fingerprint, RiskGateState.Allowed, effective, null, emptyList())
    }
    if (assessment == null) {
        return QueryRiskDecision(
            fingerprint,
            RiskGateState.AssessmentPending,
            effective,
            blockingBand(effective),
            listOf(RiskDecisionReason("assessment_pending", "Query risk assessment is pending.")),
        )
    }
    val mandatory = assessment.signals.filter(RiskSignal::mandatoryBlockWhenGateEnabled)
    val band = blockingBand(effective)!!
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
        effective,
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
