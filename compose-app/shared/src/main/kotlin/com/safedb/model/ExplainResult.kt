package com.safedb.model

sealed class ExplainResult {
    data class Estimated(val cost: Double) : ExplainResult()
    data class Unavailable(val reason: String) : ExplainResult()
}
