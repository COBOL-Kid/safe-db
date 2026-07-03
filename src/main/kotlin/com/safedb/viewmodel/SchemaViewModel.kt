package com.safedb.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.safedb.model.Schema
import com.safedb.model.TableInfo
import com.safedb.service.SafeDbService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class SchemaViewModel(
    private val service: SafeDbService,
    private val scope: CoroutineScope,
) {
    private var requestGeneration = 0

    var schema by mutableStateOf<Schema?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var loadedConnectionId by mutableStateOf<String?>(null)
        private set
    var search by mutableStateOf("")

    val tables: List<TableInfo>
        get() = schema?.tables.orEmpty()

    val filteredTables: List<TableInfo>
        get() {
            val query = search.trim().lowercase()
            if (query.isEmpty()) return tables
            return tables.filter { it.name.lowercase().contains(query) }
        }

    fun load(connectionId: String, onComplete: ((Boolean) -> Unit)? = null) {
        if (loadedConnectionId == connectionId && schema != null) {
            onComplete?.invoke(true)
            return
        }
        val generation = ++requestGeneration
        scope.launch {
            loading = true
            error = null
            schema = null
            try {
                val loaded = service.getSchema(connectionId)
                if (generation != requestGeneration) {
                    onComplete?.invoke(false)
                    return@launch
                }
                schema = loaded
                loadedConnectionId = connectionId
                onComplete?.invoke(true)
            } catch (e: Exception) {
                if (generation != requestGeneration) {
                    onComplete?.invoke(false)
                    return@launch
                }
                error = e.message ?: e.toString()
                loadedConnectionId = null
                onComplete?.invoke(false)
            } finally {
                if (generation == requestGeneration) {
                    loading = false
                }
            }
        }
    }

    fun clear() {
        requestGeneration += 1
        loading = false
        schema = null
        loadedConnectionId = null
        error = null
        search = ""
    }
}
