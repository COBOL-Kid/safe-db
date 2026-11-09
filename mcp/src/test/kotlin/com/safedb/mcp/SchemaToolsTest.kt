package com.safedb.mcp

import com.safedb.model.ColumnInfo
import com.safedb.model.ForeignKeyInfo
import com.safedb.model.IndexInfo
import com.safedb.model.Settings
import com.safedb.model.TableInfo
import com.safedb.model.TableSizeClass
import com.safedb.model.TableSizeEstimate
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class SchemaToolsTest {
    @Test
    fun listTablesReturnsSummariesAndOmitsBlockedSchemas() = runBlocking {
        val service = catalogService()
        withTempMcpClient(service) { client ->
            val listed = client.callTool("list_tables", mapOf("connection_id" to "c1"))
            val text = listed.text()
            assertFalse(listed.isError == true)
            assertFalse(text.contains("email"))
            assertFalse(text.contains("orders_pkey"))
            assertFalse(text.contains("join_eligible"))
            assertFalse(text.contains("audit"))
            assertFalse(text.contains("pg_catalog"))
            assertFalse(text.contains("should-not-leak"))
            assertFalse(text.contains("localhost"))
            val rows = kotlinx.serialization.json.Json.parseToJsonElement(text).jsonArray
            assertEquals(2, rows.size)
            assertEquals("public", rows[0].jsonObject.getValue("schema").jsonPrimitive.content)
            assertEquals("customers", rows[0].jsonObject.getValue("name").jsonPrimitive.content)
            assertEquals(
                "public.customers",
                rows[0].jsonObject.getValue("qualified_name").jsonPrimitive.content,
            )
            assertEquals("Small", rows[0].jsonObject.getValue("size_class").jsonPrimitive.content)
            assertEquals("2", rows[0].jsonObject.getValue("column_count").jsonPrimitive.content)
            assertEquals("orders", rows[1].jsonObject.getValue("name").jsonPrimitive.content)
            assertEquals("Medium", rows[1].jsonObject.getValue("size_class").jsonPrimitive.content)
        }
    }

    @Test
    fun listAndDescribeShareTheCatalogCache() = runBlocking {
        val service = catalogService()
        withTempMcpClient(service) { client ->
            client.callTool("list_tables", mapOf("connection_id" to "c1"))
            client.callTool(
                "describe_table",
                mapOf("connection_id" to "c1", "schema" to "public", "table" to "orders"),
            )
            client.callTool("list_tables", mapOf("connection_id" to "c1"))
            assertEquals(listOf("c1"), service.schemaCalls)
        }
    }

    @Test
    fun describeTableReturnsColumnsIndexesAndForeignKeys() = runBlocking {
        val service = catalogService()
        withTempMcpClient(service) { client ->
            val described =
                client.callTool(
                    "describe_table",
                    mapOf("connection_id" to "c1", "schema" to "public", "table" to "orders"),
                )
            val text = described.text()
            assertFalse(described.isError == true)
            assertTrue(text.contains("join_eligible"))
            assertTrue(text.contains("incoming_foreign_keys"))
            assertFalse(text.contains("capabilities"))
            val parsed = kotlinx.serialization.json.Json.parseToJsonElement(text).jsonObject
            assertEquals("public", parsed.getValue("schema").jsonPrimitive.content)
            assertEquals("orders", parsed.getValue("name").jsonPrimitive.content)
            val columns = parsed.getValue("columns").jsonArray
            assertEquals("id", columns[0].jsonObject.getValue("name").jsonPrimitive.content)
            assertEquals("int", columns[0].jsonObject.getValue("data_type").jsonPrimitive.content)
            assertEquals(
                "false",
                columns[0].jsonObject.getValue("nullable").jsonPrimitive.content,
            )
            assertEquals(
                "true",
                columns[0].jsonObject.getValue("join_eligible").jsonPrimitive.content,
            )
            assertEquals(
                "false",
                columns[1].jsonObject.getValue("join_eligible").jsonPrimitive.content,
            )
            assertTrue(parsed.getValue("incoming_foreign_keys").jsonArray.isEmpty())
            val index = parsed.getValue("indexes").jsonArray.single().jsonObject
            assertEquals("orders_pkey", index.getValue("name").jsonPrimitive.content)
            assertEquals("id", index.getValue("columns").jsonArray.single().jsonPrimitive.content)
            assertEquals(
                "customer_id",
                index.getValue("included_columns").jsonArray.single().jsonPrimitive.content,
            )
            val fk = parsed.getValue("foreign_keys").jsonArray.single().jsonObject
            assertEquals("orders_customer_fk", fk.getValue("name").jsonPrimitive.content)
            assertEquals("customers", fk.getValue("referenced_table").jsonPrimitive.content)
        }
    }

    @Test
    fun refreshForcesASecondIntrospect() = runBlocking {
        val service = catalogService()
        withTempMcpClient(service) { client ->
            client.callTool("list_tables", mapOf("connection_id" to "c1"))
            client.callTool("list_tables", mapOf("connection_id" to "c1", "refresh" to true))
            assertEquals(listOf("c1", "c1"), service.schemaCalls)
        }
    }

    @Test
    fun describeRefreshesAndSurfacesRefreshFailure() = runBlocking {
        val service = catalogService()
        withTempMcpClient(service) { client ->
            val first =
                client.callTool(
                    "describe_table",
                    mapOf("connection_id" to "c1", "schema" to "public", "table" to "orders"),
                )
            assertFalse(first.isError == true)

            val refreshed =
                client.callTool(
                    "describe_table",
                    mapOf(
                        "connection_id" to "c1",
                        "schema" to "public",
                        "table" to "orders",
                        "refresh" to true,
                    ),
                )
            assertFalse(refreshed.isError == true)
            assertEquals(listOf("c1", "c1"), service.schemaCalls)

            service.schemaError = IllegalStateException("refresh failed")
            val failed =
                client.callTool(
                    "describe_table",
                    mapOf(
                        "connection_id" to "c1",
                        "schema" to "public",
                        "table" to "orders",
                        "refresh" to true,
                    ),
                )
            assertEquals(true, failed.isError)
            assertEquals("internal", failed.json().getValue("error").jsonPrimitive.content)
            assertEquals("refresh failed", failed.json().getValue("message").jsonPrimitive.content)
        }
    }

    @Test
    fun failedRefreshPreservesThePreviousCatalog() = runBlocking {
        val service = catalogService()
        withTempMcpClient(service) { client ->
            val first = client.callTool("list_tables", mapOf("connection_id" to "c1"))
            assertFalse(first.isError == true)

            service.schemaError = IllegalStateException("temporary catalog failure")
            val refresh =
                client.callTool(
                    "list_tables",
                    mapOf("connection_id" to "c1", "refresh" to true),
                )
            assertEquals(true, refresh.isError)
            assertEquals("internal", refresh.json().getValue("error").jsonPrimitive.content)
            assertEquals(
                "temporary catalog failure",
                refresh.json().getValue("message").jsonPrimitive.content,
            )

            val cached = client.callTool("list_tables", mapOf("connection_id" to "c1"))
            assertFalse(cached.isError == true)
            assertTrue(cached.text().contains("public.customers"))
            assertEquals(listOf("c1", "c1"), service.schemaCalls)
        }
    }

    @Test
    fun missingArgsUnknownTablesAndConnectionsAreErrors() = runBlocking {
        val service = catalogService()
        withTempMcpClient(service) { client ->
            val extra = client.callTool("list_tables", mapOf("connection_id" to "c1", "bogus" to 1))
            assertEquals(true, extra.isError)
            assertEquals("invalid_arguments", extra.json().getValue("error").jsonPrimitive.content)
            assertEquals(
                "unknown argument 'bogus'",
                extra.json().getValue("message").jsonPrimitive.content,
            )

            val missingId = client.callTool("list_tables", emptyMap())
            assertEquals(true, missingId.isError)
            assertEquals(
                "invalid_arguments",
                missingId.json().getValue("error").jsonPrimitive.content,
            )
            assertEquals(
                "connection_id is required",
                missingId.json().getValue("message").jsonPrimitive.content,
            )

            val connectionIdNumber = client.callTool("list_tables", mapOf("connection_id" to 5))
            assertEquals(true, connectionIdNumber.isError)
            assertEquals(
                "invalid_arguments",
                connectionIdNumber.json().getValue("error").jsonPrimitive.content,
            )
            assertEquals(
                "connection_id must be a string",
                connectionIdNumber.json().getValue("message").jsonPrimitive.content,
            )

            val refreshString =
                client.callTool(
                    "list_tables",
                    mapOf("connection_id" to "c1", "refresh" to "yes"),
                )
            assertEquals(true, refreshString.isError)
            assertEquals(
                "invalid_arguments",
                refreshString.json().getValue("error").jsonPrimitive.content,
            )
            assertEquals(
                "refresh must be a boolean",
                refreshString.json().getValue("message").jsonPrimitive.content,
            )

            val unknownConnection = client.callTool("list_tables", mapOf("connection_id" to "nope"))
            assertEquals(true, unknownConnection.isError)
            assertEquals(
                "not_found",
                unknownConnection.json().getValue("error").jsonPrimitive.content,
            )
            assertEquals(
                "Connection not found",
                unknownConnection.json().getValue("message").jsonPrimitive.content,
            )

            val missingSchema =
                client.callTool(
                    "describe_table",
                    mapOf("connection_id" to "c1", "table" to "orders"),
                )
            assertEquals(true, missingSchema.isError)
            assertEquals(
                "invalid_arguments",
                missingSchema.json().getValue("error").jsonPrimitive.content,
            )
            assertEquals(
                "schema is required",
                missingSchema.json().getValue("message").jsonPrimitive.content,
            )

            val missingTable =
                client.callTool(
                    "describe_table",
                    mapOf("connection_id" to "c1", "schema" to "public"),
                )
            assertEquals(true, missingTable.isError)
            assertEquals(
                "invalid_arguments",
                missingTable.json().getValue("error").jsonPrimitive.content,
            )
            assertEquals(
                "table is required",
                missingTable.json().getValue("message").jsonPrimitive.content,
            )

            val unknownTable =
                client.callTool(
                    "describe_table",
                    mapOf("connection_id" to "c1", "schema" to "public", "table" to "missing"),
                )
            assertEquals(true, unknownTable.isError)
            assertEquals("not_found", unknownTable.json().getValue("error").jsonPrimitive.content)
            assertEquals(
                "Table not found",
                unknownTable.json().getValue("message").jsonPrimitive.content,
            )

            val blocked =
                client.callTool(
                    "describe_table",
                    mapOf("connection_id" to "c1", "schema" to "audit", "table" to "events"),
                )
            assertEquals(true, blocked.isError)
            assertEquals("not_found", blocked.json().getValue("error").jsonPrimitive.content)
            assertEquals(
                "Table not found",
                blocked.json().getValue("message").jsonPrimitive.content,
            )
            assertFalse(blocked.text().contains("blocked", ignoreCase = true))

            val catalog =
                client.callTool(
                    "describe_table",
                    mapOf("connection_id" to "c1", "schema" to "pg_catalog", "table" to "pg_class"),
                )
            assertEquals(true, catalog.isError)
            assertEquals("not_found", catalog.json().getValue("error").jsonPrimitive.content)
            assertEquals(
                "Table not found",
                catalog.json().getValue("message").jsonPrimitive.content,
            )
            assertFalse(catalog.text().contains("oid"))
            assertFalse(catalog.text().contains("pg_class"))
        }
    }

    @Test
    fun introspectFailureIsAToolError() = runBlocking {
        val service = catalogService()
        service.schemaError = IllegalStateException("jdbc down")
        withTempMcpClient(service) { client ->
            val listed = client.callTool("list_tables", mapOf("connection_id" to "c1"))
            assertEquals(true, listed.isError)
            assertEquals("internal", listed.json().getValue("error").jsonPrimitive.content)
            assertEquals("jdbc down", listed.json().getValue("message").jsonPrimitive.content)
        }
    }

    @Test
    fun describeCustomersReportsIncomingForeignKeys() = runBlocking {
        val service = catalogService()
        withTempMcpClient(service) { client ->
            val parsed =
                client
                    .callTool(
                        "describe_table",
                        mapOf(
                            "connection_id" to "c1",
                            "schema" to "public",
                            "table" to "customers",
                        ),
                    )
                    .json()
            val incoming = parsed.getValue("incoming_foreign_keys").jsonArray
            assertEquals(1, incoming.size)
            val fk = incoming.single().jsonObject
            assertEquals("orders_customer_fk", fk.getValue("name").jsonPrimitive.content)
            assertEquals("public", fk.getValue("source_schema").jsonPrimitive.content)
            assertEquals("orders", fk.getValue("source_table").jsonPrimitive.content)
            assertEquals(
                listOf("customer_id"),
                fk.getValue("source_columns").jsonArray.map { it.jsonPrimitive.content },
            )
            assertEquals(
                listOf("id"),
                fk.getValue("referenced_columns").jsonArray.map { it.jsonPrimitive.content },
            )
            assertEquals("Unknown", fk.getValue("cardinality").jsonPrimitive.content)
            assertEquals("Required", fk.getValue("optionality").jsonPrimitive.content)
        }
    }

    @Test
    fun searchCatalogReportsMatchesTotalsAndMatchedMetadata() = runBlocking {
        val service = catalogService()
        withTempMcpClient(service) { client ->
            val parsed =
                client
                    .callTool(
                        "search_catalog",
                        mapOf("connection_id" to "c1", "query" to "customer"),
                    )
                    .json()
            assertEquals("customer", parsed.getValue("query").jsonPrimitive.content)
            assertEquals("2", parsed.getValue("match_count").jsonPrimitive.content)
            assertEquals("false", parsed.getValue("truncated").jsonPrimitive.content)
            val matches = parsed.getValue("matches").jsonArray
            assertEquals(2, matches.size)
            val customers = matches[0].jsonObject
            assertEquals("public", customers.getValue("schema").jsonPrimitive.content)
            assertEquals("customers", customers.getValue("name").jsonPrimitive.content)
            assertEquals(
                "public.customers",
                customers.getValue("qualified_name").jsonPrimitive.content,
            )
            assertEquals("Small", customers.getValue("size_class").jsonPrimitive.content)
            assertTrue(customers.getValue("matched_columns").jsonArray.isEmpty())
            val orders = matches[1].jsonObject
            assertEquals("orders", orders.getValue("name").jsonPrimitive.content)
            assertEquals(
                listOf("customer_id"),
                orders.getValue("matched_columns").jsonArray.map { it.jsonPrimitive.content },
            )
            assertEquals(
                listOf("orders_customer_fk"),
                orders.getValue("matched_foreign_keys").jsonArray.map { it.jsonPrimitive.content },
            )
        }
    }

    @Test
    fun searchCatalogLimitCapsTruncatesAndRejectsNonPositive() = runBlocking {
        val service = catalogService()
        withTempMcpClient(service) { client ->
            val limited =
                client
                    .callTool(
                        "search_catalog",
                        mapOf("connection_id" to "c1", "query" to "public", "limit" to 1),
                    )
                    .json()
            assertEquals("2", limited.getValue("match_count").jsonPrimitive.content)
            assertEquals("true", limited.getValue("truncated").jsonPrimitive.content)
            assertEquals(1, limited.getValue("matches").jsonArray.size)

            val capped =
                client.callTool(
                    "search_catalog",
                    mapOf("connection_id" to "c1", "query" to "public", "limit" to 500),
                )
            assertFalse(capped.isError == true)

            val zero =
                client.callTool(
                    "search_catalog",
                    mapOf("connection_id" to "c1", "query" to "public", "limit" to 0),
                )
            assertEquals(true, zero.isError)
            assertEquals("invalid_arguments", zero.json().getValue("error").jsonPrimitive.content)
            assertEquals(
                "limit must be 1 or more",
                zero.json().getValue("message").jsonPrimitive.content,
            )

            val negative =
                client.callTool(
                    "search_catalog",
                    mapOf("connection_id" to "c1", "query" to "public", "limit" to -2),
                )
            assertEquals(true, negative.isError)
            assertEquals(
                "limit must be 1 or more",
                negative.json().getValue("message").jsonPrimitive.content,
            )

            val unknownArg =
                client.callTool(
                    "search_catalog",
                    mapOf("connection_id" to "c1", "query" to "public", "bogus" to true),
                )
            assertEquals(true, unknownArg.isError)
            assertEquals(
                "unknown argument 'bogus'",
                unknownArg.json().getValue("message").jsonPrimitive.content,
            )

            val unknownConnection =
                client.callTool("search_catalog", mapOf("connection_id" to "nope", "query" to "x"))
            assertEquals(true, unknownConnection.isError)
            assertEquals(
                "Connection not found",
                unknownConnection.json().getValue("message").jsonPrimitive.content,
            )
        }
    }

    @Test
    fun searchCatalogHonorsTheSchemaFilter() = runBlocking {
        val service = catalogService()
        withTempMcpClient(service) { client ->
            val filtered =
                client
                    .callTool(
                        "search_catalog",
                        mapOf("connection_id" to "c1", "query" to "customer", "schema" to "audit"),
                    )
                    .json()
            assertEquals("0", filtered.getValue("match_count").jsonPrimitive.content)
            assertTrue(filtered.getValue("matches").jsonArray.isEmpty())
        }
    }

    @Test
    fun findJoinPathsReturnsDirectNeighborsWithOnClause() = runBlocking {
        val service = catalogService()
        withTempMcpClient(service) { client ->
            val parsed =
                client
                    .callTool(
                        "find_join_paths",
                        mapOf("connection_id" to "c1", "from_table" to "orders"),
                    )
                    .json()
            assertEquals("public.orders", parsed.getValue("from_table").jsonPrimitive.content)
            assertFalse(parsed.containsKey("to_table"))
            assertFalse(parsed.containsKey("paths"))
            val neighbors = parsed.getValue("neighbors").jsonArray
            assertEquals(1, neighbors.size)
            val step = neighbors.single().jsonObject
            assertEquals("public", step.getValue("from_schema").jsonPrimitive.content)
            assertEquals("orders", step.getValue("from_table").jsonPrimitive.content)
            assertEquals("public", step.getValue("to_schema").jsonPrimitive.content)
            assertEquals("customers", step.getValue("to_table").jsonPrimitive.content)
            assertEquals("foreign_key", step.getValue("kind").jsonPrimitive.content)
            assertEquals(
                "public.orders.customer_id = public.customers.id",
                step.getValue("on_clause").jsonPrimitive.content,
            )
            val columns = step.getValue("columns").jsonArray.single().jsonObject
            assertEquals("customer_id", columns.getValue("left_column").jsonPrimitive.content)
            assertEquals("id", columns.getValue("right_column").jsonPrimitive.content)
            assertEquals(
                "orders_customer_fk",
                step.getValue("foreign_key_name").jsonPrimitive.content,
            )
            assertEquals("Unknown", step.getValue("cardinality").jsonPrimitive.content)
            assertEquals("Required", step.getValue("optionality").jsonPrimitive.content)
        }
    }

    @Test
    fun findJoinPathsReturnsMultiHopPaths() = runBlocking {
        val service = joinService()
        withTempMcpClient(service) { client ->
            val parsed =
                client
                    .callTool(
                        "find_join_paths",
                        mapOf(
                            "connection_id" to "c1",
                            "from_table" to "public.customers",
                            "to_table" to "public.items",
                            "max_hops" to 2,
                        ),
                    )
                    .json()
            assertEquals("public.customers", parsed.getValue("from_table").jsonPrimitive.content)
            assertEquals("public.items", parsed.getValue("to_table").jsonPrimitive.content)
            assertFalse(parsed.containsKey("neighbors"))
            val paths = parsed.getValue("paths").jsonArray
            assertTrue(paths.isNotEmpty())
            val path = paths.first().jsonObject
            assertEquals("2", path.getValue("hops").jsonPrimitive.content)
            val steps = path.getValue("steps").jsonArray
            assertEquals(2, steps.size)
            assertEquals(
                listOf("orders", "items"),
                steps.map { it.jsonObject.getValue("to_table").jsonPrimitive.content },
            )

            val tooShort =
                client
                    .callTool(
                        "find_join_paths",
                        mapOf(
                            "connection_id" to "c1",
                            "from_table" to "public.customers",
                            "to_table" to "public.items",
                            "max_hops" to 1,
                        ),
                    )
                    .json()
            assertTrue(tooShort.getValue("paths").jsonArray.isEmpty())
        }
    }

    @Test
    fun findJoinPathsResolvesDefaultSchemaAndRejectsBadReferences() = runBlocking {
        val service = joinService()
        withTempMcpClient(service) { client ->
            val ambiguous =
                client.callTool(
                    "find_join_paths",
                    mapOf("connection_id" to "c1", "from_table" to "orders"),
                )
            assertEquals(true, ambiguous.isError)
            assertEquals(
                "invalid_arguments",
                ambiguous.json().getValue("error").jsonPrimitive.content,
            )
            assertTrue(
                ambiguous
                    .json()
                    .getValue("message")
                    .jsonPrimitive
                    .content
                    .contains("default_schema")
            )

            val explicitSchema =
                client
                    .callTool(
                        "find_join_paths",
                        mapOf(
                            "connection_id" to "c1",
                            "from_table" to "orders",
                            "default_schema" to "public",
                        ),
                    )
                    .json()
            assertEquals(
                "public.orders",
                explicitSchema.getValue("from_table").jsonPrimitive.content,
            )

            service.settings = Settings(defaultConnectionId = "c1", defaultSchema = "public")
            val fromSettings =
                client
                    .callTool(
                        "find_join_paths",
                        mapOf("connection_id" to "c1", "from_table" to "orders"),
                    )
                    .json()
            assertEquals("public.orders", fromSettings.getValue("from_table").jsonPrimitive.content)

            val missingTable =
                client.callTool(
                    "find_join_paths",
                    mapOf("connection_id" to "c1", "from_table" to "public.missing"),
                )
            assertEquals(true, missingTable.isError)
            assertEquals("not_found", missingTable.json().getValue("error").jsonPrimitive.content)
            assertEquals(
                "Table not found",
                missingTable.json().getValue("message").jsonPrimitive.content,
            )

            val missingSchema =
                client.callTool(
                    "find_join_paths",
                    mapOf("connection_id" to "c1", "from_table" to ".orders"),
                )
            assertEquals(true, missingSchema.isError)
            assertEquals(
                "invalid_arguments",
                missingSchema.json().getValue("error").jsonPrimitive.content,
            )

            val unknownConnection =
                client.callTool(
                    "find_join_paths",
                    mapOf("connection_id" to "nope", "from_table" to "orders"),
                )
            assertEquals(true, unknownConnection.isError)
            assertEquals(
                "Connection not found",
                unknownConnection.json().getValue("message").jsonPrimitive.content,
            )
        }
    }

    @Test
    fun findJoinPathsRejectsMaxHopsOutsideTheRange() = runBlocking {
        val service = catalogService()
        withTempMcpClient(service) { client ->
            for (hops in listOf(0, 4, -1)) {
                val result =
                    client.callTool(
                        "find_join_paths",
                        mapOf(
                            "connection_id" to "c1",
                            "from_table" to "orders",
                            "max_hops" to hops,
                        ),
                    )
                assertEquals(true, result.isError)
                assertEquals(
                    "invalid_arguments",
                    result.json().getValue("error").jsonPrimitive.content,
                )
                assertEquals(
                    "max_hops must be between 1 and 3",
                    result.json().getValue("message").jsonPrimitive.content,
                )
            }
        }
    }

    @Test
    fun deleteConnectionDropsTheCachedCatalog() = runBlocking {
        val service = catalogService()
        withTempMcpClient(service) { client ->
            val first = client.callTool("list_tables", mapOf("connection_id" to "c1"))
            assertFalse(first.isError == true)
            assertTrue(first.text().contains("orders"))

            client.deleteConnectionConfirmed("c1")
            val listed = client.callTool("list_tables", mapOf("connection_id" to "c1"))
            assertEquals(true, listed.isError)
            assertEquals("not_found", listed.json().getValue("error").jsonPrimitive.content)
            assertEquals(
                "Connection not found",
                listed.json().getValue("message").jsonPrimitive.content,
            )
            assertFalse(listed.text().contains("orders"))
        }
    }
}

private fun catalogService(): RecordingSafeDbService {
    val service = RecordingSafeDbService()
    service.connections += sampleMcpConnection()
    service.passwords["c1"] = "should-not-leak"
    service.schemas["c1"] = sampleMcpSchema()
    service.settings = Settings(blockedSchemas = listOf("audit"))
    return service
}

private fun joinService(): RecordingSafeDbService {
    val service = catalogService()
    val base = service.schemas.getValue("c1")
    val items =
        TableInfo(
            schema = "public",
            name = "items",
            columns =
                listOf(
                    ColumnInfo(
                        name = "id",
                        dataType = "int",
                        nullable = false,
                        joinEligible = true,
                    ),
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
    service.schemas["c1"] = base.copy(tables = base.tables + items + archivedOrders)
    return service
}

private fun CallToolResult.text(): String = (content.single() as TextContent).text

private fun CallToolResult.json(): kotlinx.serialization.json.JsonObject =
    kotlinx.serialization.json.Json.parseToJsonElement(text()).jsonObject
