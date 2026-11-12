package com.safedb.schema

import com.safedb.model.ColumnInfo
import com.safedb.model.EvidenceConfidence
import com.safedb.model.ForeignKeyInfo
import com.safedb.model.IndexInfo
import com.safedb.model.IndexKey
import com.safedb.model.MetadataCoverage
import com.safedb.model.TableInfo
import com.safedb.model.TableSizeClass
import com.safedb.model.TableSizeEstimate
import com.safedb.model.classifyColumn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SchemaRelationshipsTest {
    @Test
    fun cardinalityUsesPrimaryOrNonPartialUniqueIndexOnForeignKeyColumns() {
        val foreignKey =
            foreignKey("orders_customer_fk", listOf("customer_id"), "customers", listOf("id"))
        val primary =
            table(
                name = "orders",
                columns = listOf(column("customer_id"), column("id")),
                indexes = listOf(index("orders_pkey", listOf("customer_id"), primary = true)),
                foreignKeys = listOf(foreignKey),
            )
        val unique =
            table(
                name = "orders",
                columns = listOf(column("customer_id"), column("id")),
                indexes =
                    listOf(index("orders_customer_key", listOf("customer_id"), unique = true)),
                foreignKeys = listOf(foreignKey),
            )
        val partialUnknown =
            table(
                name = "orders",
                columns = listOf(column("customer_id"), column("id")),
                indexes =
                    listOf(
                        index(
                            "orders_customer_key",
                            listOf("customer_id"),
                            unique = true,
                            partial = null,
                        )
                    ),
                foreignKeys = listOf(foreignKey),
            )
        val manyToOne =
            table(
                name = "orders",
                columns = listOf(column("customer_id"), column("id")),
                indexes = listOf(index("orders_customer_idx", listOf("customer_id"))),
                foreignKeys = listOf(foreignKey),
            )
        val incomplete =
            table(
                name = "orders",
                columns = listOf(column("customer_id"), column("id")),
                indexes = listOf(index("orders_pkey", listOf("customer_id"), primary = true)),
                foreignKeys = listOf(foreignKey),
                indexMetadata = MetadataCoverage.unavailable("not_introspected"),
            )

        assertEquals(RelationshipCardinality.OneToOne, relationshipCardinality(primary, foreignKey))
        assertEquals(RelationshipCardinality.OneToOne, relationshipCardinality(unique, foreignKey))
        assertEquals(
            RelationshipCardinality.Unknown,
            relationshipCardinality(partialUnknown, foreignKey),
        )
        assertEquals(
            RelationshipCardinality.ManyToOne,
            relationshipCardinality(manyToOne, foreignKey),
        )
        assertEquals(
            RelationshipCardinality.Unknown,
            relationshipCardinality(incomplete, foreignKey),
        )
    }

    @Test
    fun cardinalityHandlesExpressionKeysAndPartialUniqueIndexes() {
        val foreignKey = foreignKey("fk", listOf("a", "b"), "t", listOf("a", "b"))
        val expression =
            table(
                name = "source",
                columns = listOf(column("a"), column("b")),
                indexes =
                    listOf(
                        IndexInfo(
                            name = "expr",
                            isUnique = true,
                            isPartial = false,
                            keys = listOf(IndexKey(column = "a"), IndexKey(expression = true)),
                        )
                    ),
                foreignKeys = listOf(foreignKey),
            )
        val partialUnique =
            table(
                name = "source",
                columns = listOf(column("a"), column("b")),
                indexes =
                    listOf(
                        index(
                            "partial",
                            listOf("a", "b"),
                            unique = true,
                            partial = false,
                        )
                    ),
                foreignKeys = listOf(foreignKey),
            )

        assertEquals(
            RelationshipCardinality.ManyToOne,
            relationshipCardinality(expression, foreignKey),
        )
        assertEquals(
            RelationshipCardinality.OneToOne,
            relationshipCardinality(partialUnique, foreignKey),
        )
    }

    @Test
    fun optionalityDependsOnForeignKeyColumnNullability() {
        val foreignKey = foreignKey("fk", listOf("customer_id"), "customers", listOf("id"))
        val required =
            table(name = "orders", columns = listOf(column("customer_id", nullable = false)))
        val optional =
            table(name = "orders", columns = listOf(column("customer_id", nullable = true)))
        val missing = table(name = "orders", columns = listOf(column("other")))

        assertEquals(
            RelationshipOptionality.Required,
            relationshipOptionality(required, foreignKey),
        )
        assertEquals(
            RelationshipOptionality.Optional,
            relationshipOptionality(optional, foreignKey),
        )
        assertEquals(
            RelationshipOptionality.Unknown,
            relationshipOptionality(missing, foreignKey),
        )
    }

    @Test
    fun incomingForeignKeysAreCollectedAndSortedCaseInsensitively() {
        val zeta =
            table(
                schema = "sales",
                name = "Zeta",
                columns = listOf(column("customer_id")),
                foreignKeys =
                    listOf(foreignKey("zeta_fk", listOf("customer_id"), "customers", listOf("id"))),
            )
        val alpha =
            table(
                schema = "sales",
                name = "alpha",
                columns = listOf(column("customer_id")),
                foreignKeys =
                    listOf(
                        foreignKey("alpha_fk", listOf("customer_id"), "customers", listOf("id"))
                    ),
            )
        val ignored = table(name = "unrelated", columns = listOf(column("id")))

        val incoming =
            incomingForeignKeysFor(
                listOf(zeta, alpha, ignored),
                schema = "public",
                table = "customers",
            )

        assertEquals(
            listOf("sales.alpha", "sales.Zeta"),
            incoming.map { "${it.sourceSchema}.${it.sourceTable}" },
        )
        assertEquals(listOf("alpha_fk", "zeta_fk"), incoming.map { it.name })
        assertEquals(listOf("customer_id"), incoming.first().sourceColumns)
        assertEquals(listOf("id"), incoming.first().referencedColumns)
        assertTrue(incoming.all { it.cardinality == RelationshipCardinality.ManyToOne })
        assertTrue(incoming.all { it.optionality == RelationshipOptionality.Required })
    }

    @Test
    fun searchCatalogTablesMatchesEveryMetadataSurface() {
        val customers =
            table(
                schema = "public",
                name = "customers",
                columns = listOf(column("id"), column("name", dataType = "varchar")),
                indexes = listOf(index("customers_name_idx", listOf("name"))),
                sizeClass = TableSizeClass.Large,
            )
        val orders =
            table(
                schema = "public",
                name = "orders",
                columns = listOf(column("id"), column("customer_id")),
                indexes = emptyList(),
                foreignKeys =
                    listOf(
                        foreignKey(
                            "orders_customer_fk",
                            listOf("customer_id"),
                            "customers",
                            listOf("id"),
                        )
                    ),
            )
        val archive =
            table(
                schema = "archive",
                name = "customers",
                columns = listOf(column("id")),
            )

        val all = searchCatalogTables(listOf(customers, orders, archive), query = "  ")
        assertEquals(
            listOf("public.customers", "public.orders", "archive.customers"),
            all.map { it.qualifiedName },
        )
        assertTrue(all.all { it.matchedColumns.isEmpty() && it.matchedIndexes.isEmpty() })

        val filtered =
            searchCatalogTables(
                listOf(customers, orders, archive),
                query = "",
                schemaFilter = "archive",
            )
        assertEquals(listOf("archive.customers"), filtered.map { it.qualifiedName })

        val byColumn =
            searchCatalogTables(listOf(customers, orders, archive), query = "CUSTOMER_ID")
        assertEquals(listOf("public.orders"), byColumn.map { it.qualifiedName })
        assertEquals(listOf("customer_id"), byColumn.single().matchedColumns)
        assertEquals(listOf("orders_customer_fk"), byColumn.single().matchedForeignKeys)

        val byIndex = searchCatalogTables(listOf(customers, orders, archive), query = "name_idx")
        assertEquals(listOf("public.customers"), byIndex.map { it.qualifiedName })
        assertEquals(listOf("customers_name_idx"), byIndex.single().matchedIndexes)

        val byReferencedTable =
            searchCatalogTables(listOf(customers, orders, archive), query = "customers")
        assertEquals(
            listOf("public.customers", "public.orders", "archive.customers"),
            byReferencedTable.map { it.qualifiedName },
        )
        assertEquals(
            listOf("orders_customer_fk"),
            byReferencedTable.single { it.name == "orders" }.matchedForeignKeys,
        )
        assertEquals(TableSizeClass.Large, byReferencedTable.first().sizeClass)

        val byDataType = searchCatalogTables(listOf(customers, orders, archive), query = "varchar")
        assertEquals(listOf("public.customers"), byDataType.map { it.qualifiedName })
        assertEquals(listOf("name"), byDataType.single().matchedColumns)
    }

    @Test
    fun directJoinEdgesIncludeOutgoingIncomingAndIndexedCandidates() {
        val customers =
            table(
                name = "customers",
                columns =
                    listOf(
                        column("id", primary = true, joinEligible = true),
                        column("name", dataType = "varchar"),
                    ),
                indexes = listOf(index("customers_pkey", listOf("id"), primary = true)),
            )
        val orders =
            table(
                name = "orders",
                columns =
                    listOf(
                        column("id", primary = true),
                        column("customer_id", indexed = true, joinEligible = true),
                        column("total", dataType = "decimal"),
                    ),
                indexes = listOf(index("orders_pkey", listOf("id"), primary = true)),
                foreignKeys =
                    listOf(
                        foreignKey(
                            "orders_customer_fk",
                            listOf("customer_id"),
                            "customers",
                            listOf("id"),
                        )
                    ),
            )
        val balances =
            table(
                name = "balances",
                columns =
                    listOf(
                        column("id", primary = true),
                        column("customers_id", indexed = true, joinEligible = true),
                    ),
                indexes = listOf(index("balances_pkey", listOf("id"), primary = true)),
            )

        val fromOrders =
            findDirectJoinEdges(listOf(customers, orders, balances), "public", "orders")
        val outgoing = fromOrders.single { it.toTable == "customers" }
        assertEquals(JoinCandidateKind.ForeignKey, outgoing.kind)
        assertEquals(listOf(JoinColumnPair("customer_id", "id")), outgoing.columns)
        assertEquals("orders_customer_fk", outgoing.foreignKeyName)
        assertEquals(RelationshipCardinality.ManyToOne, outgoing.cardinality)
        assertEquals(RelationshipOptionality.Required, outgoing.optionality)

        val fromCustomers =
            findDirectJoinEdges(listOf(customers, orders, balances), "public", "customers")
        val incoming = fromCustomers.single { it.toTable == "orders" }
        assertEquals(JoinCandidateKind.ForeignKey, incoming.kind)
        assertEquals(listOf(JoinColumnPair("id", "customer_id")), incoming.columns)
        assertEquals("orders_customer_fk", incoming.foreignKeyName)

        val indexed = fromCustomers.single { it.toTable == "balances" }
        assertEquals(JoinCandidateKind.IndexedColumns, indexed.kind)
        assertEquals(listOf(JoinColumnPair("id", "customers_id")), indexed.columns)
        assertEquals(null, indexed.foreignKeyName)

        val foreignKeyOnly =
            findDirectJoinEdges(
                listOf(customers, orders, balances),
                "public",
                "customers",
                includeIndexedColumns = false,
            )
        assertTrue(
            foreignKeyOnly.any { it.toTable == "orders" && it.kind == JoinCandidateKind.ForeignKey }
        )
        assertTrue(foreignKeyOnly.none { it.kind == JoinCandidateKind.IndexedColumns })
        assertTrue(foreignKeyOnly.none { it.toTable == "balances" })

        val foreignKeyOnlyPaths =
            findJoinPaths(
                listOf(customers, orders, balances),
                "public",
                "customers",
                "public",
                "orders",
                maxHops = 2,
                includeIndexedColumns = false,
            )
        assertEquals(1, foreignKeyOnlyPaths.size)
        assertEquals(
            listOf("orders"),
            foreignKeyOnlyPaths.single().steps.map { it.toTable },
        )
        assertTrue(
            foreignKeyOnlyPaths.single().steps.all { it.kind == JoinCandidateKind.ForeignKey }
        )
    }

    @Test
    fun indexedCandidatesSkipExactForeignKeyMappingsAndMismatchedCategories() {
        val left =
            table(
                name = "left_table",
                columns =
                    listOf(
                        column("code", dataType = "varchar", indexed = true, joinEligible = true),
                        column("amount", dataType = "decimal", indexed = true, joinEligible = true),
                    ),
                foreignKeys =
                    listOf(foreignKey("left_fk", listOf("code"), "right_table", listOf("code"))),
            )
        val right =
            table(
                name = "right_table",
                columns =
                    listOf(
                        column("code", dataType = "varchar", indexed = true, joinEligible = true),
                        column("amount", dataType = "int", indexed = true, joinEligible = true),
                    ),
            )

        val edges = findDirectJoinEdges(listOf(left, right), "public", "left_table")

        val foreignKey = edges.single { it.kind == JoinCandidateKind.ForeignKey }
        assertEquals(listOf(JoinColumnPair("code", "code")), foreignKey.columns)
        assertTrue(edges.none { it.kind == JoinCandidateKind.IndexedColumns })
    }

    @Test
    fun indexedCandidatesSkipGenericIdToIdButKeepNamedConventionMatches() {
        val accounts =
            table(
                name = "accounts",
                columns = listOf(column("id", primary = true, joinEligible = true)),
                indexes = listOf(index("accounts_pkey", listOf("id"), primary = true)),
            )
        val sessions =
            table(
                name = "sessions",
                columns = listOf(column("id", primary = true, joinEligible = true)),
                indexes = listOf(index("sessions_pkey", listOf("id"), primary = true)),
            )

        val genericIdEdges = findDirectJoinEdges(listOf(accounts, sessions), "public", "accounts")
        assertTrue(genericIdEdges.none { it.kind == JoinCandidateKind.IndexedColumns })

        val customers =
            table(
                name = "customers",
                columns =
                    listOf(
                        column("id", primary = true, joinEligible = true),
                        column("customer_id", indexed = true, joinEligible = true),
                    ),
                indexes = listOf(index("customers_pkey", listOf("id"), primary = true)),
            )
        val invoices =
            table(
                name = "invoices",
                columns = listOf(column("customer_id", indexed = true, joinEligible = true)),
            )

        val namedEdges = findDirectJoinEdges(listOf(customers, invoices), "public", "customers")
        val sameName = namedEdges.single { it.kind == JoinCandidateKind.IndexedColumns }
        assertEquals(listOf(JoinColumnPair("customer_id", "customer_id")), sameName.columns)

        val balances =
            table(
                name = "balances",
                columns = listOf(column("customers_id", indexed = true, joinEligible = true)),
            )

        val idToTableIdEdges =
            findDirectJoinEdges(listOf(customers, balances), "public", "customers")
        val convention = idToTableIdEdges.single { it.kind == JoinCandidateKind.IndexedColumns }
        assertEquals(listOf(JoinColumnPair("id", "customers_id")), convention.columns)
    }

    @Test
    fun joinPathsFindShortestRoutesAndRespectMaxHops() {
        val customers = table(name = "customers", columns = listOf(column("id", primary = true)))
        val orders =
            table(
                name = "orders",
                columns = listOf(column("id", primary = true), column("customer_id")),
                foreignKeys =
                    listOf(
                        foreignKey(
                            "orders_customer_fk",
                            listOf("customer_id"),
                            "customers",
                            listOf("id"),
                        )
                    ),
            )
        val items =
            table(
                name = "items",
                columns = listOf(column("id", primary = true), column("order_id")),
                foreignKeys =
                    listOf(
                        foreignKey("items_order_fk", listOf("order_id"), "orders", listOf("id"))
                    ),
            )
        val tables = listOf(customers, orders, items)

        val paths = findJoinPaths(tables, "public", "customers", "public", "items", maxHops = 2)
        assertEquals(1, paths.size)
        assertEquals(
            listOf("orders", "items"),
            paths.single().steps.map { it.toTable },
        )
        assertTrue(paths.single().steps.all { it.kind == JoinCandidateKind.ForeignKey })

        assertTrue(
            findJoinPaths(tables, "public", "customers", "public", "items", maxHops = 1).isEmpty()
        )
        assertTrue(findJoinPaths(tables, "public", "customers", "public", "customers").isEmpty())
    }

    @Test
    fun joinPathsDoNotRevisitTablesAndSortByLength() {
        val a = table(name = "a", columns = listOf(column("id", primary = true), column("b_id")))
        val b =
            table(
                name = "b",
                columns = listOf(column("id", primary = true), column("a_id")),
                foreignKeys = listOf(foreignKey("b_a_fk", listOf("a_id"), "a", listOf("id"))),
            )
        val c =
            table(
                name = "c",
                columns = listOf(column("id", primary = true), column("b_id")),
                foreignKeys = listOf(foreignKey("c_b_fk", listOf("b_id"), "b", listOf("id"))),
            )
        val direct =
            table(
                name = "d",
                columns = listOf(column("id", primary = true), column("a_id")),
                foreignKeys = listOf(foreignKey("d_a_fk", listOf("a_id"), "a", listOf("id"))),
            )
        val tables = listOf(a, b, c, direct)

        val paths = findJoinPaths(tables, "public", "a", "public", "c", maxHops = 3)
        assertEquals(listOf("b", "c"), paths.first().steps.map { it.toTable })
        assertTrue(
            paths.all { path -> path.steps.map { it.toTable }.distinct().size == path.steps.size }
        )
    }
}

