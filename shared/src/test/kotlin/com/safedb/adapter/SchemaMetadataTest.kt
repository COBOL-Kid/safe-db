package com.safedb.adapter

import com.safedb.model.ColumnCategory
import com.safedb.model.ColumnInfo
import com.safedb.model.EvidenceConfidence
import com.safedb.model.IndexCapabilities
import com.safedb.model.SortDirection
import com.safedb.model.TableSizeClass
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SchemaMetadataTest {
    @Test
    fun assemblerPreservesCompositeIndexAndForeignKeyOrder() {
        val child = MetadataTableKey("public", "child")
        val schema =
            assembleSchema(
                tables = listOf(child),
                columns =
                    listOf(
                        MetadataColumn(child, ColumnInfo("a", "int", false)),
                        MetadataColumn(child, ColumnInfo("b", "int", false)),
                    ),
                indexes =
                    listOf(
                        MetadataIndex(child, "child_pk", "a", "btree", true, true, true),
                        MetadataIndex(child, "child_pk", "b", "btree", true, true, true),
                    ),
                foreignKeys =
                    listOf(
                        MetadataForeignKey(child, "child_parent_fk", "a", "public", "parent", "x"),
                        MetadataForeignKey(child, "child_parent_fk", "b", "public", "parent", "y"),
                    ),
            )

        val table = schema.tables.single()
        assertEquals(listOf("a", "b"), table.indexes.single().columns)
        assertEquals(listOf("a", "b"), table.foreignKeys.single().columns)
        assertEquals(listOf("x", "y"), table.foreignKeys.single().referencedColumns)
        assertTrue(table.columns.all { it.isIndexed && it.category == ColumnCategory.Integer })
    }

    @Test
    fun assemblerPreservesExpressionOrdinalDirectionAndIncludedColumns() {
        val tableKey = MetadataTableKey("public", "events")
        val capabilities =
            IndexCapabilities(
                equality = true,
                ordering = true,
                specializedText = false,
                expressionKeys = true,
                partialPredicate = true,
                includedColumns = true,
            )
        val schema =
            assembleSchema(
                tables = listOf(tableKey),
                columns =
                    listOf(
                        MetadataColumn(tableKey, ColumnInfo("created_at", "timestamp", false)),
                        MetadataColumn(tableKey, ColumnInfo("payload", "text", true)),
                    ),
                indexes =
                    listOf(
                        MetadataIndex(
                            tableKey,
                            "events_expr",
                            null,
                            "btree",
                            true,
                            false,
                            false,
                            SortDirection.Asc,
                            capabilities = capabilities,
                            partial = false,
                            expression = true,
                        ),
                        MetadataIndex(
                            tableKey,
                            "events_expr",
                            "created_at",
                            "btree",
                            true,
                            false,
                            false,
                            SortDirection.Desc,
                            capabilities = capabilities,
                            partial = false,
                        ),
                        MetadataIndex(
                            tableKey,
                            "events_expr",
                            "payload",
                            "btree",
                            true,
                            false,
                            false,
                            included = true,
                            capabilities = capabilities,
                            partial = false,
                        ),
                    ),
                foreignKeys = emptyList(),
            )

        val index = schema.tables.single().indexes.single()
        assertEquals(listOf(null, "created_at"), index.keys.map { it.column })
        assertEquals(listOf(SortDirection.Asc, SortDirection.Desc), index.keys.map { it.direction })
        assertEquals(listOf("payload"), index.includedColumns)
        assertEquals(true, index.capabilities.expressionKeys)
    }

    @Test
    fun tableSizeNormalizationKeepsUnavailableDistinctFromSmall() {
        assertEquals(
            TableSizeClass.Small,
            normalizeTableSize(9_999.0, EvidenceConfidence.Low).sizeClass,
        )
        assertEquals(
            TableSizeClass.Medium,
            normalizeTableSize(10_000.0, EvidenceConfidence.Low).sizeClass,
        )
        assertEquals(
            TableSizeClass.Large,
            normalizeTableSize(1_000_000.0, EvidenceConfidence.Low).sizeClass,
        )
        assertEquals(
            TableSizeClass.Unknown,
            normalizeTableSize(null, EvidenceConfidence.Low).sizeClass,
        )
    }

    @Test
    fun mysqlFullTextIndexDoesNotClaimCompatibilityWithBuilderLikePredicates() {
        val capabilities = mysqlIndexCapabilities("FULLTEXT")

        assertEquals(false, capabilities.equality)
        assertEquals(false, capabilities.ordering)
        assertEquals(false, capabilities.specializedText)
    }

    @Test
    fun postgresIndexKindsMapToSpecializedTextAndOrderingCapabilities() {
        assertEquals(true, pgIndexCapabilities("gin").specializedText)
        assertEquals(true, pgIndexCapabilities("gist").specializedText)
        assertEquals(false, pgIndexCapabilities("gin").equality)
        assertEquals(false, pgIndexCapabilities("btree").specializedText)
        assertEquals(true, pgIndexCapabilities("btree").ordering)
        assertEquals(false, pgIndexCapabilities("hash").ordering)
        assertEquals(true, pgIndexCapabilities("hash").equality)
    }

    @Test
    fun foreignKeyRowReadsTheAliasesEveryDialectQueryIsRequiredToProduce() {
        val row =
            foreignKeyRow(
                metadataRow(
                    "table_schema" to "app",
                    "table_name" to "orders",
                    "constraint_name" to "orders_customer_fk",
                    "column_name" to "customer_id",
                    "referenced_schema" to "billing",
                    "referenced_table" to "customers",
                    "referenced_column" to "id",
                )
            )

        assertEquals(MetadataTableKey("app", "orders"), row.table)
        assertEquals("orders_customer_fk", row.name)
        assertEquals("customer_id", row.column)
        assertEquals("billing", row.referencedSchema)
        assertEquals("customers", row.referencedTable)
        assertEquals("id", row.referencedColumn)
    }

    @Test
    fun columnRowHonoursDialectLabelsAndNullableSentinel() {
        val informationSchema =
            columnRow(
                metadataRow(
                    "table_schema" to "public",
                    "table_name" to "orders",
                    "column_name" to "total",
                    "data_type" to "numeric",
                    "is_nullable" to "YES",
                )
            )

        assertEquals(MetadataTableKey("public", "orders"), informationSchema.table)
        assertEquals("total", informationSchema.column.name)
        assertEquals("numeric", informationSchema.column.dataType)
        assertTrue(informationSchema.column.nullable)

        val oracle =
            columnRow(
                metadataRow(
                    "owner" to "APP",
                    "table_name" to "ORDERS",
                    "column_name" to "TOTAL",
                    "data_type" to "NUMBER",
                    "nullable" to "N",
                ),
                schemaLabel = "owner",
                nullableLabel = "nullable",
                nullableValue = "Y",
            )

        assertEquals(MetadataTableKey("APP", "ORDERS"), oracle.table)
        assertFalse(oracle.column.nullable)
    }

    @Test
    fun tableSizesReturnNoEstimatesWhenStatisticsAreUnreadable() {
        val denied =
            Proxy.newProxyInstance(
                Connection::class.java.classLoader,
                arrayOf(Connection::class.java),
            ) { _, method, _ ->
                when (method.name) {
                    "toString" -> "DeniedConnection"
                    else -> throw SQLException("statistics views are not readable")
                }
            } as Connection

        assertEquals(
            emptyMap(),
            denied.tableSizes("SELECT 1", "row_count", EvidenceConfidence.Low),
        )
    }
}

private fun metadataRow(vararg values: Pair<String, String>): ResultSet {
    val row = values.toMap()
    return Proxy.newProxyInstance(
        ResultSet::class.java.classLoader,
        arrayOf(ResultSet::class.java),
    ) { _, method, args ->
        val label = args?.firstOrNull() as? String
        when (method.name) {
            "getString" -> row[label]
            "toString" -> "MetadataRow($row)"
            else -> throw UnsupportedOperationException(method.name)
        }
    } as ResultSet
}
