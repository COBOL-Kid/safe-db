package com.safedb.query

import com.safedb.model.EvidenceConfidence
import com.safedb.model.PlanOperationKind
import com.safedb.model.PlanUnavailableReason
import com.safedb.model.QueryRiskGate

const val QUERY_RISK_SCORE_VERSION: Int = 2

enum class RiskCategory {
    Access,
    Joins,
    Operations,
    Volume,
}

enum class RiskSignalCode {
    NoEffectiveRestriction,
    NoKnownCompatibleAccessPath,
    ScanProneTextPredicate,
    ScanProneNegativePredicate,
    OrBranchWithoutCompatiblePath,
    AdditionalJoinedRelation,
    ForeignKeyWithoutSupportingIndex,
    JoinExpansionPossible,
    LimitCannotBoundWork,
    BoundedBlockingOperation,
    MaterialProjectedPayload,
    HighProjectedPayload,
    PlanConfirmedLargeScan,
    PlanConfirmedJoinExpansion,
}

enum class SignalBasis {
    StaticSchema,
    PlanEvidence,
}

enum class AccessRiskKind {
    General,
    Text,
}

sealed interface RiskTarget {
    data class Access(val alias: String, val kind: AccessRiskKind = AccessRiskKind.General) :
        RiskTarget

    data class Join(val aliases: Set<String>) : RiskTarget {
        fun displayName(): String = "join " + aliases.sorted().joinToString("-")
    }

    data class Operation(val kind: PlanOperationKind, val aliases: Set<String>) : RiskTarget
}

data class RiskSubject(
    val tableAlias: String? = null,
    val schema: String? = null,
    val table: String? = null,
    val column: String? = null,
    val operation: String? = null,
) {
    fun displayName(): String =
        when {
            table != null && column != null -> "$table.$column"
            table != null -> table
            operation != null -> operation
            tableAlias != null -> tableAlias
            else -> "query"
        }
}

data class RiskSignal(
    val code: RiskSignalCode,
    val category: RiskCategory,
    val subject: RiskSubject,
    val points: Int,
    val basis: SignalBasis,
    val confidence: EvidenceConfidence,
    val target: RiskTarget? = null,
    val mandatoryBlockWhenGateEnabled: Boolean = false,
)

data class RiskUncertainty(val code: String, val subject: RiskSubject, val reasonCode: String)

enum class QueryRiskSeverity(val label: String) {
    Minimal("Minimal concern"),
    Elevated("Elevated concern"),
    High("High concern"),
    VeryHigh("Very high concern"),
}

data class QueryRiskAssessment(
    val scoreVersion: Int,
    val queryFingerprint: String,
    val score: Int,
    val severity: QueryRiskSeverity,
    val categoryScores: Map<RiskCategory, Int>,
    val signals: List<RiskSignal>,
    val uncertainties: List<RiskUncertainty>,
)

enum class RiskGateState {
    Allowed,
    AssessmentPending,
    ConfirmationRequired,
    Blocked,
}

data class RiskDecisionReason(val code: String, val message: String, val mandatory: Boolean = false)

data class QueryRiskDecision(
    val queryFingerprint: String,
    val state: RiskGateState,
    val effectiveGate: QueryRiskGate,
    val blockingBand: QueryRiskSeverity?,
    val reasons: List<RiskDecisionReason>,
)

enum class QueryConfirmationReasonCode {
    PlanUnavailable,
    OptimizerCostUnavailable,
}

data class QueryConfirmationCondition(
    val reasonCode: QueryConfirmationReasonCode,
    // Excludes optimizer observations that can vary between retries.
    val conditionKey: String,
)

data class QueryExecutionConfirmation(
    val connectionId: String,
    val connectionFingerprint: String,
    val queryFingerprint: String,
    val conditions: Set<QueryConfirmationCondition>,
) {
    val reasonCodes: Set<QueryConfirmationReasonCode>
        get() = conditions.mapTo(linkedSetOf(), QueryConfirmationCondition::reasonCode)
}

data class QueryConfirmationRequirement(
    val confirmation: QueryExecutionConfirmation,
    val reasons: List<RiskDecisionReason>,
)

enum class QueryPlanStatus {
    NotRequested,
    Available,
    Incomplete,
    Unavailable,
    Disabled,
}

data class QueryRiskEvaluation(
    val staticAssessment: QueryRiskAssessment?,
    val finalAssessment: QueryRiskAssessment?,
    val planStatus: QueryPlanStatus,
    val planUnavailableReason: PlanUnavailableReason? = null,
    val decision: QueryRiskDecision,
    val optimizerCost: Double? = null,
    val confirmationRequirement: QueryConfirmationRequirement? = null,
    val confirmationAccepted: Boolean = false,
)
