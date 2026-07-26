package com.safedb.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Schema(
    val tables: List<TableInfo>,
)

@Serializable
data class TableInfo(
    val schema: String,
    val name: String,
    val columns: List<ColumnInfo>,
    val indexes: List<IndexInfo>,
    @SerialName("foreign_keys")
    val foreignKeys: List<ForeignKeyInfo> = emptyList(),
)

fun TableInfo.qualifiedName(): String = "$schema.$name"

@Serializable
data class ColumnInfo(
    val name: String,
    @SerialName("data_type")
    val dataType: String,
    val nullable: Boolean,
    @SerialName("is_indexed")
    val isIndexed: Boolean = false,
    @SerialName("join_eligible")
    val joinEligible: Boolean = false,
    val category: ColumnCategory = ColumnCategory.Other,
)

@Serializable
data class IndexInfo(
    val name: String,
    val columns: List<String> = emptyList(),
    @SerialName("included_columns")
    val includedColumns: List<String> = emptyList(),
    val kind: String = "",
    @SerialName("supports_equality")
    val supportsEquality: Boolean = true,
    @SerialName("is_unique")
    val isUnique: Boolean = false,
    @SerialName("is_primary")
    val isPrimary: Boolean = false,
)

@Serializable
data class ForeignKeyInfo(
    val name: String,
    val columns: List<String> = emptyList(),
    @SerialName("referenced_schema")
    val referencedSchema: String,
    @SerialName("referenced_table")
    val referencedTable: String,
    @SerialName("referenced_columns")
    val referencedColumns: List<String> = emptyList(),
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

/** Semantic helpers shared by query and Explore surfaces. */
fun ColumnCategory?.isNumeric(): Boolean = this == ColumnCategory.Integer || this == ColumnCategory.Decimal

fun ColumnCategory?.isTemporal(): Boolean = this == ColumnCategory.Date || this == ColumnCategory.DateTime

fun classifyColumn(dataType: String): ColumnCategory {
    val dt = dataType.lowercase()
    return when {
        dt in setOf("bool", "boolean", "bit") -> ColumnCategory.Bool
        dt == "date" -> ColumnCategory.Date
        dt.startsWith("timestamp") ||
            dt.startsWith("datetime") ||
            dt in setOf("smalldatetime", "time") -> ColumnCategory.DateTime
        dt in setOf(
            "int", "integer", "smallint", "bigint", "mediumint", "tinyint", "serial", "bigserial",
        ) -> ColumnCategory.Integer
        dt in setOf(
            "decimal", "numeric", "number", "real", "double", "float", "float4", "float8",
            "money", "smallmoney", "double precision",
        ) ||
            dt.startsWith("decimal") ||
            dt.startsWith("numeric") ||
            dt.startsWith("number") -> ColumnCategory.Decimal
        dt.contains("binary") || dt.contains("blob") || dt in setOf("bytea", "raw") -> ColumnCategory.Binary
        dt in setOf("json", "jsonb") -> ColumnCategory.Json
        dt in setOf(
            "text", "varchar", "char", "character", "character varying", "string", "tinytext",
            "mediumtext", "longtext", "nvarchar", "nchar", "varchar2", "nvarchar2", "clob",
            "nclob", "xml", "uuid",
        ) ||
            dt.startsWith("varchar") ||
            dt.startsWith("char") ||
            dt.startsWith("nchar") ||
            dt.startsWith("nvarchar") -> ColumnCategory.Text
        else -> ColumnCategory.Other
    }
}

/** Mark which columns appear in at least one index, mutating [columns] in place. */
fun markIndexedColumns(columns: MutableList<ColumnInfo>, indexes: List<IndexInfo>) {
    val indexed = indexes.flatMap { it.columns }.toSet()
    for (index in columns.indices) {
        val column = columns[index]
        val isIndexed = column.name in indexed
        val joinEligible = indexes.any { indexInfo ->
            indexInfo.supportsEquality && indexInfo.columns.firstOrNull() == column.name
        }
        columns[index] = column.copy(
            isIndexed = isIndexed,
            joinEligible = joinEligible,
            category = classifyColumn(column.dataType),
        )
    }
}
