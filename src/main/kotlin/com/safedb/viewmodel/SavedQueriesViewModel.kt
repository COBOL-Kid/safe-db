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
        capturingFailure(_error, _loading) { _queries.value = service.listSavedQueries() }
    }

    fun refresh() {
        scope.launch { load() }
    }

    fun delete(id: String) {
        scope.launch {
            capturingFailure(_error) {
                service.deleteSavedQuery(id)
                _queries.value = service.listSavedQueries()
            }
        }
    }

    fun save(query: SavedQuery, onComplete: () -> Unit = {}) {
        scope.launch {
            capturingFailure(_error) {
                service.saveSavedQuery(query)
                _queries.value = service.listSavedQueries()
                onComplete()
            }
        }
    }
}
