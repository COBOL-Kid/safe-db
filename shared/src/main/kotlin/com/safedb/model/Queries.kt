package com.safedb.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SavedQuery(
    val id: String,
    val name: String,
    @SerialName("connection_id") val connectionId: String,
    val spec: QuerySpec,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class HistoryEntry(
    val id: String,
    @SerialName("connection_id") val connectionId: String,
    @SerialName("connection_name") val connectionName: String,
    val spec: QuerySpec,
    @SerialName("row_count") val rowCount: Int,
    val warnings: List<String>,
    val error: String? = null,
    val timestamp: String,
    @SerialName("risk_score_version") val riskScoreVersion: Int? = null,
    @SerialName("risk_static_score") val riskStaticScore: Int? = null,
    @SerialName("risk_final_score") val riskFinalScore: Int? = null,
    @SerialName("risk_severity") val riskSeverity: String? = null,
    @SerialName("risk_signal_codes") val riskSignalCodes: List<String> = emptyList(),
    @SerialName("risk_uncertainty_codes") val riskUncertaintyCodes: List<String> = emptyList(),
    @SerialName("risk_plan_status") val riskPlanStatus: String? = null,
    @SerialName("risk_plan_reason") val riskPlanReason: String? = null,
    @SerialName("risk_gate_state") val riskGateState: String? = null,
    @SerialName("risk_optimizer_cost") val riskOptimizerCost: Double? = null,
    @SerialName("risk_optimizer_cost_threshold") val riskOptimizerCostThreshold: Double? = null,
    @SerialName("risk_confirmation_codes") val riskConfirmationCodes: List<String> = emptyList(),
    @SerialName("risk_confirmation_accepted") val riskConfirmationAccepted: Boolean? = null,
)
