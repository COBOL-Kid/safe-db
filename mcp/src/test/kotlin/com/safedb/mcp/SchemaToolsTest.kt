package com.safedb.mcp

import com.safedb.model.ColumnInfo
import com.safedb.model.ForeignKeyInfo
import com.safedb.model.Schema
import com.safedb.model.TableInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
            val parsed = kotlinx.serialization.json.Json.parseToJsonElement(text).jsonObject
            assertNotNull(listed.structuredContent)
            assertEquals(parsed, listed.structuredContent)
            val rows = parsed.getValue("tables").jsonArray
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
            assertNotNull(described.structuredContent)
            assertEquals(parsed, described.structuredContent)
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
                    mapOf("connection_id" to "c1", "schema" to "public", "table" to "custmers"),
                )
            assertEquals(true, unknownTable.isError)
            assertEquals("not_found", unknownTable.json().getValue("error").jsonPrimitive.content)
            val unknownTableMessage = unknownTable.json().getValue("message").jsonPrimitive.content
            assertTrue(unknownTableMessage.startsWith("Table not found"))
            assertTrue(unknownTableMessage.contains("public.customers"))
            assertFalse(unknownTableMessage.contains("public.orders"))

            val blocked =
                client.callTool(
                    "describe_table",
                    mapOf("connection_id" to "c1", "schema" to "audit", "table" to "events"),
                )
            assertEquals(true, blocked.isError)
            assertEquals("not_found", blocked.json().getValue("error").jsonPrimitive.content)
            assertTrue(
                blocked
                    .json()
                    .getValue("message")
                    .jsonPrimitive
                    .content
                    .startsWith("Table not found"),
            )
            assertFalse(blocked.text().contains("blocked", ignoreCase = true))

            val catalog =
                client.callTool(
                    "describe_table",
                    mapOf("connection_id" to "c1", "schema" to "pg_catalog", "table" to "pg_class"),
                )
            assertEquals(true, catalog.isError)
            assertEquals("not_found", catalog.json().getValue("error").jsonPrimitive.content)
            assertTrue(
                catalog
                    .json()
                    .getValue("message")
                    .jsonPrimitive
                    .content
                    .startsWith("Table not found"),
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
    fun describeTableResolvesExactCaseWhenBothExist() = runBlocking {
        val service = catalogService()
        val columns = listOf(ColumnInfo(name = "id", dataType = "int", nullable = false))
        service.schemas["c1"] =
            Schema(
                tables =
                    listOf(
                        TableInfo(
                            schema = "public",
                            name = "Foo",
                            columns = columns,
                            indexes = emptyList(),
                        ),
                        TableInfo(
                            schema = "public",
                            name = "FOO",
                            columns = columns,
                            indexes = emptyList(),
                        ),
                    ),
            )
        withTempMcpClient(service) { client ->
            val foo =
                client
                    .callTool(
                        "describe_table",
                        mapOf("connection_id" to "c1", "schema" to "public", "table" to "Foo"),
                    )
                    .json()
            assertEquals("Foo", foo.getValue("name").jsonPrimitive.content)

            val upper =
                client
                    .callTool(
                        "describe_table",
                        mapOf("connection_id" to "c1", "schema" to "public", "table" to "FOO"),
                    )
                    .json()
            assertEquals("FOO", upper.getValue("name").jsonPrimitive.content)

            val ambiguous =
                client.callTool(
                    "describe_table",
                    mapOf("connection_id" to "c1", "schema" to "public", "table" to "foo"),
                )
            assertEquals(true, ambiguous.isError)
            assertEquals(
                "invalid_arguments",
                ambiguous.json().getValue("error").jsonPrimitive.content,
            )
            val message = ambiguous.json().getValue("message").jsonPrimitive.content
            assertTrue(message.contains("public.Foo"))
            assertTrue(message.contains("public.FOO"))
        }
    }

    @Test
    fun describeTableHidesOutgoingForeignKeysToBlockedSchemas() = runBlocking {
        val service = catalogService()
        val schema = service.schemas.getValue("c1")
        val hiddenFk =
            ForeignKeyInfo(
                name = "orders_events_fk",
                columns = listOf("customer_id"),
                referencedSchema = "audit",
                referencedTable = "events",
                referencedColumns = listOf("id"),
            )
        service.schemas["c1"] =
            schema.copy(
                tables =
                    schema.tables.map { table ->
                        if (table.name == "orders") {
                            table.copy(foreignKeys = table.foreignKeys + hiddenFk)
                        } else {
                            table
                        }
                    },
            )
        withTempMcpClient(service) { client ->
            val parsed =
                client
                    .callTool(
                        "describe_table",
                        mapOf("connection_id" to "c1", "schema" to "public", "table" to "orders"),
                    )
                    .json()
            val foreignKeys = parsed.getValue("foreign_keys").jsonArray
            assertEquals(1, foreignKeys.size)
            val fk = foreignKeys.single().jsonObject
            assertEquals("orders_customer_fk", fk.getValue("name").jsonPrimitive.content)
            assertEquals("customers", fk.getValue("referenced_table").jsonPrimitive.content)
            assertTrue(
                foreignKeys.none {
                    it.jsonObject.getValue("referenced_schema").jsonPrimitive.content == "audit"
                },
            )
            assertFalse(parsed.toString().contains("orders_events_fk"))
            assertFalse(parsed.toString().contains("audit"))
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
    fun listTablesAndCatalogHideDb2InternalTables() = runBlocking {
        val service = db2Service()
        withTempMcpClient(service) { client ->
            val listed = client.callTool("list_tables", mapOf("connection_id" to "c1"))
            assertFalse(listed.isError == true)
            val parsed =
                kotlinx.serialization.json.Json.parseToJsonElement(listed.text()).jsonObject
            assertNotNull(listed.structuredContent)
            assertEquals(parsed, listed.structuredContent)
            val rows = parsed.getValue("tables").jsonArray
            assertEquals(
                listOf("CUSTOMERS"),
                rows.map { it.jsonObject.getValue("name").jsonPrimitive.content },
            )

            val searched =
                client
                    .callTool(
                        "search_catalog",
                        mapOf("connection_id" to "c1", "query" to "EXPLAIN"),
                    )
                    .json()
            assertEquals("0", searched.getValue("match_count").jsonPrimitive.content)

            val hidden =
                client.callTool(
                    "describe_table",
                    mapOf(
                        "connection_id" to "c1",
                        "schema" to "DB2INST1",
                        "table" to "EXPLAIN_INSTANCE",
                    ),
                )
            assertEquals(true, hidden.isError)
            assertEquals("not_found", hidden.json().getValue("error").jsonPrimitive.content)
            assertFalse(hidden.text().contains("EXPLAIN_INSTANCE"))
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

    @Test
    fun listAndDescribeToolMetadataIsDocumented() = runBlocking {
        withTempMcpClient(catalogService()) { client ->
            val tools = client.listTools().tools.associateBy { it.name }
            assertEquals(true, tools.getValue("list_tables").annotations?.readOnlyHint)
            assertTrue(
                tools.getValue("list_tables").outputSchema?.properties?.containsKey("tables") ==
                    true,
            )
            assertEquals(true, tools.getValue("describe_table").annotations?.readOnlyHint)
            assertTrue(
                tools.getValue("describe_table").outputSchema?.properties?.containsKey("columns") ==
                    true,
            )
        }
    }
}
