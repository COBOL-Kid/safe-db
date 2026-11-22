package com.safedb.viewmodel

import com.safedb.SchemaSelectionIntent
import com.safedb.explore.ExploreRecipe
import com.safedb.explore.exploreSpecHash
import com.safedb.model.ConnectionDef
import com.safedb.model.QueryResult
import com.safedb.model.QuerySpec
import com.safedb.resolveConnectionSchemaSelection
import com.safedb.resolveQuerySchemaSelection
import com.safedb.service.DispatchingSafeDbService
import com.safedb.service.SafeDbService
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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

    val connections =
        ConnectionsViewModel(
            service,
            scope,
            onCredentialsUpdated = ::onConnectionCredentialsUpdated,
        )
    val schema = SchemaViewModel(service, scope, onConnectionReachable = ::markConnectionReachable)
    val settings =
        SettingsViewModel(
            service,
            scope,
            cachedSchema = { id -> schema.schema.takeIf { schema.loadedConnectionId == id } },
        )
    val savedQueries = SavedQueriesViewModel(service, scope)
    val history = HistoryViewModel(service, scope)
    val recipes = RecipesViewModel(service, scope, ioDispatcher)
    val query =
        QueryViewModel(
            service,
            scope,
            onRunFinished = history::refresh,
            onConnectionReachable = ::markConnectionReachable,
        )
    val sqlEditor =
        SqlEditorViewModel(
            service,
            scope,
            onRunFinished = history::refresh,
            onConnectionReachable = ::markConnectionReachable,
        )
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

    private var reachabilityGeneration = 0
    private var reachabilityJob: Job? = null
    private var activeReachabilityConnection: ConnectionDef? = null
    private var windowActive = true
    private val _databaseReachability = MutableStateFlow(DatabaseReachability.Disconnected)
    val databaseReachability: StateFlow<DatabaseReachability> = _databaseReachability.asStateFlow()

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

    fun setWindowActive(active: Boolean) {
        if (windowActive == active) return
        windowActive = active
        if (!active) {
            reachabilityJob?.cancel()
            reachabilityJob = null
            // Drop an in-flight probe so deactivation keeps the last Connected/Checking value.
            ++reachabilityGeneration
            return
        }
        activeReachabilityConnection?.let { launchReachabilityLoop(it) }
    }

    private fun onConnectionCredentialsUpdated(connection: ConnectionDef) {
        if (activeReachabilityConnection?.id == connection.id) {
            refreshDatabaseReachability(connection)
        }
    }

    private fun markConnectionReachable(connectionId: String) {
        val active = activeReachabilityConnection ?: return
        if (active.id != connectionId) return
        reachabilityJob?.cancel()
        reachabilityJob = null
        ++reachabilityGeneration
        _databaseReachability.value = DatabaseReachability.Connected
        if (windowActive) {
            launchReachabilityLoop(active, initialDelay = ReachabilityPollInterval)
        }
    }

    fun refreshDatabaseReachability(connection: ConnectionDef?) {
        reachabilityJob?.cancel()
        reachabilityJob = null
        activeReachabilityConnection = connection
        if (connection == null) {
            ++reachabilityGeneration
            _databaseReachability.value = DatabaseReachability.Disconnected
            return
        }
        // Enter Checking on connection switch instead of flashing Disconnected before probing.
        _databaseReachability.value = DatabaseReachability.Checking
        if (!windowActive) {
            ++reachabilityGeneration
            return
        }
        launchReachabilityLoop(connection)
    }

    private fun launchReachabilityLoop(
        connection: ConnectionDef,
        initialDelay: Duration = Duration.ZERO,
    ) {
        reachabilityJob?.cancel()
        val generation = ++reachabilityGeneration
        reachabilityJob = scope.launch {
            if (initialDelay > Duration.ZERO) {
                delay(initialDelay)
            }
            while (generation == reachabilityGeneration) {
                val reachable =
                    try {
                        service.testConnection(connection, password = null)
                        true
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        false
                    }
                if (generation != reachabilityGeneration) return@launch
                // Repeat checks only publish the probe result; clearing first would flash red.
                _databaseReachability.value =
                    if (reachable) {
                        DatabaseReachability.Connected
                    } else {
                        DatabaseReachability.Disconnected
                    }
                delay(ReachabilityPollInterval)
            }
        }
    }

    fun openExplore(
        connection: ConnectionDef,
        sample: BuilderQuerySample,
        origin: ExploreOrigin = ExploreOrigin.Builder,
    ) = openExplore(connection, sample.spec, sample.result, origin, sample.runId)

    fun openExplore(
        connection: ConnectionDef,
        spec: QuerySpec,
        sample: QueryResult,
        origin: ExploreOrigin = ExploreOrigin.Builder,
        sampleRunId: Long? = null,
    ) {
        _explore.value?.close()
        _exploreOrigin.value = origin
        _explore.value =
            ExploreViewModel(
                createExploreSession(connection, spec, sample),
                computationScope = scope,
                ioDispatcher = ioDispatcher,
                sampleRunId = sampleRunId,
            )
    }

    fun openExploreRecipe(
        connection: ConnectionDef,
        sample: BuilderQuerySample,
        recipe: ExploreRecipe,
    ) = openExploreRecipe(connection, sample.spec, sample.result, recipe, sample.runId)

    fun openExploreRecipe(
        connection: ConnectionDef,
        spec: QuerySpec,
        sample: QueryResult,
        recipe: ExploreRecipe,
        sampleRunId: Long? = null,
    ) {
        _explore.value?.close()
        // Recipes replay through the builder pipeline, so the session refreshes from the builder.
        _exploreOrigin.value = ExploreOrigin.Builder
        _explore.value =
            ExploreViewModel(
                createExploreSession(connection, spec, sample),
                computationScope = scope,
                ioDispatcher = ioDispatcher,
                initialRecipe = recipe,
                sampleRunId = sampleRunId,
            )
    }

    internal fun exploreOriginContext(
        activeConnectionId: String?,
        sqlSpec: QuerySpec?,
        connections: List<ConnectionDef> = this.connections.connections.value,
        origin: ExploreOrigin = _exploreOrigin.value,
    ): ExploreOriginContext {
        val current = _explore.value
        val currentSpec =
            when (origin) {
                ExploreOrigin.Builder -> query.spec
                ExploreOrigin.Sql -> sqlSpec
            }
        val originSample =
            when (origin) {
                ExploreOrigin.Builder -> query.currentSample(activeConnectionId)
                ExploreOrigin.Sql -> sqlEditor.currentSample(activeConnectionId, sqlSpec)
            }
        val sampleRefreshEnabled =
            current != null &&
                activeConnectionId == current.session.connectionId &&
                originSample != null &&
                connections.any { it.id == activeConnectionId }
        return ExploreOriginContext(
            currentSpec = currentSpec,
            sampleRefreshEnabled = sampleRefreshEnabled,
            latestSampleRunId = originSample?.runId,
        )
    }

    fun refreshActiveExploreSample(
        activeConnectionId: String?,
        sqlSpec: QuerySpec?,
        connections: List<ConnectionDef> = this.connections.connections.value,
    ) {
        val current = _explore.value ?: return
        if (activeConnectionId != current.session.connectionId) return
        val connection = connections.firstOrNull { it.id == activeConnectionId } ?: return
        val latestSample =
            when (_exploreOrigin.value) {
                ExploreOrigin.Builder -> query.currentSample(activeConnectionId)
                ExploreOrigin.Sql -> sqlEditor.currentSample(activeConnectionId, sqlSpec)
            } ?: return
        refreshExploreSample(connection, latestSample)
    }

    internal fun runRecipeFromExplore(
        connection: ConnectionDef,
        recipe: ExploreRecipe,
        onSelectConnection: (SchemaSelectionIntent) -> Unit,
        onRestored: () -> Unit = {},
    ) {
        val selection =
            recipe.querySpec?.let(::resolveQuerySchemaSelection)
                ?: resolveConnectionSchemaSelection(connection.id, settings.settings.value)
        onSelectConnection(selection)
        closeExplore()
        runRecipe(connection, recipe) {
            // Builder reads VM confirmation after navigate.
            if (recipe.querySpec != null) {
                onRestored()
            }
        }
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

    // Spec-hash checks keep an Explore window from opening for a query other than the one the
    // recipe asked for.
    fun onQuerySettled(activeConnectionId: String?, connections: List<ConnectionDef>) {
        val pending = _pendingRecipeRun.value ?: return
        if (pending.connectionId != activeConnectionId) {
            _pendingRecipeRun.value = null
            return
        }
        if (exploreSpecHash(query.spec) != pending.specHash) {
            _pendingRecipeRun.value = null
            return
        }
        val activeConnection = connections.firstOrNull { it.id == pending.connectionId }
        val sample = query.currentSample(pending.connectionId)
        when {
            activeConnection != null && sample != null && !sample.stale -> {
                if (pending.specHash != exploreSpecHash(sample.spec)) return
                _pendingRecipeRun.value = null
                openExploreRecipe(activeConnection, sample, pending.recipe)
            }
            !query.running && query.error != null -> _pendingRecipeRun.value = null
        }
    }

    fun refreshExploreSample(connection: ConnectionDef, sample: BuilderQuerySample) =
        refreshExploreSample(connection, sample.spec, sample.result, sample.runId)

    fun refreshExploreSample(
        connection: ConnectionDef,
        spec: QuerySpec,
        sample: QueryResult,
        sampleRunId: Long? = null,
    ) {
        val current = _explore.value ?: return
        if (current.session.connectionId != connection.id) return
        current.close()
        _explore.value =
            ExploreViewModel(
                    session = createExploreSession(connection, spec, sample),
                    initialWorkspace = current.workspace,
                    computationScope = scope,
                    ioDispatcher = ioDispatcher,
                    sampleRunId = sampleRunId,
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

enum class DatabaseReachability {
    Disconnected,
    Checking,
    Connected,
}

enum class ExploreOrigin {
    Builder,
    Sql,
}

internal data class ExploreOriginContext(
    val currentSpec: QuerySpec?,
    val sampleRefreshEnabled: Boolean,
    val latestSampleRunId: Long? = null,
)

data class PendingRecipeRun(
    val recipe: ExploreRecipe,
    val connectionId: String,
    val specHash: String,
)

internal val ReachabilityPollInterval = 1.minutes

private const val RECIPE_APPLY_BUSY_NOTICE =
    "The recipe is on the canvas but was not run because a query is already running or waiting for confirmation. Wait, then press Run."
