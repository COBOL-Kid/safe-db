package com.safedb.viewmodel

import com.safedb.model.Dialect
import com.safedb.model.Settings
import com.safedb.model.QueryRiskGate
import com.safedb.model.ThemePalette
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

    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError.asStateFlow()

    val isDark: Boolean
        get() = _settings.value.theme == "dark"

    val palette: ThemePalette
        get() = _settings.value.palette()

    suspend fun load() {
        _loading.value = true
        _loadError.value = null
        try {
            _settings.value = service.getSettings()
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
        if (_settings.value.theme == next) return
        scope.launch {
            _saveError.value = null
            val updated = _settings.value.copy(theme = next)
            save(updated)
        }
    }

    fun setColorScheme(palette: ThemePalette) {
        if (_settings.value.palette() == palette) return
        scope.launch {
            _saveError.value = null
            save(_settings.value.copy(colorScheme = palette.id))
        }
    }

    fun setQueryRiskGate(gate: QueryRiskGate) {
        if (_settings.value.queryRiskGate == gate) return
        scope.launch {
            _saveError.value = null
            save(_settings.value.copy(queryRiskGate = gate))
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
            if (save(updated)) {
                onSuccess()
            }
        }
    }

    fun clearSaveError() {
        _saveError.value = null
    }

    fun clearLoadError() {
        _loadError.value = null
    }

    private suspend fun save(settings: Settings): Boolean {
        val normalized = normalizeSettings(settings)
        return try {
            service.saveSettings(normalized)
            _settings.value = normalized
            true
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
