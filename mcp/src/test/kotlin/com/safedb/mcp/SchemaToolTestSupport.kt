package com.safedb.mcp

import com.safedb.model.ColumnInfo
import com.safedb.model.Dialect
import com.safedb.model.ForeignKeyInfo
import com.safedb.model.IndexInfo
import com.safedb.model.Schema
import com.safedb.model.Settings
import com.safedb.model.TableInfo
import com.safedb.model.TableSizeClass
import com.safedb.model.TableSizeEstimate

internal fun catalogService(): RecordingSafeDbService {
    val service = RecordingSafeDbService()
    service.connections += sampleMcpConnection()
    service.passwords["c1"] = "should-not-leak"
    service.schemas["c1"] = sampleMcpSchema()
    service.settings = Settings(blockedSchemas = listOf("audit"))
    return service
}

internal fun joinService(): RecordingSafeDbService {
    val service = catalogService()
    val base = service.schemas.getValue("c1")
    val items =
        TableInfo(
            schema = "public",
            name = "items",
            columns =
                listOf(
                    ColumnInfo(name = "id", dataType = "int", nullable = false),
                    ColumnInfo(name = "order_id", dataType = "int", nullable = false),
                ),
            indexes =
                listOf(
                    IndexInfo(
                        name = "items_pkey",
                        columns = listOf("id"),
                        kind = "btree",
                        isUnique = true,
                        isPrimary = true,
                    )
                ),
            foreignKeys =
                listOf(
                    ForeignKeyInfo(
                        name = "items_order_fk",
                        columns = listOf("order_id"),
                        referencedSchema = "public",
                        referencedTable = "orders",
                        referencedColumns = listOf("id"),
                    )
                ),
            tableSize = TableSizeEstimate(sizeClass = TableSizeClass.Medium),
        )
    val archivedOrders =
        TableInfo(
            schema = "archive_orders",
            name = "orders",
            columns = listOf(ColumnInfo(name = "id", dataType = "int", nullable = false)),
            indexes = emptyList(),
        )
    val balances =
        TableInfo(
            schema = "public",
            name = "balances",
            columns =
                listOf(
                    ColumnInfo(name = "id", dataType = "int", nullable = false),
                    ColumnInfo(
                        name = "customers_id",
                        dataType = "int",
                        nullable = false,
                        joinEligible = true,
                    ),
                ),
            indexes =
                listOf(
                    IndexInfo(
                        name = "balances_pkey",
                        columns = listOf("id"),
                        kind = "btree",
                        isUnique = true,
                        isPrimary = true,
                    )
                ),
            tableSize = TableSizeEstimate(sizeClass = TableSizeClass.Medium),
        )
    val tables =
        base.tables.map { table ->
            if (table.name == "customers") {
                table.copy(
                    columns =
                        table.columns.map {
                            if (it.name == "id") it.copy(joinEligible = true) else it
                        }
                )
            } else {
                table
            }
        }
    service.schemas["c1"] = base.copy(tables = tables + items + archivedOrders + balances)
    return service
}

internal fun db2Service(): RecordingSafeDbService {
    val service = RecordingSafeDbService()
    service.connections += sampleMcpConnection(dialect = Dialect.Db2)
    val columns = listOf(ColumnInfo(name = "id", dataType = "int", nullable = false))
    service.schemas["c1"] =
        Schema(
            tables =
                listOf(
                    TableInfo(
                        schema = "DB2INST1",
                        name = "CUSTOMERS",
                        columns = columns,
                        indexes = emptyList(),
                    ),
                    TableInfo(
                        schema = "DB2INST1",
                        name = "EXPLAIN_INSTANCE",
                        columns = columns,
                        indexes = emptyList(),
                    ),
                    TableInfo(
                        schema = "DB2INST1",
                        name = "ADVISE_INDEX",
                        columns = columns,
                        indexes = emptyList(),
                    ),
                )
        )
    return service
}
