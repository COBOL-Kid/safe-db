package com.safedb.testsupport

import com.safedb.model.ColumnSel
import com.safedb.model.FilterGroup
import com.safedb.model.GroupConnector
import com.safedb.model.QuerySpec
import com.safedb.model.TableRef

object IntegrationFixtures {
    fun customersQuery(limit: Int = 10): QuerySpec = QuerySpec(
        tables = listOf(TableRef(schema = mysqlDatabase(), name = "customers", alias = "t0")),
        columns = listOf(
            ColumnSel(tableAlias = "t0", column = "id"),
            ColumnSel(tableAlias = "t0", column = "email"),
        ),
        joins = emptyList(),
        filters = FilterGroup(connector = GroupConnector.And, children = emptyList()),
        limit = limit,
    )

    fun ordersQuery(limit: Int = 10): QuerySpec = QuerySpec(
        tables = listOf(TableRef(schema = mysqlDatabase(), name = "orders", alias = "t0")),
        columns = listOf(
            ColumnSel(tableAlias = "t0", column = "id"),
            ColumnSel(tableAlias = "t0", column = "status"),
        ),
        joins = emptyList(),
        filters = FilterGroup(connector = GroupConnector.And, children = emptyList()),
        limit = limit,
    )

    fun blockedSchemaQuery(): QuerySpec = QuerySpec(
        tables = listOf(TableRef(schema = "mysql", name = "user", alias = "t0")),
        columns = listOf(ColumnSel(tableAlias = "t0", column = "User")),
        joins = emptyList(),
        filters = FilterGroup(connector = GroupConnector.And, children = emptyList()),
        limit = 10,
    )

    private fun mysqlDatabase(): String = IntegrationAssumptions.mysqlDatabase
}
