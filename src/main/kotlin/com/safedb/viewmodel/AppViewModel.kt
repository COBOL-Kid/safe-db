package com.safedb.viewmodel

import com.safedb.SchemaSelectionIntent
import com.safedb.explore.ExploreRecipe
import com.safedb.explore.exploreSpecHash
import com.safedb.model.ConnectionDef
import com.safedb.model.QueryResult
import com.safedb.model.QuerySpec
import com.safedb.resolveQuerySchemaSelection
import com.safedb.service.SafeDbService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AppViewModel(
    service: SafeDbService,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val service = DispatchingSafeDbService(service, ioDispatcher)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val connections = ConnectionsViewModel(service, scope)
    val settings = SettingsViewModel(service, scope)
    val savedQueries = SavedQueriesViewModel(service, scope)
    val history = HistoryViewModel(service, scope)
    val recipes = RecipesViewModel(service, scope, ioDispatcher)
    val query = QueryViewModel(service, scope)
    val sqlEditor = SqlEditorViewModel(service, scope)
    val schema = SchemaViewModel(service, scope)
    internal val schemaMap = SchemaMapViewModel()

    private val _explore = MutableStateFlow<ExploreViewModel?>(null)
    val explore: StateFlow<ExploreViewModel?> = _explore.asStateFlow()
    private val _exploreOrigin = MutableStateFlow(ExploreOrigin.Builder)
    val exploreOrigin: StateFlow<ExploreOrigin> = _exploreOrigin.asStateFlow()
    private val _pendingRecipeRun = MutableStateFlow<PendingRecipeRun?>(null)
    val pendingRecipeRun: StateFlow<PendingRecipeRun?> = _pendingRecipeRun.asStateFlow()
    private val _recipeApplyNotice = MutableStateFlow<String?>(null)
    val recipeApplyNotice: StateFlow<String?> = _recipeApplyNotice.asStateFlow()

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

    internal fun restoreQueryForConnection(
        connectionId: String,
        spec: QuerySpec,
        selection: SchemaSelectionIntent = resolveQuerySchemaSelection(spec),
        onComplete: (Boolean) -> Unit = {},
    ) {
        // SchemaViewModel.load early-returns when this connection is already loaded; clear() would
        // bump requestGeneration and force a redundant introspection.
        if (schema.loadedConnectionId != connectionId || schema.schema == null) {
            schema.clear()
        }
        schema.load(connectionId, selection = selection) { loaded ->
            if (loaded) {
                query.restoreFromSpec(spec, schema.tables)
            }
            onComplete(loaded)
        }
    }

    fun lockCredentials() {
        scope.launch { service.lockCredentials() }
    }

    fun openExplore(
        connection: ConnectionDef,
        spec: QuerySpec,
        sample: QueryResult,
        origin: ExploreOrigin = ExploreOrigin.Builder,
    ) {
        _explore.value?.close()
        _exploreOrigin.value = origin
        _explore.value =
            ExploreViewModel(
                createExploreSession(connection, spec, sample),
                computationScope = scope,
                ioDispatcher = ioDispatcher,
            )
    }

    fun openExploreRecipe(
        connection: ConnectionDef,
        spec: QuerySpec,
        sample: QueryResult,
        recipe: ExploreRecipe,
    ) {
        _explore.value?.close()
        // Recipes replay through the builder pipeline, so the session refreshes from the builder.
        _exploreOrigin.value = ExploreOrigin.Builder
        _explore.value =
            ExploreViewModel(
                    createExploreSession(connection, spec, sample),
                    computationScope = scope,
                    ioDispatcher = ioDispatcher,
                )
                .also { it.requestRecipe(recipe) }
    }

    fun runRecipe(
        connection: ConnectionDef,
        recipe: ExploreRecipe,
        onRestored: () -> Unit = {},
    ) {
        _pendingRecipeRun.value = null
        _recipeApplyNotice.value = null
        val spec = recipe.querySpec ?: return
        restoreQueryForConnection(connection.id, spec) { restored ->
            if (!restored) {
                _pendingRecipeRun.value = null
                return@restoreQueryForConnection
            }
            onRestored()
            // The SQL editor holds the same app-wide slot, so a run or confirmation there
            // blocks this one too.
            if (!query.canRun || sqlEditor.occupiesQuerySlot) {
                if (query.occupiesQuerySlot || sqlEditor.occupiesQuerySlot) {
                    _recipeApplyNotice.value = RECIPE_APPLY_BUSY_NOTICE
                }
                _pendingRecipeRun.value = null
                return@restoreQueryForConnection
            }
            _recipeApplyNotice.value = null
            _pendingRecipeRun.value =
                PendingRecipeRun(recipe, connection.id, exploreSpecHash(query.spec))
            query.run(connection.id) { succeeded -> if (!succeeded) _pendingRecipeRun.value = null }
        }
    }

    fun dismissRecipeApplyNotice() {
        _recipeApplyNotice.value = null
    }

    // Spec-hash checks keep an Explore window from opening for a query other than the one the recipe
    // asked for.
    fun onQuerySettled(activeConnectionId: String?, connections: List<ConnectionDef>) {
        val pending = _pendingRecipeRun.value ?: return
        if (pending.connectionId != activeConnectionId) {
            _pendingRecipeRun.value = null
            return
        }
        val activeConnection = connections.firstOrNull { it.id == pending.connectionId }
        val sample = query.currentSample(pending.connectionId)
        when {
            activeConnection != null && sample != null -> {
                if (pending.specHash != exploreSpecHash(sample.spec)) return
                _pendingRecipeRun.value = null
                openExploreRecipe(activeConnection, sample.spec, sample.result, pending.recipe)
            }
            exploreSpecHash(query.spec) != pending.specHash -> _pendingRecipeRun.value = null
            !query.running && query.error != null -> _pendingRecipeRun.value = null
        }
    }

    fun refreshExploreSample(connection: ConnectionDef, spec: QuerySpec, sample: QueryResult) {
        val current = _explore.value ?: return
        if (current.session.connectionId != connection.id) return
        current.close()
        _explore.value =
            ExploreViewModel(
                    session = createExploreSession(connection, spec, sample),
                    initialWorkspace = current.workspace,
                    computationScope = scope,
                    ioDispatcher = ioDispatcher,
                )
                .also { it.inheritRecipeTrackingFrom(current) }
    }

    fun closeExplore() {
        _explore.value?.close()
        _explore.value = null
    }

    fun close() {
        closeExplore()
        scope.cancel()
    }
}

enum class ExploreOrigin {
    Builder,
    Sql,
}

data class PendingRecipeRun(
    val recipe: ExploreRecipe,
    val connectionId: String,
    val specHash: String,
)

private const val RECIPE_APPLY_BUSY_NOTICE =
    "The recipe is on the canvas but was not run because a query is already running or waiting for confirmation. Wait, then press Run."
