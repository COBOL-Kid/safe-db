package com.safedb

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

class AppState(
    val service: SafeDbService,
) {
    private val _route = MutableStateFlow(AppRoute.Home)
    val route: StateFlow<AppRoute> = _route.asStateFlow()

    private val _activeConnectionId = MutableStateFlow<String?>(null)
    val activeConnectionId: StateFlow<String?> = _activeConnectionId.asStateFlow()

    private val _settingsOpen = MutableStateFlow(false)
    val settingsOpen: StateFlow<Boolean> = _settingsOpen.asStateFlow()

    fun navigate(route: AppRoute) {
        _route.value = route
    }

    fun setActiveConnection(id: String?) {
        _activeConnectionId.value = id
    }

    fun openSettings() {
        _settingsOpen.value = true
    }

    fun closeSettings() {
        _settingsOpen.value = false
    }
}
