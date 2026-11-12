package com.safedb.mcp

import com.safedb.model.TableInfo
import com.safedb.model.TableSizeClass
import com.safedb.model.qualifiedName
import com.safedb.schema.JoinEdge
import com.safedb.schema.incomingForeignKeysFor
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable internal data class TableListResult(val tables: List<TableSummary>)

@Serializable
internal data class TableSummary(
    val schema: String,
    val name: String,
    @SerialName("qualified_name") val qualifiedName: String,
    @SerialName("size_class") val sizeClass: TableSizeClass,
    @SerialName("column_count") val columnCount: Int,
)

@Serializable
internal data class TableDetail(
    val schema: String,
    val name: String,
    @SerialName("qualified_name") val qualifiedName: String,
    val columns: List<TableColumn>,
    val indexes: List<TableIndex>,
    @SerialName("foreign_keys") val foreignKeys: List<TableForeignKey>,
    @SerialName("incoming_foreign_keys")
    val incomingForeignKeys: List<TableIncomingForeignKey> = emptyList(),
)

@Serializable
internal data class TableColumn(
    val name: String,
    @SerialName("data_type") val dataType: String,
    val nullable: Boolean,
    @SerialName("join_eligible") val joinEligible: Boolean = false,
)

@Serializable
internal data class TableIncomingForeignKey(
    val name: String,
    @SerialName("source_schema") val sourceSchema: String,
    @SerialName("source_table") val sourceTable: String,
    @SerialName("source_columns") val sourceColumns: List<String>,
    @SerialName("referenced_columns") val referencedColumns: List<String>,
    val cardinality: String,
    val optionality: String,
)

@Serializable
internal data class CatalogSearchResult(
    val query: String,
    @SerialName("match_count") val matchCount: Int,
    val truncated: Boolean,
    val matches: List<CatalogMatchSummary>,
)

@Serializable
internal data class CatalogMatchSummary(
    val schema: String,
    val name: String,
    @SerialName("qualified_name") val qualifiedName: String,
    @SerialName("size_class") val sizeClass: TableSizeClass,
    @SerialName("matched_columns") val matchedColumns: List<String>,
    @SerialName("matched_indexes") val matchedIndexes: List<String>,
    @SerialName("matched_foreign_keys") val matchedForeignKeys: List<String>,
)

@Serializable
internal data class JoinPathsResult(
    @SerialName("from_table") val fromTable: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER) @SerialName("to_table") val toTable: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val neighbors: List<JoinStepSummary>? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val paths: List<JoinPathSummary>? = null,
)

@Serializable
internal data class JoinPathSummary(
    val hops: Int,
    val steps: List<JoinStepSummary>,
)

@Serializable
internal data class JoinStepSummary(
    @SerialName("from_schema") val fromSchema: String,
    @SerialName("from_table") val fromTable: String,
    @SerialName("to_schema") val toSchema: String,
    @SerialName("to_table") val toTable: String,
    val kind: String,
    val columns: List<JoinColumnPairSummary>,
    @SerialName("on_clause") val onClause: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("foreign_key_name")
    val foreignKeyName: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val cardinality: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val optionality: String? = null,
)

@Serializable
internal data class JoinColumnPairSummary(
    @SerialName("left_column") val leftColumn: String,
    @SerialName("right_column") val rightColumn: String,
)

@Serializable
internal data class TableIndex(
    val name: String,
    val columns: List<String>,
    @SerialName("included_columns") val includedColumns: List<String>,
    val kind: String,
    @SerialName("is_unique") val isUnique: Boolean,
    @SerialName("is_primary") val isPrimary: Boolean,
)

@Serializable
internal data class TableForeignKey(
    val name: String,
    val columns: List<String>,
    @SerialName("referenced_schema") val referencedSchema: String,
    @SerialName("referenced_table") val referencedTable: String,
    @SerialName("referenced_columns") val referencedColumns: List<String>,
)

internal fun TableInfo.toSummary(): TableSummary =
    TableSummary(
        schema = schema,
        name = name,
        qualifiedName = qualifiedName(),
        sizeClass = tableSize.sizeClass,
        columnCount = columns.size,
    )

internal fun TableInfo.toDetail(tables: List<TableInfo>): TableDetail =
    TableDetail(
        schema = schema,
        name = name,
        qualifiedName = qualifiedName(),
        columns =
            columns.map {
                TableColumn(
                    name = it.name,
                    dataType = it.dataType,
                    nullable = it.nullable,
                    joinEligible = it.joinEligible,
                )
            },
        indexes =
            indexes.map {
                TableIndex(
                    name = it.name,
                    columns = it.columns,
                    includedColumns = it.includedColumns,
                    kind = it.kind,
                    isUnique = it.isUnique,
                    isPrimary = it.isPrimary,
                )
            },
        foreignKeys =
            foreignKeys.map {
                TableForeignKey(
                    name = it.name,
                    columns = it.columns,
                    referencedSchema = it.referencedSchema,
                    referencedTable = it.referencedTable,
                    referencedColumns = it.referencedColumns,
                )
            },
        incomingForeignKeys =
            incomingForeignKeysFor(tables, schema, name).map {
                TableIncomingForeignKey(
                    name = it.name,
                    sourceSchema = it.sourceSchema,
                    sourceTable = it.sourceTable,
                    sourceColumns = it.sourceColumns,
                    referencedColumns = it.referencedColumns,
                    cardinality = it.cardinality.name,
                    optionality = it.optionality.name,
                )
            },
    )

internal fun com.safedb.schema.CatalogTableMatch.toSummary(): CatalogMatchSummary =
    CatalogMatchSummary(
        schema = schema,
        name = name,
        qualifiedName = qualifiedName,
        sizeClass = sizeClass,
        matchedColumns = matchedColumns,
        matchedIndexes = matchedIndexes,
        matchedForeignKeys = matchedForeignKeys,
    )

internal fun JoinEdge.toStepSummary(): JoinStepSummary =
    JoinStepSummary(
        fromSchema = fromSchema,
        fromTable = fromTable,
        toSchema = toSchema,
        toTable = toTable,
        kind =
            when (kind) {
                com.safedb.schema.JoinCandidateKind.ForeignKey -> "foreign_key"
                com.safedb.schema.JoinCandidateKind.IndexedColumns -> "indexed_columns"
            },
        columns =
            columns.map {
                JoinColumnPairSummary(leftColumn = it.leftColumn, rightColumn = it.rightColumn)
            },
        onClause =
            columns.joinToString(separator = " AND ") {
                "$fromSchema.$fromTable.${it.leftColumn} = " +
                    "$toSchema.$toTable.${it.rightColumn}"
            },
        foreignKeyName = foreignKeyName,
        cardinality = cardinality?.name,
        optionality = optionality?.name,
    )
