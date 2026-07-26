package com.safedb.adapter

import com.safedb.model.ColumnInfo
import com.safedb.model.ForeignKeyInfo
import com.safedb.model.IndexInfo
import com.safedb.model.Schema
import com.safedb.model.TableInfo
import com.safedb.model.markIndexedColumns

internal data class MetadataTableKey(val schema: String, val table: String)
internal data class MetadataColumn(val table: MetadataTableKey, val column: ColumnInfo)
internal data class MetadataIndex(
    val table: MetadataTableKey,
    val name: String,
    val column: String,
    val kind: String,
    val supportsEquality: Boolean,
    val unique: Boolean,
    val primary: Boolean,
)
internal data class MetadataForeignKey(
    val table: MetadataTableKey,
    val name: String,
    val column: String,
    val referencedSchema: String,
    val referencedTable: String,
    val referencedColumn: String,
)

internal fun assembleSchema(
    tables: List<MetadataTableKey>,
    columns: List<MetadataColumn>,
    indexes: List<MetadataIndex>,
    foreignKeys: List<MetadataForeignKey>,
): Schema {
    val columnsByTable = columns.groupBy(MetadataColumn::table)
    val indexesByTable = indexes.groupBy(MetadataIndex::table)
    val foreignKeysByTable = foreignKeys.groupBy(MetadataForeignKey::table)
    return Schema(
        tables.map { table ->
            val tableIndexes = indexesByTable[table].orEmpty()
                .groupBy { it.name }
                .map { (name, rows) ->
                    val first = rows.first()
                    IndexInfo(
                        name = name,
                        columns = rows.map { it.column },
                        kind = first.kind,
                        supportsEquality = first.supportsEquality,
                        isUnique = first.unique,
                        isPrimary = first.primary,
                    )
                }
            val tableColumns = columnsByTable[table].orEmpty().map { it.column }.toMutableList()
            markIndexedColumns(tableColumns, tableIndexes)
            val tableForeignKeys = foreignKeysByTable[table].orEmpty()
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
            TableInfo(table.schema, table.table, tableColumns, tableIndexes, tableForeignKeys)
        },
    )
}
