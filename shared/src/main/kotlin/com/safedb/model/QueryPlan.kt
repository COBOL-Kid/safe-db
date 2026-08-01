package com.safedb.model

/** Dialect-neutral optimizer evidence. Raw plans never leave the adapter layer. */
data class NormalizedQueryPlan(
    val relations: List<PlanRelationAccess> = emptyList(),
    val joins: List<PlanJoinEvidence> = emptyList(),
    val blockingOperations: List<PlanBlockingOperation> = emptyList(),
    val rawOptimizerCost: Double? = null,
)

enum class PlanAccessMethod {
    BoundedLookup,
    BoundedRange,
    FullIndexScan,
    TableScan,
    Unknown,
    Other,
}

data class PlanRelationAccess(
    val schema: String? = null,
    val table: String? = null,
    val alias: String? = null,
    val method: PlanAccessMethod,
    val estimatedRows: Long? = null,
    val specializedTextEvidence: Boolean = false,
)

data class PlanJoinEvidence(
    val aliases: Set<String> = emptySet(),
    val estimatedOutputRows: Long? = null,
)

enum class PlanOperationKind { Sort, Grouping, Distinct, Other }

data class PlanBlockingOperation(
    val kind: PlanOperationKind,
    val aliases: Set<String> = emptySet(),
    val estimatedRows: Long? = null,
)

enum class PlanUnavailableReason {
    PermissionDenied,
    TimedOut,
    ParseFailure,
    UnsupportedShape,
    CleanupFailure,
    ExecutionFailure,
}
