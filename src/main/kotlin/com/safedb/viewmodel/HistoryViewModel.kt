package com.safedb.viewmodel

import com.safedb.model.HistoryEntry
import com.safedb.service.SafeDbService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class HistoryViewModel(private val service: SafeDbService, private val scope: CoroutineScope) {
    private val _entries = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val entries: StateFlow<List<HistoryEntry>> = _entries.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    suspend fun load() {
        _loading.value = true
        _error.value = null
        try {
            _entries.value = service.listHistory()
        } catch (error: Exception) {
            _error.value = error.message ?: error.toString()
        } finally {
            _loading.value = false
        }
    }

    fun refresh() {
        scope.launch { load() }
    }

    fun clear(onComplete: () -> Unit = {}) {
        scope.launch {
            _error.value = null
            try {
                service.clearHistory()
                _entries.value = emptyList()
                onComplete()
            } catch (error: Exception) {
                _error.value = error.message ?: error.toString()
            }
        }
    }

    fun clearError() {
        _error.value = null
    }
}
