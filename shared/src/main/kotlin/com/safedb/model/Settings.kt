package com.safedb.model

import java.math.BigDecimal
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class Settings(
    @SerialName("blocked_schemas") val blockedSchemas: List<String> = emptyList(),
    val theme: String = DEFAULT_THEME,
    @SerialName("color_scheme") val colorScheme: String = ThemePalette.DEFAULT.id,
    @SerialName("query_risk_gate") val queryRiskGate: QueryRiskGate = QueryRiskGate.Standard,
    @SerialName("default_connection_id") val defaultConnectionId: String? = null,
    @SerialName("default_schema") val defaultSchema: String? = null,
    @SerialName("last_selected_schemas") val lastSelectedSchemas: Map<String, String> = emptyMap(),
    @SerialName("query_timeout_seconds")
    @Serializable(with = QueryTimeoutSecondsSerializer::class)
    val queryTimeoutSeconds: Int = DEFAULT_QUERY_TIMEOUT_SECONDS,
) {
    fun palette(): ThemePalette = ThemePalette.fromId(colorScheme)

    fun isDark(): Boolean = theme == THEME_DARK

    fun clampedQueryTimeoutSeconds(): Int =
        queryTimeoutSeconds.coerceIn(MIN_QUERY_TIMEOUT_SECONDS, MAX_QUERY_TIMEOUT_SECONDS)

    // JDBC treats 0 as "no timeout", so the 1..120 clamp must survive normalization.
    fun queryTimeoutMs(): Int = clampedQueryTimeoutSeconds() * 1_000

    companion object {
        const val THEME_DARK: String = "dark"
        const val THEME_LIGHT: String = "light"
        const val DEFAULT_THEME: String = THEME_LIGHT
        const val MIN_QUERY_TIMEOUT_SECONDS: Int = 1
        const val MAX_QUERY_TIMEOUT_SECONDS: Int = 120
        const val DEFAULT_QUERY_TIMEOUT_SECONDS: Int = 10

        fun default(): Settings = Settings()
    }
}

// A plain Int decode would discard the whole settings file for a bad timeout token.
internal object QueryTimeoutSecondsSerializer : KSerializer<Int> {
    private val minInt = BigDecimal(Int.MIN_VALUE)
    private val maxInt = BigDecimal(Int.MAX_VALUE)

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.safedb.model.QueryTimeoutSeconds", PrimitiveKind.INT)

    override fun serialize(encoder: Encoder, value: Int) {
        encoder.encodeInt(value)
    }

    override fun deserialize(decoder: Decoder): Int {
        val element =
            runCatching { (decoder as? JsonDecoder)?.decodeJsonElement() }.getOrNull()
                ?: return Settings.DEFAULT_QUERY_TIMEOUT_SECONDS
        val primitive = element as? JsonPrimitive
        if (primitive == null || primitive.isString) {
            return Settings.DEFAULT_QUERY_TIMEOUT_SECONDS
        }
        val number =
            primitive.content.toBigDecimalOrNull() ?: return Settings.DEFAULT_QUERY_TIMEOUT_SECONDS
        if (number.stripTrailingZeros().scale() > 0) {
            return Settings.DEFAULT_QUERY_TIMEOUT_SECONDS
        }
        return when {
            number > maxInt -> Int.MAX_VALUE
            number < minInt -> Int.MIN_VALUE
            else -> number.toInt()
        }
    }
}

fun normalizeSettings(settings: Settings): Settings {
    val seen = LinkedHashSet<String>()
    val blockedSchemas =
        settings.blockedSchemas
            .asSequence()
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .filter { seen.add(it) }
            .toList()

    val theme =
        settings.theme.trim().lowercase().takeIf {
            it == Settings.THEME_DARK || it == Settings.THEME_LIGHT
        } ?: Settings.DEFAULT_THEME
    val colorScheme = settings.palette().id
    val defaultConnectionId = settings.defaultConnectionId?.trim()?.takeIf { it.isNotEmpty() }
    val defaultSchema =
        if (defaultConnectionId == null) {
            null
        } else {
            settings.defaultSchema?.trim()?.takeIf { it.isNotEmpty() }
        }
    val lastSelectedSchemas =
        settings.lastSelectedSchemas
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
    val queryTimeoutSeconds = settings.clampedQueryTimeoutSeconds()

    return settings.copy(
        blockedSchemas = blockedSchemas,
        theme = theme,
        colorScheme = colorScheme,
        queryRiskGate = settings.queryRiskGate,
        defaultConnectionId = defaultConnectionId,
        defaultSchema = defaultSchema,
        lastSelectedSchemas = lastSelectedSchemas,
        queryTimeoutSeconds = queryTimeoutSeconds,
    )
}

@Serializable
enum class QueryRiskGate {
    Cautious,
    Standard,
    Flexible,
    Disabled,
}
