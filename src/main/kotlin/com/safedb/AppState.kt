package com.safedb

import com.safedb.model.ConnectionDef
import com.safedb.model.QuerySpec
import com.safedb.model.Settings
import com.safedb.service.SafeDbService
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

internal enum class SchemaSelectionSource {
    StartupDefault,
    ConnectionHistory,
    RestoredQuery,
    User,
}

internal data class SchemaSelectionIntent(
    val schema: String? = null,
    val source: SchemaSelectionSource? = null,
) {
    init {
        require((schema == null) == (source == null)) {
            "Schema and selection source must either both be present or both be absent"
        }
    }

    companion object {
        val Unselected = SchemaSelectionIntent()
    }
}

internal data class DefaultQueryLocation(val connectionId: String, val schema: String)

internal fun resolveDefaultQueryLocation(
    settings: Settings,
    connections: List<ConnectionDef>,
): DefaultQueryLocation? {
    val connectionId = settings.defaultConnectionId ?: return null
    val schema = settings.defaultSchema ?: return null
    if (connections.none { it.id == connectionId }) return null
    return DefaultQueryLocation(connectionId, schema)
}

internal fun resolveConnectionSchemaSelection(
    connectionId: String,
    settings: Settings,
): SchemaSelectionIntent =
    settings.lastSelectedSchemas[connectionId]?.let { schema ->
        SchemaSelectionIntent(schema, SchemaSelectionSource.ConnectionHistory)
    } ?: SchemaSelectionIntent.Unselected

internal fun resolveQuerySchemaSelection(spec: QuerySpec): SchemaSelectionIntent =
    spec.tables.firstOrNull()?.schema?.let { schema ->
        SchemaSelectionIntent(schema, SchemaSelectionSource.RestoredQuery)
    } ?: SchemaSelectionIntent.Unselected

class AppState(val service: SafeDbService) {
    private val _route = MutableStateFlow(AppRoute.Home)
    val route: StateFlow<AppRoute> = _route.asStateFlow()

    private val _activeConnectionId = MutableStateFlow<String?>(null)
    val activeConnectionId: StateFlow<String?> = _activeConnectionId.asStateFlow()

    private val _schemaSelection = MutableStateFlow(SchemaSelectionIntent.Unselected)
    internal val schemaSelection: StateFlow<SchemaSelectionIntent> = _schemaSelection.asStateFlow()

    private val _activeConnectionOrigin = MutableStateFlow<ActiveConnectionOrigin?>(null)
    val activeConnectionOrigin: StateFlow<ActiveConnectionOrigin?> =
        _activeConnectionOrigin.asStateFlow()

    private val _settingsOpen = MutableStateFlow(false)
    val settingsOpen: StateFlow<Boolean> = _settingsOpen.asStateFlow()

    fun navigate(route: AppRoute) {
        _route.value = route
    }

    internal fun setActiveConnection(
        id: String?,
        schemaSelection: SchemaSelectionIntent = SchemaSelectionIntent.Unselected,
    ) {
        _activeConnectionId.value = id
        _schemaSelection.value =
            if (id == null) SchemaSelectionIntent.Unselected else schemaSelection
        _activeConnectionOrigin.value = if (id == null) null else ActiveConnectionOrigin.Explicit
    }

    internal fun setActiveSchema(schema: String) {
        if (_activeConnectionId.value == null) return
        _schemaSelection.value = SchemaSelectionIntent(schema, SchemaSelectionSource.User)
    }

    fun activateDefaultConnection(id: String, schema: String) {
        if (_activeConnectionOrigin.value == ActiveConnectionOrigin.Explicit) return
        _activeConnectionId.value = id
        _schemaSelection.value = SchemaSelectionIntent(schema, SchemaSelectionSource.StartupDefault)
        _activeConnectionOrigin.value = ActiveConnectionOrigin.Default
    }

    fun clearDefaultConnection() {
        if (_activeConnectionOrigin.value != ActiveConnectionOrigin.Default) return
        _activeConnectionId.value = null
        _schemaSelection.value = SchemaSelectionIntent.Unselected
        _activeConnectionOrigin.value = null
    }

    fun clearActiveConnectionIf(id: String) {
        if (_activeConnectionId.value != id) return
        _activeConnectionId.value = null
        _schemaSelection.value = SchemaSelectionIntent.Unselected
        _activeConnectionOrigin.value = null
    }

    fun openSettings() {
        _settingsOpen.value = true
    }

    fun closeSettings() {
        _settingsOpen.value = false
    }
}
