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
    fun customersQuery(schema: Schema, limit: Int = 10): QuerySpec {
        val table = requireSeededTable(schema, "customers", setOf("id", "email"))
        return QuerySpec(
            tables = listOf(TableRef(schema = table.schema, name = table.name, alias = "t0")),
            columns = listOf(
                ColumnSel(tableAlias = "t0", column = "id"),
                ColumnSel(tableAlias = "t0", column = "email"),
            ),
            joins = emptyList(),
            filters = FilterGroup(connector = GroupConnector.And, children = emptyList()),
            limit = limit,
        )
    }

    fun ordersQuery(schema: Schema, limit: Int = 10): QuerySpec {
        val table = requireSeededTable(schema, "orders", setOf("id", "status"))
        return QuerySpec(
            tables = listOf(TableRef(schema = table.schema, name = table.name, alias = "t0")),
            columns = listOf(
                ColumnSel(tableAlias = "t0", column = "id"),
                ColumnSel(tableAlias = "t0", column = "status"),
            ),
            joins = emptyList(),
            filters = FilterGroup(connector = GroupConnector.And, children = emptyList()),
            limit = limit,
        )
    }

    fun blockedSchemaQuery(): QuerySpec = QuerySpec(
        tables = listOf(TableRef(schema = "mysql", name = "user", alias = "t0")),
        columns = listOf(ColumnSel(tableAlias = "t0", column = "User")),
        joins = emptyList(),
        filters = FilterGroup(connector = GroupConnector.And, children = emptyList()),
        limit = 10,
    )

    fun requireSeededTable(
        schema: Schema,
        tableName: String,
        requiredColumns: Set<String>,
    ): TableInfo {
        val candidates = schema.tables.filter { it.name == tableName }
        assertTrue(candidates.isNotEmpty(), "Expected seeded table '$tableName' in introspected schema")

        val table = candidates.firstOrNull { it.schema == IntegrationAssumptions.mysqlDatabase }
            ?: candidates.singleOrNull()
            ?: candidates.first()
        val columns = table.columns.map { it.name }.toSet()
        val missing = requiredColumns - columns
        assertTrue(
            missing.isEmpty(),
            "Expected seeded table '${table.schema}.${table.name}' to include columns ${requiredColumns.sorted()}, " +
                "missing ${missing.sorted()} from ${columns.sorted()}",
        )
        return table
    }
}
