package com.safedb.mcp

import com.safedb.model.Dialect
import com.safedb.model.TableInfo
import com.safedb.query.isSchemaBlocked

private val DB2_INTERNAL_CATALOG_PREFIXES = listOf("EXPLAIN_", "ADVISE_")

internal fun isInternalCatalogTable(dialect: Dialect, table: TableInfo): Boolean =
    dialect == Dialect.Db2 &&
        DB2_INTERNAL_CATALOG_PREFIXES.any { table.name.startsWith(it, ignoreCase = true) }

internal fun visibleTables(
    tables: List<TableInfo>,
    blockedSchemas: List<String>,
    dialect: Dialect,
): List<TableInfo> =
    tables
        .filter {
            !isSchemaBlocked(it.schema, blockedSchemas) && !isInternalCatalogTable(dialect, it)
        }
        .sortedWith(
            compareBy<TableInfo, String>(String.CASE_INSENSITIVE_ORDER) { it.schema }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        )