private fun column(
    name: String,
    dataType: String = "int",
    nullable: Boolean = false,
    indexed: Boolean = false,
    joinEligible: Boolean = false,
    primary: Boolean = false,
): ColumnInfo =
    ColumnInfo(
        name = name,
        dataType = dataType,
        nullable = nullable,
        isIndexed = indexed || primary,
        joinEligible = joinEligible,
        category = classifyColumn(dataType),
    )

private fun index(
    name: String,
    columns: List<String>,
    unique: Boolean = false,
    primary: Boolean = false,
    partial: Boolean? = false,
): IndexInfo =
    IndexInfo(
        name = name,
        columns = columns,
        isUnique = unique || primary,
        isPrimary = primary,
        isPartial = partial,
    )

private fun foreignKey(
    name: String,
    columns: List<String>,
    referencedTable: String,
    referencedColumns: List<String>,
    referencedSchema: String = "public",
): ForeignKeyInfo =
    ForeignKeyInfo(
        name = name,
        columns = columns,
        referencedSchema = referencedSchema,
        referencedTable = referencedTable,
        referencedColumns = referencedColumns,
    )

private fun table(
    schema: String = "public",
    name: String,
    columns: List<ColumnInfo>,
    indexes: List<IndexInfo> = emptyList(),
    foreignKeys: List<ForeignKeyInfo> = emptyList(),
    indexMetadata: MetadataCoverage = MetadataCoverage.complete(),
    sizeClass: TableSizeClass = TableSizeClass.Unknown,
): TableInfo =
    TableInfo(
        schema = schema,
        name = name,
        columns = columns,
        indexes = indexes,
        foreignKeys = foreignKeys,
        indexMetadata = indexMetadata,
        foreignKeyMetadata = MetadataCoverage.complete(),
        tableSize =
            TableSizeEstimate(
                sizeClass = sizeClass,
                coverage = MetadataCoverage.complete(),
                confidence = EvidenceConfidence.High,
            ),
    )
