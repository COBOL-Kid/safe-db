package com.safedb.model

sealed class ExplainResult {
    data class Available(val plan: NormalizedQueryPlan) : ExplainResult()
    data class Unavailable(
        val reasonCode: PlanUnavailableReason,
        val message: String,
    ) : ExplainResult()
}
