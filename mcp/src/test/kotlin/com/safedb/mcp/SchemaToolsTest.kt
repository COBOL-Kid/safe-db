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
            assertEquals("refresh failed", failed.text())
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
            assertEquals("temporary catalog failure", refresh.text())

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
            assertEquals("connection_id is required", missingId.text())

            val unknownConnection = client.callTool("list_tables", mapOf("connection_id" to "nope"))
            assertEquals(true, unknownConnection.isError)
            assertEquals("Connection not found", unknownConnection.text())

            val missingSchema =
                client.callTool(
                    "describe_table",
                    mapOf("connection_id" to "c1", "table" to "orders"),
                )
            assertEquals(true, missingSchema.isError)
            assertEquals("schema is required", missingSchema.text())

            val missingTable =
                client.callTool(
                    "describe_table",
                    mapOf("connection_id" to "c1", "schema" to "public"),
                )
            assertEquals(true, missingTable.isError)
            assertEquals("table is required", missingTable.text())

            val unknownTable =
                client.callTool(
                    "describe_table",
                    mapOf("connection_id" to "c1", "schema" to "public", "table" to "missing"),
                )
            assertEquals(true, unknownTable.isError)
            assertEquals("Table not found", unknownTable.text())

            val blocked =
                client.callTool(
                    "describe_table",
                    mapOf("connection_id" to "c1", "schema" to "audit", "table" to "events"),
                )
            assertEquals(true, blocked.isError)
            assertEquals("Table not found", blocked.text())
            assertFalse(blocked.text().contains("blocked", ignoreCase = true))
        }
    }

    @Test
    fun introspectFailureIsAToolError() = runBlocking {
        val service = catalogService()
        service.schemaError = IllegalStateException("jdbc down")
        withTempMcpClient(service) { client ->
            val listed = client.callTool("list_tables", mapOf("connection_id" to "c1"))
            assertEquals(true, listed.isError)
            assertEquals("jdbc down", listed.text())
        }
    }

    @Test
    fun deleteConnectionDropsTheCachedCatalog() = runBlocking {
        val service = catalogService()
        withTempMcpClient(service) { client ->
            val first = client.callTool("list_tables", mapOf("connection_id" to "c1"))
            assertFalse(first.isError == true)
            assertTrue(first.text().contains("orders"))

            client.callTool("delete_connection", mapOf("connection_id" to "c1"))
            val listed = client.callTool("list_tables", mapOf("connection_id" to "c1"))
            assertEquals(true, listed.isError)
            assertEquals("Connection not found", listed.text())
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
