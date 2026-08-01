package com.safedb.viewmodel

import com.safedb.model.Dialect
import com.safedb.model.QueryRiskGate
import com.safedb.model.Settings
import com.safedb.model.ThemePalette
import com.safedb.model.normalizeSettings
import com.safedb.service.SafeDbService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SettingsViewModel(
    private val service: SafeDbService,
    private val scope: CoroutineScope,
) {
    private var defaultSchemaRequestGeneration = 0
    private var defaultSchemaConnectionId: String? = null
    private val settingsMutationMutex = Mutex()

    private val _settings = MutableStateFlow(Settings.default())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _saveError = MutableStateFlow<String?>(null)
    val saveError: StateFlow<String?> = _saveError.asStateFlow()

    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError.asStateFlow()

    private val _defaultSchemaOptions = MutableStateFlow<List<String>>(emptyList())
    val defaultSchemaOptions: StateFlow<List<String>> = _defaultSchemaOptions.asStateFlow()

    private val _defaultSchemaLoading = MutableStateFlow(false)
    val defaultSchemaLoading: StateFlow<Boolean> = _defaultSchemaLoading.asStateFlow()

    private val _defaultSchemaError = MutableStateFlow<String?>(null)
    val defaultSchemaError: StateFlow<String?> = _defaultSchemaError.asStateFlow()

    val isDark: Boolean
        get() = _settings.value.theme == "dark"

    val palette: ThemePalette
        get() = _settings.value.palette()

    suspend fun load() = settingsMutationMutex.withLock {
        _loading.value = true
        _loadError.value = null
        try {
            _settings.value = service.getSettings()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            _loadError.value = error.message ?: error.toString()
        } finally {
            _loading.value = false
        }
    }

    fun toggleTheme() {
        setDarkMode(!isDark)
    }

    fun setDarkMode(isDark: Boolean) {
        val next = if (isDark) "dark" else Settings.DEFAULT_THEME
        mutateSettings {
            if (_settings.value.theme == next) return@mutateSettings
            save(_settings.value.copy(theme = next))
        }
    }

    fun setColorScheme(palette: ThemePalette) {
        mutateSettings {
            if (_settings.value.palette() == palette) return@mutateSettings
            save(_settings.value.copy(colorScheme = palette.id))
        }
    }

    fun setQueryRiskGate(gate: QueryRiskGate) {
        mutateSettings {
            if (_settings.value.queryRiskGate == gate) return@mutateSettings
            save(_settings.value.copy(queryRiskGate = gate))
        }
    }

    fun saveThresholds(thresholds: Map<Dialect, Double>, onSuccess: () -> Unit = {}) {
        mutateSettings {
            val updatedThresholds = Dialect.entries.associateWith { dialect ->
                thresholds[dialect] ?: _settings.value.costThreshold(dialect)
            }
            val invalid = updatedThresholds.entries.firstOrNull { (_, value) ->
                !value.isFinite() || value !in Settings.MIN_COST_THRESHOLD..Settings.MAX_COST_THRESHOLD
            }
            if (invalid != null) {
                _saveError.value =
                    "${dialectLabel(invalid.key)} threshold must be between 1 and 10,000,000"
                return@mutateSettings
            }
            val updated = _settings.value.copy(
                explainCostThresholds = updatedThresholds,
                explainCostThreshold = updatedThresholds.getValue(Dialect.Postgres),
            )
            if (save(updated)) onSuccess()
        }
    }

    fun addBlockedSchema(schema: String) {
        val normalized = schema.trim().lowercase()
        if (normalized.isEmpty()) return
        mutateSettings {
            val current = _settings.value.blockedSchemas
            if (normalized in current) return@mutateSettings
            save(_settings.value.copy(blockedSchemas = current + normalized))
        }
    }

    fun removeBlockedSchema(schema: String) {
        val normalized = schema.trim().lowercase()
        mutateSettings {
            val current = _settings.value.blockedSchemas
            if (normalized !in current) return@mutateSettings
            save(_settings.value.copy(blockedSchemas = current - normalized))
        }
    }

    fun loadDefaultSchemaOptions(connectionId: String) {
        val generation = ++defaultSchemaRequestGeneration
        scope.launch {
            if (generation != defaultSchemaRequestGeneration) return@launch
            _defaultSchemaLoading.value = true
            _defaultSchemaError.value = null
            _defaultSchemaOptions.value = emptyList()
            defaultSchemaConnectionId = null
            try {
                val schemas = service.getSchema(connectionId).tables
                    .asSequence()
                    .map { it.schema }
                    .distinct()
                    .sortedWith(String.CASE_INSENSITIVE_ORDER)
                    .toList()
                if (generation != defaultSchemaRequestGeneration) return@launch
                _defaultSchemaOptions.value = schemas
                defaultSchemaConnectionId = connectionId
                if (schemas.isEmpty()) {
                    _defaultSchemaError.value = "No schemas containing visible tables were found."
                }
            } catch (error: Exception) {
                if (generation != defaultSchemaRequestGeneration) return@launch
                _defaultSchemaError.value = error.message ?: error.toString()
            } finally {
                if (generation == defaultSchemaRequestGeneration) {
                    _defaultSchemaLoading.value = false
                }
            }
        }
    }

    fun clearDefaultSchemaOptions() {
        defaultSchemaRequestGeneration += 1
        _defaultSchemaOptions.value = emptyList()
        defaultSchemaConnectionId = null
        _defaultSchemaLoading.value = false
        _defaultSchemaError.value = null
    }

    fun saveDefaultLocation(
        connectionId: String,
        schema: String,
        onSuccess: () -> Unit = {},
    ) {
        mutateSettings {
            val normalizedConnectionId = connectionId.trim()
            val normalizedSchema = schema.trim()
            if (normalizedConnectionId.isEmpty() || normalizedSchema.isEmpty()) return@mutateSettings
            if (
                defaultSchemaConnectionId != normalizedConnectionId ||
                normalizedSchema !in _defaultSchemaOptions.value
            ) {
                _saveError.value = "Select a schema loaded from the chosen database."
                return@mutateSettings
            }
            if (save(
                    _settings.value.copy(
                        defaultConnectionId = normalizedConnectionId,
                        defaultSchema = normalizedSchema,
                    ),
                )
            ) {
                onSuccess()
            }
        }
    }

    fun clearDefaultLocation(onSuccess: () -> Unit = {}) {
        mutateSettings {
            if (_settings.value.defaultConnectionId == null && _settings.value.defaultSchema == null) {
                onSuccess()
                return@mutateSettings
            }
            if (save(_settings.value.copy(defaultConnectionId = null, defaultSchema = null))) {
                onSuccess()
            }
        }
    }

    fun clearDefaultIfConnection(connectionId: String, onSuccess: () -> Unit = {}) {
        mutateSettings {
            if (_settings.value.defaultConnectionId != connectionId) return@mutateSettings
            if (save(_settings.value.copy(defaultConnectionId = null, defaultSchema = null))) {
                onSuccess()
            }
        }
    }

    fun clearSaveError() {
        _saveError.value = null
    }

    private fun mutateSettings(mutation: suspend () -> Unit) {
        scope.launch {
            settingsMutationMutex.withLock {
                _saveError.value = null
                mutation()
            }
        }
    }

    private suspend fun save(settings: Settings): Boolean {
        val normalized = normalizeSettings(settings)
        return try {
            service.saveSettings(normalized)
            _settings.value = normalized
            true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            _saveError.value = error.message ?: error.toString()
            false
        }
    }

    companion object {
        fun dialectLabel(dialect: Dialect): String =
            when (dialect) {
                Dialect.Postgres -> "PostgreSQL"
                Dialect.MySql -> "MySQL"
                Dialect.Mssql -> "SQL Server"
                Dialect.Oracle -> "Oracle"
            }
    }
}
