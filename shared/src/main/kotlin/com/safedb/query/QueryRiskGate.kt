package com.safedb.query

import com.safedb.model.Dialect
import com.safedb.model.Outcome
import com.safedb.model.QueryRiskGate
import com.safedb.model.QuerySpec
import com.safedb.model.Schema
import com.safedb.model.Settings

data class StaticQueryRisk(
    val validated: ValidatedQuery,
    val normalizedSpec: QuerySpec,
    val warnings: List<String>,
    // Null when the user disabled the gate, which also suppresses descriptive scoring.
    val assessment: QueryRiskAssessment?,
)

// Shared by the builder's risk preview and by execution gating so the two cannot disagree.
fun assessValidatedQueryRisk(
    spec: QuerySpec,
    schema: Schema,
    settings: Settings,
    dialect: Dialect,
): Outcome<StaticQueryRisk> {
    val (validated, outcome) =
        when (val result = validateQuery(spec, schema, settings.blockedSchemas, dialect)) {
            is Outcome.Ok -> result.value
            is Outcome.Err -> return Outcome.err(result.message)
        }
    return Outcome.ok(
        StaticQueryRisk(
            validated = validated,
            normalizedSpec = validated.spec(),
            warnings = outcome.warnings,
            assessment =
                if (settings.queryRiskGate == QueryRiskGate.Disabled) {
                    null
                } else {
                    assessStaticQueryRisk(validated, schema, dialect)
                },
        )
    )
}

fun evaluateQueryRisk(
    spec: QuerySpec,
    schema: Schema,
    settings: Settings,
    dialect: Dialect,
): Outcome<QueryRiskEvaluation> {
    val assessment =
        when (val result = assessValidatedQueryRisk(spec, schema, settings, dialect)) {
            is Outcome.Ok -> result.value.assessment
            is Outcome.Err -> return Outcome.err(result.message)
        }
    return Outcome.ok(
        QueryRiskEvaluation(
            staticAssessment = assessment,
            finalAssessment = assessment,
            planStatus =
                if (assessment == null) QueryPlanStatus.Disabled else QueryPlanStatus.NotRequested,
            decision = applyRiskGate(assessment, settings.queryRiskGate),
        )
    )
}

fun applyRiskGate(
    assessment: QueryRiskAssessment?,
    userSetting: QueryRiskGate,
): QueryRiskDecision {
    val fingerprint = assessment?.queryFingerprint.orEmpty()
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
                        // EvidenceConfidence declares High first, so ascending ordinal is
                        // descending confidence.
                        .thenBy { it.confidence.ordinal }
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
