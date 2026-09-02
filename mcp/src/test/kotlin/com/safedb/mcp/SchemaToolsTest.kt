package com.safedb.mcp

import com.safedb.model.Settings
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
            assertFalse(text.contains("join_eligible"))
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

private fun CallToolResult.text(): String = (content.single() as TextContent).text

private fun CallToolResult.json(): kotlinx.serialization.json.JsonObject =
    kotlinx.serialization.json.Json.parseToJsonElement(text()).jsonObject
