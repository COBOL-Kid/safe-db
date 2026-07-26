package com.safedb.viewmodel

import com.safedb.explore.ExploreRecipe
import com.safedb.model.ConnectionDef
import com.safedb.model.HistoryEntry
import com.safedb.model.SavedQuery
import com.safedb.model.Schema
import com.safedb.model.Settings
import com.safedb.service.QueryRunRequest
import com.safedb.service.SafeDbService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

internal class DispatchingSafeDbService(
    private val delegate: SafeDbService,
    private val ioDispatcher: CoroutineDispatcher,
) : SafeDbService by delegate {
    override suspend fun testConnection(def: ConnectionDef, password: String) =
        onIo { delegate.testConnection(def, password) }

    override suspend fun saveConnection(def: ConnectionDef, password: String?) =
        onIo { delegate.saveConnection(def, password) }

    override suspend fun createConnection(def: ConnectionDef, password: String) =
        onIo { delegate.createConnection(def, password) }

    override suspend fun updateConnection(def: ConnectionDef, password: String?) =
        onIo { delegate.updateConnection(def, password) }

    override suspend fun listConnections(): List<ConnectionDef> = onIo(delegate::listConnections)
    override suspend fun deleteConnection(id: String) = onIo { delegate.deleteConnection(id) }
    override suspend fun lockCredentials() = onIo(delegate::lockCredentials)
    override suspend fun getSchema(connectionId: String): Schema = onIo { delegate.getSchema(connectionId) }
    override suspend fun runQuery(request: QueryRunRequest) = onIo { delegate.runQuery(request) }
    override suspend fun listSavedQueries(): List<SavedQuery> = onIo(delegate::listSavedQueries)
    override suspend fun saveSavedQuery(query: SavedQuery) = onIo { delegate.saveSavedQuery(query) }
    override suspend fun deleteSavedQuery(id: String) = onIo { delegate.deleteSavedQuery(id) }
    override suspend fun listExploreRecipes(): List<ExploreRecipe> = onIo(delegate::listExploreRecipes)
    override suspend fun saveExploreRecipe(recipe: ExploreRecipe) = onIo { delegate.saveExploreRecipe(recipe) }
    override suspend fun deleteExploreRecipe(id: String) = onIo { delegate.deleteExploreRecipe(id) }
    override suspend fun importExploreRecipe(json: String, nowEpochSec: String) =
        onIo { delegate.importExploreRecipe(json, nowEpochSec) }

    override suspend fun exportExploreRecipe(recipe: ExploreRecipe) =
        onIo { delegate.exportExploreRecipe(recipe) }

    override suspend fun listHistory(): List<HistoryEntry> = onIo(delegate::listHistory)
    override suspend fun clearHistory() = onIo(delegate::clearHistory)
    override suspend fun getSettings(): Settings = onIo(delegate::getSettings)
    override suspend fun saveSettings(settings: Settings) = onIo { delegate.saveSettings(settings) }

    private suspend fun <T> onIo(block: suspend () -> T): T = withContext(ioDispatcher) { block() }
}
