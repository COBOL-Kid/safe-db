package com.safedb.model

import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Base64
import java.util.UUID
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

const val CURRENT_SCHEMA_VERSION: Int = 1
const val MAX_CELL_BYTES: Int = 1024 * 1024
const val MAX_RESULT_BYTES: Int = 10 * 1024 * 1024

@Serializable
data class QuerySpec(
    val tables: List<TableRef> = emptyList(),
    val columns: List<ColumnSel> = emptyList(),
    val joins: List<JoinSpec> = emptyList(),
    val filters: FilterGroup,
    val limit: Int,
    val distinct: Boolean = false,
    val sorts: List<SortSpec> = emptyList(),
    val groups: List<GroupSpec> = emptyList(),
    @SerialName("schema_version") val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    @SerialName("connector_overrides")
    val connectorOverrides: Map<String, GroupConnector> = emptyMap(),
)

@Serializable data class TableRef(val schema: String, val name: String, val alias: String)

@Serializable
data class ColumnSel(@SerialName("table_alias") val tableAlias: String, val column: String)

@Serializable
data class SortSpec(
    @SerialName("table_alias") val tableAlias: String,
    val column: String,
    val direction: SortDirection = SortDirection.Asc,
)

@Serializable
data class GroupSpec(@SerialName("table_alias") val tableAlias: String, val column: String)

@Serializable
enum class SortDirection {
    Asc,
    Desc,
}

@Serializable
data class JoinSpec(
    @SerialName("left_alias") val leftAlias: String,
    @SerialName("left_column") val leftColumn: String,
    @SerialName("right_alias") val rightAlias: String,
    @SerialName("right_column") val rightColumn: String,
)

@Serializable(with = FilterNodeSerializer::class)
sealed class FilterNode {
    data class Leaf(val spec: FilterSpec) : FilterNode()

    data class Group(val group: FilterGroup) : FilterNode()
}

@Serializable
data class FilterGroup(
    val id: String = "",
    val connector: GroupConnector = GroupConnector.And,
    val children: List<FilterNode> = emptyList(),
) {
    companion object {
        fun empty(): FilterGroup =
            FilterGroup(
                id = UUID.randomUUID().toString(),
                connector = GroupConnector.And,
                children = emptyList(),
            )
    }
}

@Serializable
enum class GroupConnector {
    And,
    Or,
}

@Serializable
data class FilterSpec(
    val id: String = "",
    @SerialName("table_alias") val tableAlias: String,
    val column: String,
    val op: FilterOp,
    val value: FilterValue? = null,
)

@Serializable(with = FilterValueSerializer::class)
sealed class FilterValue {
    data class Single(val literal: FilterLiteral) : FilterValue()

    data class ListValue(val literals: List<FilterLiteral>) : FilterValue()

    data class Pair(val first: FilterLiteral, val second: FilterLiteral) : FilterValue()
}

@Serializable
enum class LiteralKind {
    Text,
    Int,
    Decimal,
    Float,
    Bool,
    Date,
    DateTime,
}

@Serializable data class FilterLiteral(val kind: LiteralKind, val text: String)

@Serializable
enum class FilterOp {
    Eq,
    Ne,
    Gt,
    Gte,
    Lt,
    Lte,
    Contains,
    ContainsIgnoreCase,
    NotContains,
    StartsWith,
    EndsWith,
    Like,
    NotLike,
    Ilike,
    In,
    NotIn,
    Between,
    IsNull,
    IsNotNull,
    IsEmpty,
    IsNotEmpty,
}

enum class ValueKind {
    None,
    Single,
    List,
    Pair,
}

fun FilterOp.valueKind(): ValueKind =
    when (this) {
        FilterOp.IsNull,
        FilterOp.IsNotNull,
        FilterOp.IsEmpty,
        FilterOp.IsNotEmpty -> ValueKind.None
        FilterOp.In,
        FilterOp.NotIn -> ValueKind.List
        FilterOp.Between -> ValueKind.Pair
        else -> ValueKind.Single
    }

fun FilterOp.sqlOperator(): String? =
    when (this) {
        FilterOp.Eq -> "="
        FilterOp.Ne -> "<>"
        FilterOp.Gt -> ">"
        FilterOp.Gte -> ">="
        FilterOp.Lt -> "<"
        FilterOp.Lte -> "<="
        FilterOp.Like -> "LIKE"
        FilterOp.NotLike -> "NOT LIKE"
        else -> null
    }

