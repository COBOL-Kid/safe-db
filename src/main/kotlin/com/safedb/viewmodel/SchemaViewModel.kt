package com.safedb.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.safedb.SchemaSelectionIntent
import com.safedb.SchemaSelectionSource
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
            val selected = selectedSchema ?: return emptyList()
            val query = search.trim().lowercase()
            return tables.filter { table ->
                table.schema == selected &&
                    (query.isEmpty() || table.name.lowercase().contains(query))
            }
        }

    internal fun load(
        connectionId: String,
        selection: SchemaSelectionIntent = SchemaSelectionIntent.Unselected,
        onUnavailableSelection: ((SchemaSelectionIntent) -> Unit)? = null,
        onComplete: ((Boolean) -> Unit)? = null,
    ) {
        if (loadedConnectionId == connectionId && schema != null) {
            applySelection(selection, onUnavailableSelection)
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
                applySelection(selection, onUnavailableSelection)
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

    private fun applySelection(
        selection: SchemaSelectionIntent,
        onUnavailableSelection: ((SchemaSelectionIntent) -> Unit)?,
    ) {
        val requestedSchema = selection.schema
        selectedSchema = requestedSchema?.takeIf { it in schemaOptions }
        if (requestedSchema == null || selectedSchema != null) {
            preferredSchemaWarning = null
            return
        }

        preferredSchemaWarning = when (selection.source) {
            SchemaSelectionSource.StartupDefault ->
                "Default schema \"$requestedSchema\" is unavailable. Select a schema or update Settings."
            SchemaSelectionSource.ConnectionHistory ->
                "Previously selected schema \"$requestedSchema\" is unavailable. Select another schema."
            SchemaSelectionSource.RestoredQuery ->
                "Query schema \"$requestedSchema\" is unavailable. Select another schema."
            SchemaSelectionSource.User ->
                "Schema \"$requestedSchema\" is unavailable. Select another schema."
            null -> null
        }
        onUnavailableSelection?.invoke(selection)
    }
}
