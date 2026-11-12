package com.safedb.mcp

import com.safedb.model.ForeignKeyInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class SchemaSearchToolsTest {
    @Test
    fun searchCatalogReportsMatchesTotalsAndMatchedMetadata() = runBlocking {
        val service = catalogService()
        withTempMcpClient(service) { client ->
            val result =
                client.callTool(
                    "search_catalog",
                    mapOf("connection_id" to "c1", "query" to "customer"),
                )
            val parsed = result.json()
            assertNotNull(result.structuredContent)
            assertEquals(parsed, result.structuredContent)
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
    fun searchCatalogDoesNotMatchForeignKeysToBlockedSchemas() = runBlocking {
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
                    }
            )
        withTempMcpClient(service) { client ->
            val byTarget =
                client
                    .callTool(
                        "search_catalog",
                        mapOf("connection_id" to "c1", "query" to "events"),
                    )
                    .json()
            assertEquals("0", byTarget.getValue("match_count").jsonPrimitive.content)
            assertTrue(byTarget.getValue("matches").jsonArray.isEmpty())

            val byName =
                client
                    .callTool(
                        "search_catalog",
                        mapOf("connection_id" to "c1", "query" to "orders_events_fk"),
                    )
                    .json()
            assertEquals("0", byName.getValue("match_count").jsonPrimitive.content)
            assertTrue(byName.getValue("matches").jsonArray.isEmpty())

            val visible =
                client
                    .callTool(
                        "search_catalog",
                        mapOf("connection_id" to "c1", "query" to "orders_customer_fk"),
                    )
                    .json()
            val matches = visible.getValue("matches").jsonArray
            assertEquals(1, matches.size)
            assertEquals(
                listOf("orders_customer_fk"),
                matches.single().jsonObject.getValue("matched_foreign_keys").jsonArray.map {
                    it.jsonPrimitive.content
                },
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
    fun searchCatalogToolIsReadOnly() = runBlocking {
        withTempMcpClient(catalogService()) { client ->
            val tool = client.listTools().tools.single { it.name == "search_catalog" }
            assertEquals(true, tool.annotations?.readOnlyHint)
        }
    }
}