sealed class BindValue {
    data class Text(val value: String) : BindValue()

    data class Int(val value: Long) : BindValue()

    data class Decimal(val value: BigDecimal) : BindValue()

    data class Float(val value: Double) : BindValue()

    data class Bool(val value: Boolean) : BindValue()

    data class Date(val value: LocalDate) : BindValue()

    data class DateTime(val value: LocalDateTime) : BindValue()

    data object Null : BindValue()

    companion object {
        fun fromLiteral(literal: FilterLiteral): Result<BindValue> = runCatching {
            when (literal.kind) {
                LiteralKind.Text -> Text(literal.text)
                LiteralKind.Int ->
                    Int(
                        literal.text.toLongOrNull()
                            ?: throw IllegalArgumentException(
                                "'${literal.text}' is not a valid integer"
                            )
                    )
                LiteralKind.Decimal ->
                    Decimal(
                        literal.text.toBigDecimalOrNull()
                            ?: throw IllegalArgumentException(
                                "'${literal.text}' is not a valid decimal"
                            )
                    )
                LiteralKind.Float ->
                    Float(
                        literal.text.toDoubleOrNull()
                            ?: throw IllegalArgumentException(
                                "'${literal.text}' is not a valid number"
                            )
                    )
                LiteralKind.Bool ->
                    Bool(
                        parseBoolLiteral(literal.text)
                            ?: throw IllegalArgumentException(
                                "'${literal.text}' is not a valid boolean"
                            )
                    )
                LiteralKind.Date -> Date(parseDateLiteral(literal.text).getOrThrow())
                LiteralKind.DateTime -> DateTime(parseDateTimeLiteral(literal.text).getOrThrow())
            }
        }
    }
}

internal fun parseBoolLiteral(text: String): Boolean? =
    when {
        text.equals("true", ignoreCase = true) ||
            text.equals("1", ignoreCase = true) ||
            text.equals("yes", ignoreCase = true) -> true
        text.equals("false", ignoreCase = true) ||
            text.equals("0", ignoreCase = true) ||
            text.equals("no", ignoreCase = true) ||
            text.isEmpty() -> false
        else -> null
    }

fun parseDateLiteral(text: String): Result<LocalDate> = runCatching {
    try {
        LocalDate.parse(text.trim(), DateTimeFormatter.ISO_LOCAL_DATE)
    } catch (_: DateTimeParseException) {
        throw IllegalArgumentException("'$text' is not a valid date; expected YYYY-MM-DD")
    }
}

fun parseDateTimeLiteral(text: String): Result<LocalDateTime> = runCatching {
    val trimmed = text.trim()
    val parsers =
        listOf(
            DateTimeFormatter.ofPattern(
                "yyyy-MM-dd'T'HH:mm:ss[.SSSSSS][.SSSSS][.SSSS][.SSS][.SS][.S]"
            ),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"),
            DateTimeFormatter.ofPattern(
                "yyyy-MM-dd HH:mm:ss[.SSSSSS][.SSSSS][.SSSS][.SSS][.SS][.S]"
            ),
        )
    for (formatter in parsers) {
        try {
            return@runCatching LocalDateTime.parse(trimmed, formatter)
        } catch (_: DateTimeParseException) {}
    }
    // The IR only carries local timestamps. Normalizing an offset-bearing value to UTC would bind
    // a different instant than the database compares against a local timestamp column, so reject
    // rather than silently shift the comparison.
    val hasOffset = runCatching { java.time.OffsetDateTime.parse(trimmed) }.isSuccess
    if (hasOffset) {
        throw IllegalArgumentException(
            "'$text' has a UTC offset, which isn't supported; write the timestamp as the column's local time (YYYY-MM-DDTHH:MM:SS)"
        )
    }
    throw IllegalArgumentException(
        "'$text' is not a valid datetime; expected YYYY-MM-DDTHH:MM[:SS]"
    )
}

