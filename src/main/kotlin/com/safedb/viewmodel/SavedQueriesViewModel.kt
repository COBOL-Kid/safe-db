package com.safedb.viewmodel

import com.safedb.model.SavedQuery
import com.safedb.service.SafeDbService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SavedQueriesViewModel(private val service: SafeDbService, private val scope: CoroutineScope) {
    private val _queries = MutableStateFlow<List<SavedQuery>>(emptyList())
    val queries: StateFlow<List<SavedQuery>> = _queries.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    suspend fun load() {
        _loading.value = true
        _error.value = null
        try {
            _queries.value = service.listSavedQueries()
        } catch (error: Exception) {
            _error.value = error.message ?: error.toString()
        } finally {
            _loading.value = false
        }
    }

    fun refresh() {
        scope.launch { load() }
    }

    fun delete(id: String) {
        scope.launch {
            _error.value = null
            try {
                service.deleteSavedQuery(id)
                _queries.value = service.listSavedQueries()
            } catch (error: Exception) {
                _error.value = error.message ?: error.toString()
            }
        }
    }

    fun save(query: SavedQuery, onComplete: () -> Unit = {}) {
        scope.launch {
            _error.value = null
            try {
                service.saveSavedQuery(query)
                _queries.value = service.listSavedQueries()
                onComplete()
            } catch (error: Exception) {
                _error.value = error.message ?: error.toString()
            }
        }
    }
}
