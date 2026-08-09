package com.safedb.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable data class Schema(val tables: List<TableInfo>)

@Serializable
data class TableInfo(
    val schema: String,
    val name: String,
    val columns: List<ColumnInfo>,
    val indexes: List<IndexInfo>,
    @SerialName("foreign_keys") val foreignKeys: List<ForeignKeyInfo> = emptyList(),
    @SerialName("index_metadata") val indexMetadata: MetadataCoverage = MetadataCoverage(),
    @SerialName("foreign_key_metadata")
    val foreignKeyMetadata: MetadataCoverage = MetadataCoverage(),
    @SerialName("table_size") val tableSize: TableSizeEstimate = TableSizeEstimate(),
)

fun TableInfo.qualifiedName(): String = "$schema.$name"

@Serializable
data class ColumnInfo(
    val name: String,
    @SerialName("data_type") val dataType: String,
    val nullable: Boolean,
    @SerialName("is_indexed") val isIndexed: Boolean = false,
    @SerialName("join_eligible") val joinEligible: Boolean = false,
    val category: ColumnCategory = ColumnCategory.Other,
)

@Serializable
data class IndexInfo(
    val name: String,
    val columns: List<String> = emptyList(),
    @SerialName("included_columns") val includedColumns: List<String> = emptyList(),
    val kind: String = "",
    @SerialName("supports_equality") val supportsEquality: Boolean = true,
    @SerialName("is_unique") val isUnique: Boolean = false,
    @SerialName("is_primary") val isPrimary: Boolean = false,
    // Null columns preserve expression-key positions instead of collapsing index ordinals.
    val keys: List<IndexKey> = emptyList(),
    val capabilities: IndexCapabilities = IndexCapabilities(),
    @SerialName("is_partial") val isPartial: Boolean? = null,
)

@Serializable
data class MetadataCoverage(
    val state: MetadataCoverageState = MetadataCoverageState.Unavailable,
    @SerialName("reason_code") val reasonCode: String? = "legacy_or_not_introspected",
) {
    val isComplete: Boolean
        get() = state == MetadataCoverageState.Complete

    companion object {
        fun complete(): MetadataCoverage = MetadataCoverage(MetadataCoverageState.Complete, null)

        fun unavailable(reasonCode: String): MetadataCoverage =
            MetadataCoverage(MetadataCoverageState.Unavailable, reasonCode)
    }
}

@Serializable
enum class MetadataCoverageState {
    Complete,
    Unavailable,
}

@Serializable
data class IndexKey(
    val column: String? = null,
    val direction: SortDirection? = null,
    val expression: Boolean = column == null,
)

// Null capabilities mean the adapter could not normalize them safely.
@Serializable
data class IndexCapabilities(
    val equality: Boolean? = null,
    val ordering: Boolean? = null,
    @SerialName("specialized_text") val specializedText: Boolean? = null,
    @SerialName("expression_keys") val expressionKeys: Boolean? = null,
    @SerialName("partial_predicate") val partialPredicate: Boolean? = null,
    @SerialName("included_columns") val includedColumns: Boolean? = null,
)

@Serializable
data class TableSizeEstimate(
    @SerialName("size_class") val sizeClass: TableSizeClass = TableSizeClass.Unknown,
    val coverage: MetadataCoverage = MetadataCoverage(),
    val confidence: EvidenceConfidence = EvidenceConfidence.Unknown,
)

@Serializable
enum class TableSizeClass {
    Small,
    Medium,
    Large,
    Unknown,
}

@Serializable
enum class EvidenceConfidence {
    High,
    Medium,
    Low,
    Unknown,
}

@Serializable
data class ForeignKeyInfo(
    val name: String,
    val columns: List<String> = emptyList(),
    @SerialName("referenced_schema") val referencedSchema: String,
    @SerialName("referenced_table") val referencedTable: String,
    @SerialName("referenced_columns") val referencedColumns: List<String> = emptyList(),
)

@Serializable
enum class ColumnCategory {
    Text,
    Integer,
    Decimal,
    Bool,
    Date,
    DateTime,
    Binary,
    Json,
    Other,
}

fun ColumnCategory?.isNumeric(): Boolean =
    this == ColumnCategory.Integer || this == ColumnCategory.Decimal

fun ColumnCategory?.isTemporal(): Boolean =
    this == ColumnCategory.Date || this == ColumnCategory.DateTime

fun classifyColumn(dataType: String): ColumnCategory {
    val dt = dataType.lowercase()
    return when {
        dt in setOf("bool", "boolean", "bit") -> ColumnCategory.Bool
        dt == "date" -> ColumnCategory.Date
        dt.startsWith("timestamp") ||
            dt.startsWith("datetime") ||
            dt in setOf("smalldatetime", "time") -> ColumnCategory.DateTime
        dt in
            setOf(
                "int",
                "integer",
                "smallint",
                "bigint",
                "mediumint",
                "tinyint",
                "serial",
                "bigserial",
            ) -> ColumnCategory.Integer
        dt in
            setOf(
                "decimal",
                "numeric",
                "number",
                "real",
                "double",
                "float",
                "float4",
                "float8",
                "money",
                "smallmoney",
                "double precision",
            ) || dt.startsWith("decimal") || dt.startsWith("numeric") || dt.startsWith("number") ->
            ColumnCategory.Decimal
        dt.contains("binary") || dt.contains("blob") || dt in setOf("bytea", "raw") ->
            ColumnCategory.Binary
        dt in setOf("json", "jsonb") -> ColumnCategory.Json
        dt in
            setOf(
                "text",
                "varchar",
                "char",
                "character",
                "character varying",
                "string",
                "tinytext",
                "mediumtext",
                "longtext",
                "nvarchar",
                "nchar",
                "varchar2",
                "nvarchar2",
                "clob",
                "nclob",
                "xml",
                "uuid",
            ) ||
            dt.startsWith("varchar") ||
            dt.startsWith("char") ||
            dt.startsWith("nchar") ||
            dt.startsWith("nvarchar") -> ColumnCategory.Text
        else -> ColumnCategory.Other
    }
}

fun markIndexedColumns(columns: MutableList<ColumnInfo>, indexes: List<IndexInfo>) {
    val indexed =
        indexes
            .flatMap { index ->
                if (index.keys.isNotEmpty()) index.keys.mapNotNull(IndexKey::column)
                else index.columns
            }
            .toSet()
    for (index in columns.indices) {
        val column = columns[index]
        val isIndexed = column.name in indexed
        val joinEligible = indexes.any { indexInfo ->
            val leadingColumn =
                indexInfo.keys.firstOrNull()?.column ?: indexInfo.columns.firstOrNull()
            (indexInfo.capabilities.equality ?: indexInfo.supportsEquality) &&
                leadingColumn == column.name
        }
        columns[index] =
            column.copy(
                isIndexed = isIndexed,
                joinEligible = joinEligible,
                category = classifyColumn(column.dataType),
            )
    }
}
