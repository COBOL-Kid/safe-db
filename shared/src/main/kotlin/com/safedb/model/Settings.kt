package com.safedb.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Settings(
    @SerialName("blocked_schemas")
    val blockedSchemas: List<String> = emptyList(),
    @SerialName("explain_cost_threshold")
    val explainCostThreshold: Double = DEFAULT_COST_THRESHOLD,
    @SerialName("explain_cost_thresholds")
    val explainCostThresholds: Map<Dialect, Double> = defaultDialectThresholds(),
    val theme: String = DEFAULT_THEME,
    @SerialName("color_scheme")
    val colorScheme: String = ThemePalette.DEFAULT.id,
    @SerialName("query_risk_gate")
    val queryRiskGate: QueryRiskGate = QueryRiskGate.Standard,
    @SerialName("default_connection_id")
    val defaultConnectionId: String? = null,
    @SerialName("default_schema")
    val defaultSchema: String? = null,
) {
    fun costThreshold(dialect: Dialect): Double =
        explainCostThresholds[dialect] ?: explainCostThreshold

    fun palette(): ThemePalette = ThemePalette.fromId(colorScheme)

    companion object {
        const val DEFAULT_COST_THRESHOLD: Double = 100_000.0
        const val DEFAULT_THEME: String = "light"

        fun default(): Settings = Settings()

        fun defaultDialectThresholds(): Map<Dialect, Double> =
            Dialect.entries.associateWith { DEFAULT_COST_THRESHOLD }
    }
}

/** Normalize user-supplied settings before persistence. */
fun normalizeSettings(settings: Settings): Settings {
    val seen = LinkedHashSet<String>()
    val blockedSchemas = settings.blockedSchemas
        .asSequence()
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }
        .filter { seen.add(it) }
        .toList()

    val explainCostThreshold = settings.explainCostThreshold.coerceIn(1.0, 10_000_000.0)

    val explainCostThresholds = if (settings.explainCostThresholds.isEmpty()) {
        Dialect.entries.associateWith { explainCostThreshold }
    } else {
        settings.explainCostThresholds.mapValues { (_, threshold) ->
            threshold.coerceIn(1.0, 10_000_000.0)
        }
    }

    val theme = if (settings.theme == "dark") "dark" else Settings.DEFAULT_THEME
    val colorScheme = settings.palette().id
    val defaultConnectionId = settings.defaultConnectionId?.trim()?.takeIf { it.isNotEmpty() }
    val defaultSchema = if (defaultConnectionId == null) {
        null
    } else {
        settings.defaultSchema?.trim()?.takeIf { it.isNotEmpty() }
    }

    return settings.copy(
        blockedSchemas = blockedSchemas,
        explainCostThreshold = explainCostThreshold,
        explainCostThresholds = explainCostThresholds,
        theme = theme,
        colorScheme = colorScheme,
        queryRiskGate = settings.queryRiskGate,
        defaultConnectionId = defaultConnectionId,
        defaultSchema = defaultSchema,
    )
}

@Serializable
enum class QueryRiskGate {
    Cautious,
    Standard,
    Flexible,
    Disabled,
}
