package com.safedb.mcp

import com.safedb.model.Dialect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ConnectionToolsTest {
    @Test
    fun listAndDeleteToolsRoundTripWithoutSecrets() = runBlocking {
        val service = RecordingSafeDbService()
        service.connections +=
            sampleMcpConnection(
                id = "c1",
                name = "Prod",
                dialect = Dialect.MySql,
                database = "shop",
            )
        service.passwords["c1"] = "should-not-leak"
        val server = createSafeDbMcpServer(service)

        withMcpClient(server) { client ->
            val listed = client.callTool("list_connections", emptyMap())
            val text =
                (listed.content.single() as io.modelcontextprotocol.kotlin.sdk.types.TextContent)
                    .text
            assertFalse(listed.isError == true)
            assertFalse(text.contains("should-not-leak"))
            assertFalse(text.contains("localhost"))
            assertFalse(text.contains("readonly"))
            val parsed =
                kotlinx.serialization.json.Json.parseToJsonElement(text)
                    .jsonArray
                    .single()
                    .jsonObject
            assertEquals("c1", parsed.getValue("id").jsonPrimitive.content)
            assertEquals("Prod", parsed.getValue("name").jsonPrimitive.content)
            assertEquals("MySql", parsed.getValue("dialect").jsonPrimitive.content)
            assertEquals("shop", parsed.getValue("database").jsonPrimitive.content)

            val missing = client.callTool("delete_connection", emptyMap())
            assertEquals(true, missing.isError)
            assertEquals(
                "connection_id is required",
                (missing.content.single() as io.modelcontextprotocol.kotlin.sdk.types.TextContent)
                    .text,
            )
            val blank = client.callTool("delete_connection", mapOf("connection_id" to "  "))
            assertEquals(true, blank.isError)
            assertEquals(
                "connection_id is required",
                (blank.content.single() as io.modelcontextprotocol.kotlin.sdk.types.TextContent)
                    .text,
            )

            val unknown = client.callTool("delete_connection", mapOf("connection_id" to "nope"))
            assertEquals(true, unknown.isError)
            assertEquals(
                "Connection not found",
                (unknown.content.single() as io.modelcontextprotocol.kotlin.sdk.types.TextContent)
                    .text,
            )
            assertEquals(listOf("c1"), service.connections.map { it.id })
            assertTrue(service.passwords.containsKey("c1"))

            val deleted = client.callTool("delete_connection", mapOf("connection_id" to "c1"))
            assertFalse(deleted.isError == true)
            assertTrue(
                (deleted.content.single() as io.modelcontextprotocol.kotlin.sdk.types.TextContent)
                    .text
                    .contains("c1")
            )
            assertTrue(service.connections.isEmpty())
            assertFalse(service.passwords.containsKey("c1"))
        }
    }
}
