package com.safedb.viewmodel

import com.safedb.model.ConnectionDef
import com.safedb.model.QueryResult
import com.safedb.model.HistoryEntry
import com.safedb.model.QuerySpec
import com.safedb.model.SavedQuery
import com.safedb.model.Settings
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
    val query = QueryViewModel(service, scope)
    val schema = SchemaViewModel(service, scope)

    private val _explore = MutableStateFlow<ExploreViewModel?>(null)
    val explore: StateFlow<ExploreViewModel?> = _explore.asStateFlow()

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

    fun refreshExploreSample(connection: ConnectionDef, spec: QuerySpec, sample: QueryResult) {
        val current = _explore.value ?: return
        if (current.session.connectionId != connection.id) return
        _explore.value = ExploreViewModel(
            session = createExploreSession(connection, spec, sample),
            initialConfig = current.config,
        )
    }

    fun closeExplore() {
        _explore.value = null
    }
}
