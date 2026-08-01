package com.safedb

import com.safedb.service.SafeDbService
import com.safedb.model.ConnectionDef
import com.safedb.model.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AppRoute {
    Home,
    Connections,
    Builder,
    History,
}

enum class ActiveConnectionOrigin {
    Default,
    Explicit,
}

internal data class DefaultQueryLocation(
    val connectionId: String,
    val schema: String,
)

internal fun resolveDefaultQueryLocation(
    settings: Settings,
    connections: List<ConnectionDef>,
): DefaultQueryLocation? {
    val connectionId = settings.defaultConnectionId ?: return null
    val schema = settings.defaultSchema ?: return null
    if (connections.none { it.id == connectionId }) return null
    return DefaultQueryLocation(connectionId, schema)
}

class AppState(
    val service: SafeDbService,
) {
    private val _route = MutableStateFlow(AppRoute.Home)
    val route: StateFlow<AppRoute> = _route.asStateFlow()

    private val _activeConnectionId = MutableStateFlow<String?>(null)
    val activeConnectionId: StateFlow<String?> = _activeConnectionId.asStateFlow()

    private val _preferredSchema = MutableStateFlow<String?>(null)
    val preferredSchema: StateFlow<String?> = _preferredSchema.asStateFlow()

    private val _activeConnectionOrigin = MutableStateFlow<ActiveConnectionOrigin?>(null)
    val activeConnectionOrigin: StateFlow<ActiveConnectionOrigin?> = _activeConnectionOrigin.asStateFlow()

    private val _settingsOpen = MutableStateFlow(false)
    val settingsOpen: StateFlow<Boolean> = _settingsOpen.asStateFlow()

    fun navigate(route: AppRoute) {
        _route.value = route
    }

    fun setActiveConnection(id: String?, preferredSchema: String? = null) {
        _activeConnectionId.value = id
        _preferredSchema.value = if (id == null) null else preferredSchema
        _activeConnectionOrigin.value = if (id == null) null else ActiveConnectionOrigin.Explicit
    }

    fun activateDefaultConnection(id: String, schema: String) {
        if (_activeConnectionOrigin.value == ActiveConnectionOrigin.Explicit) return
        _activeConnectionId.value = id
        _preferredSchema.value = schema
        _activeConnectionOrigin.value = ActiveConnectionOrigin.Default
    }

    fun clearDefaultConnection() {
        if (_activeConnectionOrigin.value != ActiveConnectionOrigin.Default) return
        _activeConnectionId.value = null
        _preferredSchema.value = null
        _activeConnectionOrigin.value = null
    }

    fun clearActiveConnectionIf(id: String) {
        if (_activeConnectionId.value != id) return
        _activeConnectionId.value = null
        _preferredSchema.value = null
        _activeConnectionOrigin.value = null
    }

    fun openSettings() {
        _settingsOpen.value = true
    }

    fun closeSettings() {
        _settingsOpen.value = false
    }
}
