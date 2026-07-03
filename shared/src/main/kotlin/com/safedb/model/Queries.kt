package com.safedb.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SavedQuery(
    val id: String,
    val name: String,
    @SerialName("connection_id")
    val connectionId: String,
    val spec: QuerySpec,
    @SerialName("created_at")
    val createdAt: String,
)

@Serializable
data class HistoryEntry(
    val id: String,
    @SerialName("connection_id")
    val connectionId: String,
    @SerialName("connection_name")
    val connectionName: String,
    val spec: QuerySpec,
    @SerialName("row_count")
    val rowCount: Int,
    val warnings: List<String>,
    val error: String? = null,
    val timestamp: String,
)
