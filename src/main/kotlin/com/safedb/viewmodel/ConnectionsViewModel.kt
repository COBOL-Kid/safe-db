package com.safedb.viewmodel

import com.safedb.model.ConnectionDef
import com.safedb.service.SafeDbService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ConnectionsViewModel(
    private val service: SafeDbService,
    private val scope: CoroutineScope,
    private val onCredentialsUpdated: (ConnectionDef) -> Unit = {},
) {
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
        updateConnectionInternal(def, password, notifyCredentialsUpdated = password != null)
    }

    private suspend fun updateConnectionInternal(
        def: ConnectionDef,
        password: String?,
        notifyCredentialsUpdated: Boolean,
    ) {
        service.updateConnection(def, password)
        if (notifyCredentialsUpdated) {
            onCredentialsUpdated(def)
        }
    }

    fun saveConnection(
        def: ConnectionDef,
        password: String?,
        editing: Boolean,
        credentialMaterialChanged: Boolean,
        onConnectionChanged: (String) -> Unit = {},
        onComplete: (Result<ConnectionDef>) -> Unit = {},
    ): Job = scope.launch {
        try {
            val saved =
                if (editing) {
                    // Only re-probe in place for password-only updates; credential-material edits
                    // trigger onConnectionChanged, which clears the active connection immediately.
                    updateConnectionInternal(
                        def,
                        password,
                        notifyCredentialsUpdated = password != null && !credentialMaterialChanged,
                    )
                    def
                } else {
                    createConnection(def, password ?: "")
                }
            if (editing && credentialMaterialChanged) {
                onConnectionChanged(saved.id)
            }
            load()
            onComplete(Result.success(saved))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            onComplete(Result.failure(error))
        }
    }
}
