package com.safedb.service

import com.safedb.explore.ExploreRecipe
import com.safedb.model.ConnectionDef
import com.safedb.model.HistoryEntry
import com.safedb.model.SavedQuery
import com.safedb.model.Schema
import com.safedb.model.Settings

open class FakeSafeDbServiceSupport : SafeDbService {
    override suspend fun testConnection(def: ConnectionDef, password: String?): String {
        throw UnsupportedOperationException("testConnection")
    }

    override suspend fun createConnection(def: ConnectionDef, password: String): ConnectionDef {
        throw UnsupportedOperationException("createConnection")
    }

    override suspend fun updateConnection(def: ConnectionDef, password: String?) {
        throw UnsupportedOperationException("updateConnection")
    }

    override suspend fun listConnections(): List<ConnectionDef> = emptyList()

    override suspend fun deleteConnection(id: String) {
        throw UnsupportedOperationException("deleteConnection")
    }

    override suspend fun lockCredentials() = Unit

    override suspend fun getSchema(connectionId: String): Schema {
        throw UnsupportedOperationException("getSchema")
    }

    override suspend fun runQuery(request: QueryRunRequest): QueryRunResult {
        throw UnsupportedOperationException("runQuery")
    }

    override suspend fun listSavedQueries(): List<SavedQuery> = emptyList()

    override suspend fun saveSavedQuery(query: SavedQuery) = Unit

    override suspend fun deleteSavedQuery(id: String) = Unit

    override suspend fun listExploreRecipes(): List<ExploreRecipe> = emptyList()

    override suspend fun saveExploreRecipe(recipe: ExploreRecipe) = Unit

    override suspend fun deleteExploreRecipe(id: String) = Unit

    override suspend fun importExploreRecipe(json: String, nowEpochSec: String): ExploreRecipe {
        throw UnsupportedOperationException("Explore recipes are unavailable")
    }

    override suspend fun exportExploreRecipe(recipe: ExploreRecipe): String {
        throw UnsupportedOperationException("Explore recipes are unavailable")
    }

    override suspend fun listHistory(): List<HistoryEntry> = emptyList()

    override suspend fun clearHistory() = Unit

    override suspend fun getSettings(): Settings = Settings()

    override suspend fun saveSettings(settings: Settings) = Unit
}