@Serializable
data class QueryResult(
    val columns: List<ResultColumn>,
    val rows: List<List<ResultCell>>,
    @SerialName("row_count") val rowCount: Int,
    val truncated: Boolean,
    val warnings: List<String>,
) {
    companion object {
        private val rowJson = Json { encodeDefaults = true }

        fun fromRows(columns: List<ResultColumn>, rows: List<List<ResultCell>>): QueryResult {
            val kept = mutableListOf<List<ResultCell>>()
            var encodedBytes = 0
            var resultTruncated = false
            var cellTruncated = false

            for (row in rows) {
                cellTruncated = cellTruncated || row.any { it.wasTruncated() }
                val rowBytes = runCatching {
                    rowJson.encodeToString(row).toByteArray(Charsets.UTF_8).size
                }
                    .getOrDefault(MAX_RESULT_BYTES + 1)
                if (encodedBytes + rowBytes > MAX_RESULT_BYTES) {
                    resultTruncated = true
                    break
                }
                encodedBytes += rowBytes
                kept.add(row)
            }

            val warnings = buildList {
                if (cellTruncated) {
                    add("One or more cells exceeded $MAX_CELL_BYTES bytes and were truncated")
                }
                if (resultTruncated) {
                    add("Result exceeded $MAX_RESULT_BYTES encoded bytes and was truncated")
                }
            }

            return QueryResult(
                columns = columns,
                rows = kept,
                rowCount = kept.size,
                truncated = resultTruncated,
                warnings = warnings,
            )
        }
    }
}

@Serializable
data class ResultColumn(val name: String, @SerialName("data_type") val dataType: String) {
    companion object {
        fun of(name: String, dataType: String): ResultColumn = ResultColumn(name, dataType)
    }
}

@Serializable
@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("kind")
sealed class ResultCell {
    @Serializable @SerialName("Null") data object Null : ResultCell()

    @Serializable @SerialName("Bool") data class BoolCell(val value: Boolean) : ResultCell()

    @Serializable @SerialName("Integer") data class IntegerCell(val value: Long) : ResultCell()

    @Serializable @SerialName("Float") data class FloatCell(val value: Double) : ResultCell()

    @Serializable @SerialName("Text") data class TextCell(val value: TextValue) : ResultCell()

    @Serializable @SerialName("Binary") data class BinaryCell(val value: BinaryValue) : ResultCell()

    fun wasTruncated(): Boolean =
        when (this) {
            is TextCell -> value.truncated
            is BinaryCell -> value.truncated
            else -> false
        }

    companion object {
        fun text(value: String): ResultCell {
            val (text, truncated) = truncateUtf8(value, MAX_CELL_BYTES)
            return TextCell(TextValue(text = text, truncated = truncated))
        }

        fun binary(value: ByteArray): ResultCell {
            val truncated = value.size > MAX_CELL_BYTES
            val clipped = value.copyOf(value.size.coerceAtMost(MAX_CELL_BYTES))
            return BinaryCell(
                BinaryValue(
                    base64 = Base64.getEncoder().encodeToString(clipped),
                    truncated = truncated,
                )
            )
        }

        fun bool(value: Boolean): ResultCell = BoolCell(value)

        fun integer(value: Long): ResultCell = IntegerCell(value)

        fun float(value: Double): ResultCell = FloatCell(value)

        private fun truncateUtf8(value: String, maxBytes: Int): Pair<String, Boolean> {
            if (value.toByteArray(Charsets.UTF_8).size <= maxBytes) {
                return value to false
            }
            var end = 0
            var usedBytes = 0
            while (end < value.length) {
                val codePoint = value.codePointAt(end)
                val chars = Character.charCount(codePoint)
                val bytes = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8).size
                if (usedBytes + bytes > maxBytes) break
                usedBytes += bytes
                end += chars
            }
            return value.substring(0, end) to true
        }
    }
}

@Serializable data class TextValue(val text: String, val truncated: Boolean)

@Serializable data class BinaryValue(val base64: String, val truncated: Boolean)

data class CompiledQuery(val sql: String, val params: List<BindValue>)

