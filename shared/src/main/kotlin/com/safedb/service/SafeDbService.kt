package com.safedb.service

import com.safedb.explore.ExploreRecipe
import com.safedb.model.ConnectionDef
import com.safedb.model.HistoryEntry
import com.safedb.model.QueryResult
import com.safedb.model.QuerySpec
import com.safedb.model.ResultColumn
import com.safedb.model.SavedQuery
import com.safedb.model.Schema
import com.safedb.model.Settings
import com.safedb.query.QueryCoreError
import com.safedb.query.QueryError
import com.safedb.query.QueryExecutionConfirmation
import com.safedb.query.QueryRiskEvaluation

fun interface QueryHistoryRecording {
    fun shouldRecord(): Boolean

    companion object {
        val Always: QueryHistoryRecording = QueryHistoryRecording { true }
    }
}

data class QueryRunRequest(
    val connectionId: String,
    val spec: QuerySpec,
    val confirmation: QueryExecutionConfirmation? = null,
    val historyRecording: QueryHistoryRecording = QueryHistoryRecording.Always,
)

data class QueryRunResult(val queryResult: QueryResult, val riskEvaluation: QueryRiskEvaluation)

data class ResolvedQueryRun(
    val connection: ConnectionDef,
    val spec: QuerySpec,
    val result: QueryRunResult,
)

data class QueryAssessmentResult(
    val normalizedSpec: QuerySpec,
    val columns: List<ResultColumn>,
    val compiledSql: String,
    val parameterCount: Int,
    val warnings: List<String>,
    val riskEvaluation: QueryRiskEvaluation,
)

data class ResolvedQueryAssessment(
    val connection: ConnectionDef,
    val spec: QuerySpec,
    val assessment: QueryAssessmentResult,
)

class QueryFailureException(val queryError: QueryError, val warnings: List<String> = emptyList()) :
    IllegalArgumentException(queryError.message)

class ConnectionNotFoundException : IllegalArgumentException("Connection not found")

internal fun QueryFailureException(coreError: QueryCoreError): QueryFailureException =
    QueryFailureException(coreError.error, coreError.warnings)

interface SafeDbService {
    suspend fun testConnection(def: ConnectionDef, password: String?): String

    suspend fun createConnection(def: ConnectionDef, password: String): ConnectionDef

    suspend fun updateConnection(def: ConnectionDef, password: String?)

    suspend fun listConnections(): List<ConnectionDef>

    suspend fun deleteConnection(id: String)

    suspend fun lockCredentials()

    suspend fun getSchema(connectionId: String): Schema

    suspend fun runQuery(request: QueryRunRequest): QueryRunResult

    suspend fun runQueryWithSchema(
        connectionId: String,
        confirmation: QueryExecutionConfirmation? = null,
        resolveSpec: (ConnectionDef, Schema, Settings) -> QuerySpec,
    ): ResolvedQueryRun

    suspend fun assessQuery(request: QueryRunRequest): QueryAssessmentResult

    suspend fun assessQueryWithSchema(
        connectionId: String,
        resolveSpec: (ConnectionDef, Schema, Settings) -> QuerySpec,
    ): ResolvedQueryAssessment

    suspend fun listSavedQueries(): List<SavedQuery>

    suspend fun saveSavedQuery(query: SavedQuery)

    suspend fun deleteSavedQuery(id: String)

    suspend fun listExploreRecipes(): List<ExploreRecipe>

    suspend fun saveExploreRecipe(recipe: ExploreRecipe)

    suspend fun deleteExploreRecipe(id: String)

    suspend fun importExploreRecipe(json: String, nowEpochSec: String): ExploreRecipe

    suspend fun exportExploreRecipe(recipe: ExploreRecipe): String

    suspend fun listHistory(): List<HistoryEntry>

    suspend fun clearHistory()

    suspend fun deleteHistoryEntry(id: String)

    suspend fun getSettings(): Settings

    suspend fun saveSettings(settings: Settings)
}
