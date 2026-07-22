package com.safedb.viewmodel

import com.safedb.model.ConnectionDef
import com.safedb.model.QueryResult
import com.safedb.model.HistoryEntry
import com.safedb.model.QuerySpec
import com.safedb.model.SavedQuery
import com.safedb.model.Settings
import com.safedb.explore.ExploreRecipe
import com.safedb.explore.exploreSpecHash
import com.safedb.service.SafeDbService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AppViewModel(
    private val service: SafeDbService,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val connections = ConnectionsViewModel(service, scope)
    val settings = SettingsViewModel(service, scope)
    val savedQueries = SavedQueriesViewModel(service, scope)
    val history = HistoryViewModel(service, scope)
    val recipes = RecipesViewModel(service, scope)
    val query = QueryViewModel(service, scope)
    val schema = SchemaViewModel(service, scope)

    private val _explore = MutableStateFlow<ExploreViewModel?>(null)
    val explore: StateFlow<ExploreViewModel?> = _explore.asStateFlow()
    private val _pendingRecipeRun = MutableStateFlow<PendingRecipeRun?>(null)
    val pendingRecipeRun: StateFlow<PendingRecipeRun?> = _pendingRecipeRun.asStateFlow()

    private val _initialLoading = MutableStateFlow(true)
    val initialLoading: StateFlow<Boolean> = _initialLoading.asStateFlow()

    init {
        scope.launch {
            try {
                awaitAll(
                    async { settings.load() },
                    async { connections.load() },
                    async { savedQueries.load() },
                    async { history.load() },
                    async { recipes.load() },
                )
            } finally {
                _initialLoading.value = false
            }
        }
    }

    fun restoreQueryForConnection(
        connectionId: String,
        spec: QuerySpec,
        onComplete: (Boolean) -> Unit = {},
    ) {
        schema.clear()
        schema.load(connectionId) { loaded ->
            if (loaded) {
                query.restoreFromSpec(spec, schema.tables)
            }
            onComplete(loaded)
        }
    }

    fun lockCredentials() {
        scope.launch {
            service.lockCredentials()
        }
    }

    fun openExplore(connection: ConnectionDef, spec: QuerySpec, sample: QueryResult) {
        _explore.value = ExploreViewModel(createExploreSession(connection, spec, sample))
    }

    fun openExploreRecipe(connection: ConnectionDef, spec: QuerySpec, sample: QueryResult, recipe: ExploreRecipe) {
        _explore.value = ExploreViewModel(createExploreSession(connection, spec, sample)).also { it.requestRecipe(recipe) }
    }

    fun runRecipe(connection: ConnectionDef, recipe: ExploreRecipe) {
        val spec = recipe.querySpec ?: return
        restoreQueryForConnection(connection.id, spec) { restored ->
            if (!restored) return@restoreQueryForConnection
            _pendingRecipeRun.value = PendingRecipeRun(recipe, connection.id, exploreSpecHash(query.spec))
            query.run(connection.id)
        }
    }

    fun completePendingRecipeRun(connection: ConnectionDef, sample: QueryResult, spec: QuerySpec) {
        val pending = _pendingRecipeRun.value ?: return
        if (pending.connectionId != connection.id || pending.specHash != exploreSpecHash(spec)) return
        _pendingRecipeRun.value = null
        openExploreRecipe(connection, spec, sample, pending.recipe)
    }

    fun cancelPendingRecipeRun() {
        _pendingRecipeRun.value = null
    }

    fun refreshExploreSample(connection: ConnectionDef, spec: QuerySpec, sample: QueryResult) {
        val current = _explore.value ?: return
        if (current.session.connectionId != connection.id) return
        _explore.value = ExploreViewModel(
            session = createExploreSession(connection, spec, sample),
            initialWorkspace = current.workspace,
        ).also { it.inheritRecipeTrackingFrom(current) }
    }

    fun closeExplore() {
        _explore.value = null
    }
}

data class PendingRecipeRun(
    val recipe: ExploreRecipe,
    val connectionId: String,
    val specHash: String,
)