internal object FilterNodeSerializer : KSerializer<FilterNode> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("FilterNode")

    override fun serialize(encoder: Encoder, value: FilterNode) {
        val jsonEncoder =
            encoder as? JsonEncoder
                ?: throw SerializationException("FilterNode can only be serialized to JSON")
        val element =
            when (value) {
                is FilterNode.Leaf ->
                    buildJsonObject {
                        put(
                            "Leaf",
                            jsonEncoder.json.encodeToJsonElement(
                                FilterSpec.serializer(),
                                value.spec,
                            ),
                        )
                    }
                is FilterNode.Group ->
                    buildJsonObject {
                        put(
                            "Group",
                            jsonEncoder.json.encodeToJsonElement(
                                FilterGroup.serializer(),
                                value.group,
                            ),
                        )
                    }
            }
        jsonEncoder.encodeJsonElement(element)
    }

    override fun deserialize(decoder: Decoder): FilterNode {
        val jsonDecoder =
            decoder as? JsonDecoder
                ?: throw SerializationException("FilterNode can only be deserialized from JSON")
        val objectValue = jsonDecoder.decodeJsonElement().jsonObject
        return when {
            "Leaf" in objectValue ->
                FilterNode.Leaf(
                    jsonDecoder.json.decodeFromJsonElement(
                        FilterSpec.serializer(),
                        objectValue.getValue("Leaf"),
                    )
                )
            "Group" in objectValue ->
                FilterNode.Group(
                    jsonDecoder.json.decodeFromJsonElement(
                        FilterGroup.serializer(),
                        objectValue.getValue("Group"),
                    )
                )
            else -> throw SerializationException("Unknown FilterNode variant: $objectValue")
        }
    }
}

internal object FilterValueSerializer : KSerializer<FilterValue> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("FilterValue")

    override fun serialize(encoder: Encoder, value: FilterValue) {
        val jsonEncoder =
            encoder as? JsonEncoder
                ?: throw SerializationException("FilterValue can only be serialized to JSON")
        val element =
            when (value) {
                is FilterValue.Single ->
                    buildJsonObject {
                        put(
                            "Single",
                            jsonEncoder.json.encodeToJsonElement(
                                FilterLiteral.serializer(),
                                value.literal,
                            ),
                        )
                    }
                is FilterValue.ListValue ->
                    buildJsonObject {
                        put(
                            "List",
                            JsonArray(
                                value.literals.map {
                                    jsonEncoder.json.encodeToJsonElement(
                                        FilterLiteral.serializer(),
                                        it,
                                    )
                                }
                            ),
                        )
                    }
                is FilterValue.Pair ->
                    buildJsonObject {
                        put(
                            "Pair",
                            JsonArray(
                                listOf(
                                    jsonEncoder.json.encodeToJsonElement(
                                        FilterLiteral.serializer(),
                                        value.first,
                                    ),
                                    jsonEncoder.json.encodeToJsonElement(
                                        FilterLiteral.serializer(),
                                        value.second,
                                    ),
                                )
                            ),
                        )
                    }
            }
        jsonEncoder.encodeJsonElement(element)
    }

    override fun deserialize(decoder: Decoder): FilterValue {
        val jsonDecoder =
            decoder as? JsonDecoder
                ?: throw SerializationException("FilterValue can only be deserialized from JSON")
        val objectValue = jsonDecoder.decodeJsonElement().jsonObject
        return when {
            "Single" in objectValue ->
                FilterValue.Single(
                    jsonDecoder.json.decodeFromJsonElement(
                        FilterLiteral.serializer(),
                        objectValue.getValue("Single"),
                    )
                )
            "List" in objectValue ->
                FilterValue.ListValue(
                    objectValue.getValue("List").jsonArray.map {
                        jsonDecoder.json.decodeFromJsonElement(FilterLiteral.serializer(), it)
                    }
                )
            "Pair" in objectValue -> {
                val pair = objectValue.getValue("Pair").jsonArray
                require(pair.size == 2) { "Pair filter value must contain exactly two literals" }
                FilterValue.Pair(
                    jsonDecoder.json.decodeFromJsonElement(FilterLiteral.serializer(), pair[0]),
                    jsonDecoder.json.decodeFromJsonElement(FilterLiteral.serializer(), pair[1]),
                )
            }
            else -> throw SerializationException("Unknown FilterValue variant: $objectValue")
        }
    }
}

object SafeDbJson {
    // Store files emit defaults so older and newer readers see a stable persisted shape.
    val store: Json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
        isLenient = true
    }

    val lenient: Json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        isLenient = true
    }
}
