package com.safedb.viewmodel

import com.safedb.model.ConnectionDef
import com.safedb.service.SafeDbService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ConnectionsViewModel(private val service: SafeDbService, private val scope: CoroutineScope) {
    private val _connections = MutableStateFlow<List<ConnectionDef>>(emptyList())
    val connections: StateFlow<List<ConnectionDef>> = _connections.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _deleteError = MutableStateFlow<String?>(null)
    val deleteError: StateFlow<String?> = _deleteError.asStateFlow()

    suspend fun load() {
        capturingFailure(_error, _loading) { _connections.value = service.listConnections() }
    }

    fun refresh() {
        scope.launch { load() }
    }

    fun delete(id: String, onComplete: () -> Unit = {}) {
        scope.launch {
            capturingFailure(_deleteError) {
                service.deleteConnection(id)
                _connections.value = service.listConnections()
                onComplete()
            }
        }
    }

    fun clearDeleteError() {
        _deleteError.value = null
    }

    suspend fun testConnection(def: ConnectionDef, password: String?): String =
        service.testConnection(def, password)

    suspend fun createConnection(def: ConnectionDef, password: String): ConnectionDef {
        return service.createConnection(def, password)
    }

    suspend fun updateConnection(def: ConnectionDef, password: String?) {
        service.updateConnection(def, password)
    }

    fun connectionName(id: String): String =
        _connections.value.firstOrNull { it.id == id }?.name ?: "Unknown"
}
