package com.safedb.service

import com.safedb.explore.ExploreRecipe
import com.safedb.model.ConnectionDef
import com.safedb.model.HistoryEntry
import com.safedb.model.QueryResult
import com.safedb.model.QuerySpec
import com.safedb.model.SavedQuery
import com.safedb.model.Schema
import com.safedb.model.Settings
import com.safedb.query.QueryCoreError
import com.safedb.query.QueryError

data class QueryRunRequest(
    val connectionId: String,
    val spec: QuerySpec,
    val force: Boolean = false,
)

class QueryFailureException(
    val queryError: QueryError,
    val warnings: List<String> = emptyList(),
) : IllegalArgumentException(queryError.message)

internal fun QueryFailureException(coreError: QueryCoreError): QueryFailureException =
    QueryFailureException(coreError.error, coreError.warnings)

/** Desktop service surface used by the Compose UI. */
interface SafeDbService {
    suspend fun testConnection(def: ConnectionDef, password: String): String

    suspend fun createConnection(def: ConnectionDef, password: String): ConnectionDef

    suspend fun updateConnection(def: ConnectionDef, password: String?)

    suspend fun listConnections(): List<ConnectionDef>

    suspend fun deleteConnection(id: String)

    suspend fun lockCredentials()

    suspend fun getSchema(connectionId: String): Schema

    suspend fun runQuery(request: QueryRunRequest): QueryResult

    suspend fun listSavedQueries(): List<SavedQuery>

    suspend fun saveSavedQuery(query: SavedQuery)

    suspend fun deleteSavedQuery(id: String)

    // Small test and preview fakes do not all model local recipe persistence.
    suspend fun listExploreRecipes(): List<ExploreRecipe> = emptyList()

    suspend fun saveExploreRecipe(recipe: ExploreRecipe) = Unit

    suspend fun deleteExploreRecipe(id: String) = Unit

    suspend fun importExploreRecipe(json: String, nowEpochSec: String): ExploreRecipe =
        throw UnsupportedOperationException("Explore recipes are unavailable")

    suspend fun exportExploreRecipe(recipe: ExploreRecipe): String =
        throw UnsupportedOperationException("Explore recipes are unavailable")

    suspend fun listHistory(): List<HistoryEntry>

    suspend fun clearHistory()

    suspend fun getSettings(): Settings

    suspend fun saveSettings(settings: Settings)
}
