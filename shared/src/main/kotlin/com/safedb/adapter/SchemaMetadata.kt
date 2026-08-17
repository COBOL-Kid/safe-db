package com.safedb.adapter

import com.safedb.model.ColumnInfo
import com.safedb.model.EvidenceConfidence
import com.safedb.model.ForeignKeyInfo
import com.safedb.model.IndexCapabilities
import com.safedb.model.IndexInfo
import com.safedb.model.IndexKey
import com.safedb.model.LARGE_TABLE_ROW_ESTIMATE
import com.safedb.model.MetadataCoverage
import com.safedb.model.Schema
import com.safedb.model.SortDirection
import com.safedb.model.TableInfo
import com.safedb.model.TableSizeClass
import com.safedb.model.TableSizeEstimate
import com.safedb.model.markIndexedColumns

internal data class MetadataTableKey(val schema: String, val table: String)

internal data class MetadataColumn(val table: MetadataTableKey, val column: ColumnInfo)

internal data class MetadataIndex(
    val table: MetadataTableKey,
    val name: String,
    val column: String?,
    val kind: String,
    val supportsEquality: Boolean,
    val unique: Boolean,
    val primary: Boolean,
    val direction: SortDirection? = null,
    val included: Boolean = false,
    val capabilities: IndexCapabilities =
        IndexCapabilities(
            equality = supportsEquality,
            ordering = null,
            specializedText = null,
            expressionKeys = null,
            partialPredicate = null,
            includedColumns = null,
        ),
    val partial: Boolean? = null,
    val expression: Boolean = column == null,
)

internal data class MetadataForeignKey(
    val table: MetadataTableKey,
    val name: String,
    val column: String,
    val referencedSchema: String,
    val referencedTable: String,
    val referencedColumn: String,
)

internal fun normalizeTableSize(
    rowEstimate: Double?,
    confidence: EvidenceConfidence,
): TableSizeEstimate =
    when {
        rowEstimate == null || !rowEstimate.isFinite() || rowEstimate < 0.0 ->
            TableSizeEstimate(coverage = MetadataCoverage.unavailable("row_estimate_unavailable"))
        rowEstimate < 10_000.0 ->
            TableSizeEstimate(TableSizeClass.Small, MetadataCoverage.complete(), confidence)
        rowEstimate < LARGE_TABLE_ROW_ESTIMATE.toDouble() ->
            TableSizeEstimate(TableSizeClass.Medium, MetadataCoverage.complete(), confidence)
        else -> TableSizeEstimate(TableSizeClass.Large, MetadataCoverage.complete(), confidence)
    }

internal fun assembleSchema(
    tables: List<MetadataTableKey>,
    columns: List<MetadataColumn>,
    indexes: List<MetadataIndex>,
    foreignKeys: List<MetadataForeignKey>,
    tableSizes: Map<MetadataTableKey, TableSizeEstimate> = emptyMap(),
    indexCoverage: MetadataCoverage = MetadataCoverage.complete(),
    foreignKeyCoverage: MetadataCoverage = MetadataCoverage.complete(),
): Schema {
    val columnsByTable = columns.groupBy(MetadataColumn::table)
    val indexesByTable = indexes.groupBy(MetadataIndex::table)
    val foreignKeysByTable = foreignKeys.groupBy(MetadataForeignKey::table)
    return Schema(
        tables.map { table ->
            val tableIndexes =
                indexesByTable[table]
                    .orEmpty()
                    .groupBy { it.name }
                    .map { (name, rows) ->
                        val first = rows.first()
                        IndexInfo(
                            name = name,
                            columns =
                                rows
                                    .filterNot(MetadataIndex::included)
                                    .mapNotNull(MetadataIndex::column),
                            includedColumns =
                                rows
                                    .filter(MetadataIndex::included)
                                    .mapNotNull(MetadataIndex::column),
                            kind = first.kind,
                            supportsEquality = first.supportsEquality,
                            isUnique = first.unique,
                            isPrimary = first.primary,
                            keys =
                                rows.filterNot(MetadataIndex::included).map { row ->
                                    IndexKey(row.column, row.direction, row.expression)
                                },
                            capabilities = first.capabilities,
                            isPartial = first.partial,
                        )
                    }
            val tableColumns = columnsByTable[table].orEmpty().map { it.column }.toMutableList()
            markIndexedColumns(tableColumns, tableIndexes)
            val tableForeignKeys =
                foreignKeysByTable[table]
                    .orEmpty()
                    .groupBy { Triple(it.name, it.referencedSchema, it.referencedTable) }
                    .map { (key, rows) ->
                        ForeignKeyInfo(
                            name = key.first,
                            columns = rows.map { it.column },
                            referencedSchema = key.second,
                            referencedTable = key.third,
                            referencedColumns = rows.map { it.referencedColumn },
                        )
                    }
            TableInfo(
                table.schema,
                table.table,
                tableColumns,
                tableIndexes,
                tableForeignKeys,
                indexMetadata = indexCoverage,
                foreignKeyMetadata = foreignKeyCoverage,
                tableSize = tableSizes[table] ?: TableSizeEstimate(),
            )
        }
    )
}
