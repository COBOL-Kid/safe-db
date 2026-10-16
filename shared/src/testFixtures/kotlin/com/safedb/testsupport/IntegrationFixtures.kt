package com.safedb.testsupport

import com.safedb.model.ColumnSel
import com.safedb.model.FilterGroup
import com.safedb.model.GroupConnector
import com.safedb.model.QuerySpec
import com.safedb.model.Schema
import com.safedb.model.TableInfo
import com.safedb.model.TableRef
import kotlin.test.assertTrue

object IntegrationFixtures {
    fun customersQuery(schema: Schema, limit: Int = 10, expectedSchema: String? = null): QuerySpec {
        val table = requireSeededTable(schema, "customers", setOf("id", "email"), expectedSchema)
        return QuerySpec(
            tables = listOf(TableRef(schema = table.schema, name = table.name, alias = "t0")),
            columns =
                listOf(
                    ColumnSel(tableAlias = "t0", column = seededColumn(table, "id")),
                    ColumnSel(tableAlias = "t0", column = seededColumn(table, "email")),
                ),
            joins = emptyList(),
            filters = emptyFilters("integration-customers-filters"),
            limit = limit,
        )
    }

    fun ordersQuery(schema: Schema, limit: Int = 10): QuerySpec {
        val table = requireSeededTable(schema, "orders", setOf("id", "status"))
        return QuerySpec(
            tables = listOf(TableRef(schema = table.schema, name = table.name, alias = "t0")),
            columns =
                listOf(
                    ColumnSel(tableAlias = "t0", column = seededColumn(table, "id")),
                    ColumnSel(tableAlias = "t0", column = seededColumn(table, "status")),
                ),
            joins = emptyList(),
            filters = emptyFilters("integration-orders-filters"),
            limit = limit,
        )
    }

    fun blockedSchemaQuery(): QuerySpec =
        QuerySpec(
            tables = listOf(TableRef(schema = "mysql", name = "user", alias = "t0")),
            columns = listOf(ColumnSel(tableAlias = "t0", column = "User")),
            joins = emptyList(),
            filters = emptyFilters("integration-blocked-schema-filters"),
            limit = 10,
        )

    fun requireSeededTable(
        schema: Schema,
        tableName: String,
        requiredColumns: Set<String>,
        expectedSchema: String? = null,
    ): TableInfo {
        // Unquoted names fold per engine (Db2 and Oracle uppercase, Postgres lowercases), so
        // match case-insensitively and let callers use the introspected spelling.
        val candidates = schema.tables.filter { it.name.equals(tableName, ignoreCase = true) }
        assertTrue(
            candidates.isNotEmpty(),
            "Expected seeded table '$tableName' in introspected schema",
        )

        val table =
            candidates.firstOrNull {
                it.schema == (expectedSchema ?: IntegrationAssumptions.mysqlDatabase)
            } ?: candidates.singleOrNull() ?: candidates.first()
        val columns = table.columns.map { it.name }.toSet()
        val missing = requiredColumns.filter { required ->
            columns.none { it.equals(required, ignoreCase = true) }
        }
        assertTrue(
            missing.isEmpty(),
            "Expected seeded table '${table.schema}.${table.name}' to include columns ${requiredColumns.sorted()}, " +
                "missing ${missing.sorted()} from ${columns.sorted()}",
        )
        return table
    }

    // Resolve the introspected spelling of a seeded column so compiled specs quote the name
    // the engine actually stores (e.g. CUSTOMERS.ID on Db2 instead of customers.id).
    fun seededColumn(table: TableInfo, columnName: String): String {
        val actual =
            table.columns.map { it.name }.firstOrNull { it.equals(columnName, ignoreCase = true) }
        assertTrue(
            actual != null,
            "Expected seeded table '${table.schema}.${table.name}' to include column '$columnName'",
        )
        return actual
    }

    private fun emptyFilters(id: String): FilterGroup =
        FilterGroup(id = id, connector = GroupConnector.And, children = emptyList())
}
