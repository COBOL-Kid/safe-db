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
    var selectedSchema by mutableStateOf<String?>(null)
        private set
    var preferredSchemaWarning by mutableStateOf<String?>(null)
        private set
    var search by mutableStateOf("")

    val tables: List<TableInfo>
        get() = schema?.tables.orEmpty()

    val schemaOptions: List<String>
        get() = tables
            .asSequence()
            .map { it.schema }
            .distinct()
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
            .toList()

    val filteredTables: List<TableInfo>
        get() {
            val query = search.trim().lowercase()
            return tables.filter { table ->
                (selectedSchema == null || table.schema == selectedSchema) &&
                    (query.isEmpty() || table.name.lowercase().contains(query))
            }
        }

    fun load(
        connectionId: String,
        preferredSchema: String? = null,
        onComplete: ((Boolean) -> Unit)? = null,
    ) {
        if (loadedConnectionId == connectionId && schema != null) {
            applyPreferredSchema(preferredSchema)
            onComplete?.invoke(true)
            return
        }
        val generation = ++requestGeneration
        selectedSchema = null
        preferredSchemaWarning = null
        search = ""
        scope.launch {
            if (generation != requestGeneration) return@launch
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
                applyPreferredSchema(preferredSchema)
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

    fun selectSchema(schema: String?) {
        selectedSchema = schema?.takeIf { it in schemaOptions }
        preferredSchemaWarning = null
    }

    fun clear() {
        requestGeneration += 1
        loading = false
        schema = null
        loadedConnectionId = null
        selectedSchema = null
        preferredSchemaWarning = null
        error = null
        search = ""
    }

    private fun applyPreferredSchema(preferredSchema: String?) {
        selectedSchema = preferredSchema?.takeIf { it in schemaOptions }
        preferredSchemaWarning = if (preferredSchema != null && selectedSchema == null) {
            "Default schema \"$preferredSchema\" is unavailable. Showing all schemas."
        } else {
            null
        }
    }
}
