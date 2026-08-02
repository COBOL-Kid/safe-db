package com.safedb.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Settings(
    @SerialName("blocked_schemas")
    val blockedSchemas: List<String> = emptyList(),
    val theme: String = DEFAULT_THEME,
    @SerialName("color_scheme")
    val colorScheme: String = ThemePalette.DEFAULT.id,
    @SerialName("query_risk_gate")
    val queryRiskGate: QueryRiskGate = QueryRiskGate.Standard,
    @SerialName("default_connection_id")
    val defaultConnectionId: String? = null,
    @SerialName("default_schema")
    val defaultSchema: String? = null,
    @SerialName("last_selected_schemas")
    val lastSelectedSchemas: Map<String, String> = emptyMap(),
) {
    fun palette(): ThemePalette = ThemePalette.fromId(colorScheme)

    companion object {
        const val DEFAULT_THEME: String = "light"

        fun default(): Settings = Settings()
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

    val theme = if (settings.theme == "dark") "dark" else Settings.DEFAULT_THEME
    val colorScheme = settings.palette().id
    val defaultConnectionId = settings.defaultConnectionId?.trim()?.takeIf { it.isNotEmpty() }
    val defaultSchema = if (defaultConnectionId == null) {
        null
    } else {
        settings.defaultSchema?.trim()?.takeIf { it.isNotEmpty() }
    }
    val lastSelectedSchemas = settings.lastSelectedSchemas
        .asSequence()
        .mapNotNull { (connectionId, schema) ->
            val normalizedConnectionId = connectionId.trim()
            val normalizedSchema = schema.trim()
            if (normalizedConnectionId.isEmpty() || normalizedSchema.isEmpty()) {
                null
            } else {
                normalizedConnectionId to normalizedSchema
            }
        }
        .sortedBy { it.first }
        .toMap(LinkedHashMap())

    return settings.copy(
        blockedSchemas = blockedSchemas,
        theme = theme,
        colorScheme = colorScheme,
        queryRiskGate = settings.queryRiskGate,
        defaultConnectionId = defaultConnectionId,
        defaultSchema = defaultSchema,
        lastSelectedSchemas = lastSelectedSchemas,
    )
}

@Serializable
enum class QueryRiskGate {
    Cautious,
    Standard,
    Flexible,
    Disabled,
}
