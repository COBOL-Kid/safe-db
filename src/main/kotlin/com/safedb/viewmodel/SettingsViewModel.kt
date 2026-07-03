package com.safedb.viewmodel

import com.safedb.model.Dialect
import com.safedb.model.Settings
import com.safedb.model.normalizeSettings
import com.safedb.service.SafeDbService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val service: SafeDbService,
    private val scope: CoroutineScope,
) {
    private val _settings = MutableStateFlow(Settings.default())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _saveError = MutableStateFlow<String?>(null)
    val saveError: StateFlow<String?> = _saveError.asStateFlow()

    val isDark: Boolean
        get() = _settings.value.theme == "dark"

    suspend fun load() {
        _loading.value = true
        try {
            _settings.value = service.getSettings()
        } finally {
            _loading.value = false
        }
    }

    fun toggleTheme() {
        scope.launch {
            val next = if (_settings.value.theme == "dark") Settings.DEFAULT_THEME else "dark"
            val updated = _settings.value.copy(theme = next)
            save(updated)
        }
    }

    fun saveThresholds(thresholds: Map<Dialect, Double>, onSuccess: () -> Unit = {}) {
        scope.launch {
            _saveError.value = null
            val invalid = thresholds.entries.firstOrNull { (_, value) -> value < 1.0 || value > 10_000_000.0 }
            if (invalid != null) {
                _saveError.value = "${dialectLabel(invalid.key)} threshold must be between 1 and 10,000,000"
                return@launch
            }
            val updated = _settings.value.copy(
                explainCostThresholds = thresholds,
                explainCostThreshold = thresholds[Dialect.Postgres] ?: Settings.DEFAULT_COST_THRESHOLD,
            )
            save(updated)
            onSuccess()
        }
    }

    fun addBlockedSchema(schema: String) {
        val normalized = schema.trim().lowercase()
        if (normalized.isEmpty()) return
        scope.launch {
            val current = _settings.value.blockedSchemas
            if (current.contains(normalized)) return@launch
            save(_settings.value.copy(blockedSchemas = current + normalized))
        }
    }

    fun removeBlockedSchema(schema: String) {
        scope.launch {
            save(
                _settings.value.copy(
                    blockedSchemas = _settings.value.blockedSchemas.filterNot { it == schema },
                ),
            )
        }
    }

    fun clearSaveError() {
        _saveError.value = null
    }

    private suspend fun save(settings: Settings) {
        val normalized = normalizeSettings(settings)
        service.saveSettings(normalized)
        _settings.value = normalized
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
