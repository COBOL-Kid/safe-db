package com.safedb.schema

import com.safedb.model.ColumnInfo
import com.safedb.model.ForeignKeyInfo
import com.safedb.model.IndexInfo
import com.safedb.model.IndexKey
import com.safedb.model.MetadataCoverage
import com.safedb.model.Schema
import com.safedb.model.SortDirection
import com.safedb.model.TableInfo
import com.safedb.ui.schemaMapRelationshipGeometries
import com.safedb.ui.schemaMapRelationshipHitRegions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SchemaMapGraphTest {
    @Test
    fun requiredNonUniqueForeignKeyIsManyToOne() {
        val graph =
            buildSchemaMapGraph(
                Schema(
                    listOf(
                        parent(),
                        table(
                            "orders",
                            columns = listOf(column("id"), column("customer_id")),
                            indexes = listOf(primary("orders_pkey", "id")),
                            foreignKeys = listOf(foreignKey("orders_customer_fk", "customer_id")),
                        ),
                    )
                ),
                "public",
            )

        val relationship = graph.relationships.single()
        assertEquals(SchemaMapCardinality.ManyToOne, relationship.cardinality)
        assertEquals(SchemaMapOptionality.Required, relationship.optionality)
        assertTrue(relationship.description.contains("many to one"))
        assertTrue(relationship.description.contains("required"))
    }

    @Test
    fun nullableExactCompositeUniqueForeignKeyIsOptionalOneToOne() {
        val graph =
            buildSchemaMapGraph(
                Schema(
                    listOf(
                        parent(columns = listOf(column("tenant_id"), column("id"))),
                        table(
                            "profile",
                            columns =
                                listOf(
                                    column("id"),
                                    column("tenant_id", nullable = true),
                                    column("customer_id"),
                                ),
                            indexes =
                                listOf(
                                    primary("profile_pkey", "id"),
                                    IndexInfo(
                                        "profile_customer_key",
                                        listOf("customer_id", "tenant_id"),
                                        isUnique = true,
                                        isPartial = false,
                                    ),
                                ),
                            foreignKeys =
                                listOf(
                                    ForeignKeyInfo(
                                        "profile_customer_fk",
                                        listOf("tenant_id", "customer_id"),
                                        "public",
                                        "customers",
                                        listOf("tenant_id", "id"),
                                    )
                                ),
                        ),
                    )
                ),
                "public",
            )

        val relationship = graph.relationships.single()
        assertEquals(SchemaMapCardinality.OneToOne, relationship.cardinality)
        assertEquals(SchemaMapOptionality.Optional, relationship.optionality)
    }

    @Test
    fun incompleteIndexCoverageDoesNotGuessNonUniqueCardinality() {
        val child =
            table(
                "orders",
                columns = listOf(column("customer_id")),
                foreignKeys = listOf(foreignKey("orders_customer_fk", "customer_id")),
                indexCoverage = MetadataCoverage.unavailable("permission_denied"),
            )

        val relationship =
            buildSchemaMapGraph(Schema(listOf(parent(), child)), "public").relationships.single()

        assertEquals(SchemaMapCardinality.Unknown, relationship.cardinality)
        assertTrue(relationship.description.contains("cardinality unknown"))
    }

    @Test
    fun unsafeUniqueIndexesDoNotImplyForeignKeyUniquenessAndKeepKeyOrder() {
        val expressionIndex =
            IndexInfo(
                name = "orders_expression_key",
                isUnique = true,
                isPartial = false,
                keys =
                    listOf(
                        IndexKey(column = null, expression = true),
                        IndexKey("customer_id", SortDirection.Desc),
                    ),
            )
        val child =
            table(
                "orders",
                columns = listOf(column("customer_id")),
                indexes = listOf(expressionIndex),
                foreignKeys = listOf(foreignKey("orders_customer_fk", "customer_id")),
            )

        val relationship =
            buildSchemaMapGraph(Schema(listOf(parent(), child)), "public").relationships.single()

        assertEquals(SchemaMapCardinality.ManyToOne, relationship.cardinality)
        assertTrue(indexTooltip(expressionIndex).contains("expression, customer_id DESC"))

        fun cardinalityFor(index: IndexInfo): SchemaMapCardinality {
            val indexedChild =
                table(
                    "orders",
                    columns = listOf(column("customer_id")),
                    indexes = listOf(index),
                    foreignKeys = listOf(foreignKey("orders_customer_fk", "customer_id")),
                )
            return buildSchemaMapGraph(Schema(listOf(parent(), indexedChild)), "public")
                .relationships
                .single()
                .cardinality
        }
        assertEquals(
            SchemaMapCardinality.ManyToOne,
            cardinalityFor(
                IndexInfo(
                    "orders_partial_key",
                    listOf("customer_id"),
                    isUnique = true,
                    isPartial = true,
                )
            ),
        )
        assertEquals(
            SchemaMapCardinality.Unknown,
            cardinalityFor(
                IndexInfo(
                    "orders_unknown_predicate_key",
                    listOf("customer_id"),
                    isUnique = true,
                    isPartial = null,
                )
            ),
        )
        assertEquals(
            SchemaMapCardinality.OneToOne,
            cardinalityFor(
                IndexInfo(
                    "orders_customer_pkey",
                    listOf("customer_id"),
                    isUnique = true,
                    isPrimary = true,
                )
            ),
        )
    }

    @Test
    fun externalTargetsAreDeduplicatedAndAccumulateReferencedColumns() {
        val first =
            table(
                "orders",
                listOf(column("created_by")),
                foreignKeys =
                    listOf(
                        ForeignKeyInfo(
                            "orders_creator_fk",
                            listOf("created_by"),
                            "identity",
                            "users",
                            listOf("id"),
                        )
                    ),
            )
        val second =
            table(
                "audit",
                listOf(column("actor_email")),
                foreignKeys =
                    listOf(
                        ForeignKeyInfo(
                            "audit_actor_fk",
                            listOf("actor_email"),
                            "identity",
                            "users",
                            listOf("email"),
                        )
                    ),
            )

        val graph = buildSchemaMapGraph(Schema(listOf(first, second)), "public")

        val external = graph.nodes.single { it.isExternal }
        assertEquals("identity.users", external.id)
        assertEquals(setOf("id", "email"), external.externalColumns.toSet())
        assertEquals(2, graph.relationships.size)
    }

    @Test
    fun markersSeparatePrimaryUniqueForeignAndOrdinaryIndexes() {
        val table =
            table(
                "orders",
                columns =
                    listOf(
                        column("id"),
                        column("email"),
                        column("customer_id"),
                        column("covering_value"),
                    ),
                indexes =
                    listOf(
                        primary("orders_pkey", "id"),
                        IndexInfo(
                            "orders_email_key",
                            listOf("email"),
                            isUnique = true,
                            isPartial = true,
                        ),
                        IndexInfo(
                            "orders_customer_idx",
                            listOf("customer_id"),
                            includedColumns = listOf("covering_value"),
                        ),
                    ),
                foreignKeys = listOf(foreignKey("orders_customer_fk", "customer_id")),
            )

        val node =
            buildSchemaMapGraph(Schema(listOf(parent(), table)), "public").nodes.single {
                it.id == "public.orders"
            }
        fun kinds(column: String) =
            node.columns.single { it.column.name == column }.markers.map { it.kind }

        assertEquals(listOf(SchemaMapColumnMarkerKind.PrimaryKey), kinds("id"))
        assertEquals(listOf(SchemaMapColumnMarkerKind.Unique), kinds("email"))
        assertEquals(
            listOf(SchemaMapColumnMarkerKind.ForeignKey, SchemaMapColumnMarkerKind.Index),
            kinds("customer_id"),
        )
        assertEquals(listOf(SchemaMapColumnMarkerKind.Index), kinds("covering_value"))
        assertFalse(SchemaMapColumnMarkerKind.Unique in kinds("id"))
        val uniqueTooltip =
            node.columns
                .single { it.column.name == "email" }
                .markers
                .single { it.kind == SchemaMapColumnMarkerKind.Unique }
                .tooltip
        assertTrue(uniqueTooltip.contains("Partial unique index orders_email_key"))
        assertTrue(uniqueTooltip.contains("partial predicate"))
    }

    @Test
    fun searchMatchesTablesColumnsIndexesAndForeignKeys() {
        val orders =
            table(
                "orders",
                columns = listOf(column("id"), ColumnInfo("placed_at", "timestamp", false)),
                indexes =
                    listOf(
                        primary("orders_pkey", "id"),
                        IndexInfo("orders_placed_idx", listOf("placed_at")),
                    ),
                foreignKeys = listOf(foreignKey("orders_customer_fk", "id")),
            )
        val graph = buildSchemaMapGraph(Schema(listOf(parent(), orders)), "public")

        assertEquals(setOf("public.orders"), searchSchemaMap(graph, "timestamp").nodeIds)
        assertEquals(setOf("public.orders"), searchSchemaMap(graph, "placed_idx").nodeIds)
        val relationshipMatch = searchSchemaMap(graph, "customer_fk")
        assertEquals(1, relationshipMatch.relationshipIds.size)
        assertTrue("public.orders" in relationshipMatch.nodeIds)
        assertTrue("public.customers" in relationshipMatch.nodeIds)
    }

    @Test
    fun layoutIsDeterministicAndNonOverlappingForCyclesAndIsolates() {
        val a =
            table(
                "a",
                listOf(column("id"), column("b_id")),
                foreignKeys =
                    listOf(ForeignKeyInfo("a_b_fk", listOf("b_id"), "public", "b", listOf("id"))),
            )
        val b =
            table(
                "b",
                listOf(column("id"), column("a_id")),
                foreignKeys =
                    listOf(ForeignKeyInfo("b_a_fk", listOf("a_id"), "public", "a", listOf("id"))),
            )
        val graph =
            buildSchemaMapGraph(
                Schema(listOf(a, b, table("isolated", listOf(column("id"))))),
                "public",
            )
        val sizes = graph.nodes.associate { it.id to SchemaMapSize(180f, 100f) }

        val first = layoutSchemaMap(graph, sizes)
        val second = layoutSchemaMap(graph, sizes)

        assertEquals(first, second)
        for ((leftId, leftPoint) in first) {
            for ((rightId, rightPoint) in first) {
                if (leftId >= rightId) continue
                val overlaps =
                    leftPoint.x < rightPoint.x + 180f &&
                        leftPoint.x + 180f > rightPoint.x &&
                        leftPoint.y < rightPoint.y + 100f &&
                        leftPoint.y + 100f > rightPoint.y
                assertFalse(overlaps, "$leftId and $rightId overlap")
            }
        }
    }

    @Test
    fun selfRelationshipRoutesOutsideItsTable() {
        val employees =
            table(
                "employees",
                listOf(
                    column("id"),
                    column("manager_id", nullable = true),
                    column("mentor_id", nullable = true),
                ),
                indexes = listOf(primary("employees_pkey", "id")),
                foreignKeys =
                    listOf(
                        ForeignKeyInfo(
                            "employees_manager_fk",
                            listOf("manager_id"),
                            "public",
                            "employees",
                            listOf("id"),
                        ),
                        ForeignKeyInfo(
                            "employees_mentor_fk",
                            listOf("mentor_id"),
                            "public",
                            "employees",
                            listOf("id"),
                        ),
                    ),
            )
        val graph = buildSchemaMapGraph(Schema(listOf(employees)), "public")

        val geometries =
            schemaMapRelationshipGeometries(
                    graph,
                    mapOf("public.employees" to SchemaMapPoint(10f, 20f)),
                    mapOf("public.employees" to SchemaMapSize(200f, 100f)),
                )
                .values

        assertEquals(2, geometries.size)
        assertTrue(geometries.all { geometry -> geometry.bends.all { it.x > 210f } })
        assertTrue(geometries.all { it.anchor.x > 210f })
        assertTrue(geometries.all { it.source != it.target })
        assertEquals(2, geometries.map { it.bends.first().x }.toSet().size)
    }

    @Test
    fun parallelRelationshipsReceiveDistinctLanesAndTooltipAnchors() {
        val audit =
            table(
                "audit",
                listOf(column("created_by"), column("updated_by")),
                foreignKeys =
                    listOf(
                        foreignKey("audit_created_by_fk", "created_by"),
                        foreignKey("audit_updated_by_fk", "updated_by"),
                    ),
            )
        val graph = buildSchemaMapGraph(Schema(listOf(parent(), audit)), "public")

        val geometry =
            schemaMapRelationshipGeometries(
                graph,
                mapOf(
                    "public.customers" to SchemaMapPoint(0f, 0f),
                    "public.audit" to SchemaMapPoint(400f, 0f),
                ),
                mapOf(
                    "public.customers" to SchemaMapSize(200f, 120f),
                    "public.audit" to SchemaMapSize(200f, 120f),
                ),
            )

        assertEquals(2, geometry.size)
        assertEquals(2, geometry.values.map { it.source.y }.toSet().size)
        assertEquals(2, geometry.values.map { it.anchor }.toSet().size)
    }

    @Test
    fun relationshipHitRegionsCoverHorizontalAndVerticalLineSegments() {
        val geometry =
            com.safedb.ui.SchemaMapRelationshipGeometry(
                source = SchemaMapPoint(10f, 20f),
                target = SchemaMapPoint(210f, 120f),
                bends = listOf(SchemaMapPoint(110f, 20f), SchemaMapPoint(110f, 120f)),
                anchor = SchemaMapPoint(110f, 70f),
                sourceTowardRight = true,
                targetTowardRight = false,
            )

        val regions = schemaMapRelationshipHitRegions(geometry, zoom = 0.5f)

        assertEquals(3, regions.size)
        assertTrue(regions[0].contains(SchemaMapPoint(60f, 20f)))
        assertTrue(regions[1].contains(SchemaMapPoint(110f, 70f)))
        assertTrue(regions[2].contains(SchemaMapPoint(160f, 120f)))
        assertTrue(regions.none { it.contains(SchemaMapPoint(60f, 70f)) })
        assertEquals(28f, regions[0].height)
        assertEquals(28f, regions[1].width)
    }

    @Test
    fun malformedCompositeForeignKeyIsSkipped() {
        val broken =
            table(
                "broken",
                listOf(column("a"), column("b")),
                foreignKeys =
                    listOf(
                        ForeignKeyInfo(
                            "broken_fk",
                            listOf("a", "b"),
                            "public",
                            "customers",
                            listOf("id"),
                        )
                    ),
            )

        val graph = buildSchemaMapGraph(Schema(listOf(parent(), broken)), "public")

        assertTrue(graph.relationships.isEmpty())
        assertNotNull(graph.nodes.singleOrNull { it.id == "public.broken" })
    }

    private fun parent(columns: List<ColumnInfo> = listOf(column("id"))) =
        table(
            "customers",
            columns,
            listOf(primary("customers_pkey", *columns.map { it.name }.toTypedArray())),
        )

    private fun table(
        name: String,
        columns: List<ColumnInfo>,
        indexes: List<IndexInfo> = emptyList(),
        foreignKeys: List<ForeignKeyInfo> = emptyList(),
        indexCoverage: MetadataCoverage = MetadataCoverage.complete(),
    ) =
        TableInfo(
            schema = "public",
            name = name,
            columns = columns,
            indexes = indexes,
            foreignKeys = foreignKeys,
            indexMetadata = indexCoverage,
            foreignKeyMetadata = MetadataCoverage.complete(),
        )

    private fun column(name: String, nullable: Boolean = false) =
        ColumnInfo(name, "bigint", nullable)

    private fun primary(name: String, vararg columns: String) =
        IndexInfo(
            name,
            columns.toList(),
            isUnique = true,
            isPrimary = true,
            isPartial = false,
        )

    private fun foreignKey(name: String, column: String) =
        ForeignKeyInfo(name, listOf(column), "public", "customers", listOf("id"))
}
