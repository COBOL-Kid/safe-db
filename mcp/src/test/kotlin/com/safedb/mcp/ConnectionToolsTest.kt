package com.safedb.mcp

import com.safedb.model.Dialect
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ConnectionToolsTest {
    @Test
    fun emptyListReturnsAnEmptyJsonArray() = runBlocking {
        withTempMcpClient(RecordingSafeDbService()) { client ->
            val listed = client.callTool("list_connections", emptyMap())
            assertFalse(listed.isError == true)
            val text =
                (listed.content.single() as io.modelcontextprotocol.kotlin.sdk.types.TextContent)
                    .text
            assertEquals(0, kotlinx.serialization.json.Json.parseToJsonElement(text).jsonArray.size)
        }
    }

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

        withTempMcpClient(service) { client ->
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
            val missingBody =
                kotlinx.serialization.json.Json.parseToJsonElement(
                        (missing.content.single()
                                as io.modelcontextprotocol.kotlin.sdk.types.TextContent)
                            .text
                    )
                    .jsonObject
            assertEquals("invalid_arguments", missingBody.getValue("error").jsonPrimitive.content)
            assertEquals(
                "connection_id is required",
                missingBody.getValue("message").jsonPrimitive.content,
            )
            val blank = client.callTool("delete_connection", mapOf("connection_id" to "  "))
            assertEquals(true, blank.isError)
            val blankBody =
                kotlinx.serialization.json.Json.parseToJsonElement(
                        (blank.content.single()
                                as io.modelcontextprotocol.kotlin.sdk.types.TextContent)
                            .text
                    )
                    .jsonObject
            assertEquals("invalid_arguments", blankBody.getValue("error").jsonPrimitive.content)
            assertEquals(
                "connection_id is required",
                blankBody.getValue("message").jsonPrimitive.content,
            )

            val unknown = client.callTool("delete_connection", mapOf("connection_id" to "nope"))
            assertEquals(true, unknown.isError)
            val unknownBody =
                kotlinx.serialization.json.Json.parseToJsonElement(
                        (unknown.content.single()
                                as io.modelcontextprotocol.kotlin.sdk.types.TextContent)
                            .text
                    )
                    .jsonObject
            assertEquals("not_found", unknownBody.getValue("error").jsonPrimitive.content)
            assertEquals(
                "Connection not found",
                unknownBody.getValue("message").jsonPrimitive.content,
            )
            assertEquals(listOf("c1"), service.connections.map { it.id })
            assertTrue(service.passwords.containsKey("c1"))

            val deleted = client.deleteConnectionConfirmed("c1")
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

    @Test
    fun deleteRequiresEchoedConfirmation() = runBlocking {
        val service = RecordingSafeDbService()
        service.connections +=
            sampleMcpConnection(
                id = "c1",
                name = "Prod",
                dialect = Dialect.MySql,
                database = "shop",
            )
        service.passwords["c1"] = "should-not-leak"

        withTempMcpClient(service) { client ->
            val first = client.callTool("delete_connection", mapOf("connection_id" to "c1"))
            assertEquals(true, first.isError)
            val firstBody = first.json()
            assertEquals("confirmation_required", firstBody.getValue("error").jsonPrimitive.content)
            assertTrue(firstBody.getValue("message").jsonPrimitive.content.contains("Prod"))
            assertTrue(firstBody.getValue("message").jsonPrimitive.content.contains("shop"))
            assertFalse(
                firstBody.getValue("message").jsonPrimitive.content.contains("should-not-leak")
            )
            assertFalse(firstBody.getValue("message").jsonPrimitive.content.contains("localhost"))
            val reasons = firstBody.getValue("reasons").jsonArray.map { it.jsonPrimitive.content }
            assertEquals(listOf("This deletes saved connection Prod (MySql / shop)."), reasons)
            val confirmation = firstBody.getValue("confirmation").jsonObject
            assertEquals("c1", confirmation.getValue("connection_id").jsonPrimitive.content)
            assertTrue(confirmation.getValue("token").jsonPrimitive.content.isNotBlank())
            assertEquals(listOf("c1"), service.connections.map { it.id })

            val invented =
                client.callTool(
                    "delete_connection",
                    mapOf(
                        "connection_id" to "c1",
                        "confirmation" to
                            JsonObject(
                                mapOf(
                                    "connection_id" to JsonPrimitive("c1"),
                                    "token" to JsonPrimitive("invented"),
                                )
                            ),
                    ),
                )
            assertEquals(true, invented.isError)
            assertEquals(
                "confirmation_required",
                invented.json().getValue("error").jsonPrimitive.content,
            )
            assertEquals(listOf("c1"), service.connections.map { it.id })

            val asString =
                client.callTool(
                    "delete_connection",
                    mapOf("connection_id" to "c1", "confirmation" to "not-an-object"),
                )
            assertEquals(true, asString.isError)
            assertEquals("parse", asString.json().getValue("error").jsonPrimitive.content)
            assertEquals(listOf("c1"), service.connections.map { it.id })

            val stale =
                client.callTool(
                    "delete_connection",
                    mapOf("connection_id" to "c1", "confirmation" to confirmation),
                )
            assertEquals(true, stale.isError)
            assertEquals(
                "confirmation_required",
                stale.json().getValue("error").jsonPrimitive.content,
            )
            val echoed = stale.json().getValue("confirmation")
            val deleted =
                client.callTool(
                    "delete_connection",
                    mapOf("connection_id" to "c1", "confirmation" to echoed),
                )
            assertFalse(deleted.isError == true)
            assertTrue(deleted.text().contains("c1"))
            assertTrue(service.connections.isEmpty())
        }
    }

    @Test
    fun listAndDeleteServiceFailuresAreToolErrors() = runBlocking {
        val listService =
            object : RecordingSafeDbService() {
                override suspend fun listConnections() = error("store read failed")
            }
        withTempMcpClient(listService) { client ->
            val listed = client.callTool("list_connections", emptyMap())
            assertEquals(true, listed.isError)
            val listedBody =
                kotlinx.serialization.json.Json.parseToJsonElement(
                        (listed.content.single()
                                as io.modelcontextprotocol.kotlin.sdk.types.TextContent)
                            .text
                    )
                    .jsonObject
            assertEquals("internal", listedBody.getValue("error").jsonPrimitive.content)
            assertTrue(
                listedBody.getValue("message").jsonPrimitive.content.contains("store read failed")
            )
        }

        val deleteService =
            object : RecordingSafeDbService() {
                override suspend fun deleteConnection(id: String) {
                    error("store write failed")
                }
            }
        deleteService.connections += sampleMcpConnection()
        deleteService.passwords["c1"] = "should-not-leak"
        withTempMcpClient(deleteService) { client ->
            val deleted = client.deleteConnectionConfirmed("c1")
            assertEquals(true, deleted.isError)
            val deletedBody =
                kotlinx.serialization.json.Json.parseToJsonElement(
                        (deleted.content.single()
                                as io.modelcontextprotocol.kotlin.sdk.types.TextContent)
                            .text
                    )
                    .jsonObject
            assertEquals("internal", deletedBody.getValue("error").jsonPrimitive.content)
            assertEquals(
                "store write failed",
                deletedBody.getValue("message").jsonPrimitive.content,
            )
            assertFalse(
                deletedBody.getValue("message").jsonPrimitive.content.contains("should-not-leak")
            )
            assertEquals(listOf("c1"), deleteService.connections.map { it.id })
        }
    }
}

private fun CallToolResult.text(): String = (content.single() as TextContent).text

private fun CallToolResult.json(): JsonObject = Json.parseToJsonElement(text()).jsonObject
