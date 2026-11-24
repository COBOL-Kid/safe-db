package com.safedb.mcp

import com.safedb.model.Dialect
import com.safedb.model.HistoryEntry
import com.safedb.model.SavedQuery
import com.safedb.store.ConfigStore
import com.safedb.store.QueryStore
import java.nio.file.Files
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
    fun emptyListReturnsEmptyConnectionsObject() = runBlocking {
        withTempMcpClient(RecordingSafeDbService()) { client ->
            val listed = client.callTool("list_connections", emptyMap())
            assertFalse(listed.isError == true)
            val body = listed.json()
            assertEquals(body, listed.structuredContent)
            assertEquals(0, body.getValue("connections").jsonArray.size)
            assertEquals("0", body.getValue("total_count").jsonPrimitive.content)
            assertEquals("false", body.getValue("truncated").jsonPrimitive.content)
            assertFalse(body.containsKey("next_offset"))
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
            val text = listed.text()
            assertFalse(listed.isError == true)
            assertFalse(text.contains("should-not-leak"))
            assertFalse(text.contains("localhost"))
            assertFalse(text.contains("readonly"))
            val body = Json.parseToJsonElement(text).jsonObject
            assertEquals("1", body.getValue("total_count").jsonPrimitive.content)
            assertEquals("false", body.getValue("truncated").jsonPrimitive.content)
            val parsed = body.getValue("connections").jsonArray.single().jsonObject
            assertEquals("c1", parsed.getValue("id").jsonPrimitive.content)
            assertEquals("Prod", parsed.getValue("name").jsonPrimitive.content)
            assertEquals("MySql", parsed.getValue("dialect").jsonPrimitive.content)
            assertEquals("shop", parsed.getValue("database").jsonPrimitive.content)

            val extra = client.callTool("list_connections", mapOf("bogus" to 1))
            assertEquals(true, extra.isError)
            val extraBody = extra.json()
            assertEquals("invalid_arguments", extraBody.getValue("error").jsonPrimitive.content)
            assertEquals(
                "unknown argument 'bogus'",
                extraBody.getValue("message").jsonPrimitive.content,
            )

            val missing = client.callTool("delete_connection", emptyMap())
            assertEquals(true, missing.isError)
            val missingBody = missing.json()
            assertEquals("invalid_arguments", missingBody.getValue("error").jsonPrimitive.content)
            assertEquals(
                "connection_id is required",
                missingBody.getValue("message").jsonPrimitive.content,
            )
            val blank = client.callTool("delete_connection", mapOf("connection_id" to "  "))
            assertEquals(true, blank.isError)
            val blankBody = blank.json()
            assertEquals("invalid_arguments", blankBody.getValue("error").jsonPrimitive.content)
            assertEquals(
                "connection_id is required",
                blankBody.getValue("message").jsonPrimitive.content,
            )

            val unknown = client.callTool("delete_connection", mapOf("connection_id" to "nope"))
            assertEquals(true, unknown.isError)
            val unknownBody = unknown.json()
            assertEquals("not_found", unknownBody.getValue("error").jsonPrimitive.content)
            assertEquals(
                "Connection not found",
                unknownBody.getValue("message").jsonPrimitive.content,
            )
            assertEquals(listOf("c1"), service.connections.map { it.id })
            assertTrue(service.passwords.containsKey("c1"))

            val deleted = client.deleteConnectionConfirmed("c1")
            assertFalse(deleted.isError == true)
            assertTrue(deleted.text().contains("c1"))
            assertTrue(service.connections.isEmpty())
            assertFalse(service.passwords.containsKey("c1"))
        }
    }

    @Test
    fun deleteConnectionRemovesItsSavedQueriesAndHistory() = runBlocking {
        val dataDir = Files.createTempDirectory("safedb-mcp-delete")
        try {
            val configStore = ConfigStore.new(dataDir)
            configStore.save(sampleMcpConnection(id = "c1", name = "Prod"))
            configStore.save(sampleMcpConnection(id = "c2", name = "Dev"))
            val queryStore = QueryStore.new(dataDir)
            queryStore.saveQuery(SavedQuery("q1", "One", "c1", sampleMcpQuerySpec(), "1"))
            queryStore.saveQuery(SavedQuery("q2", "Two", "c2", sampleMcpQuerySpec(), "1"))
            queryStore.addHistory(
                HistoryEntry(
                    "h1",
                    "c1",
                    "Prod",
                    sampleMcpQuerySpec(),
                    1,
                    emptyList(),
                    timestamp = "1",
                ),
            )
            queryStore.addHistory(
                HistoryEntry(
                    "h2",
                    "c2",
                    "Dev",
                    sampleMcpQuerySpec(),
                    1,
                    emptyList(),
                    timestamp = "2",
                ),
            )
            val service = createMcpService(dataDir)

            withTempMcpClient(service) { client ->
                val pending = client.callTool("delete_connection", mapOf("connection_id" to "c1"))
                val pendingBody = pending.json()
                assertTrue(
                    pendingBody.getValue("reasons").jsonArray.any {
                        it.jsonPrimitive.content.contains("saved queries and query history")
                    },
                )
                val deleted =
                    client.callTool(
                        "delete_connection",
                        mapOf(
                            "connection_id" to "c1",
                            "confirmation" to pendingBody.getValue("confirmation").jsonObject,
                        ),
                    )
                assertFalse(deleted.isError == true)
            }

            assertEquals(listOf("c2"), service.listConnections().map { it.id })
            assertEquals(listOf("q2"), service.listSavedQueries().map { it.id })
            assertEquals(listOf("h2"), service.listHistory().map { it.id })
        } finally {
            dataDir.toFile().deleteRecursively()
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
                firstBody.getValue("message").jsonPrimitive.content.contains("should-not-leak"),
            )
            assertFalse(firstBody.getValue("message").jsonPrimitive.content.contains("localhost"))
            val reasons = firstBody.getValue("reasons").jsonArray.map { it.jsonPrimitive.content }
            assertEquals(
                listOf(
                    "This deletes saved connection Prod (MySQL / shop).",
                    "It also deletes this connection's saved queries and query history.",
                ),
                reasons,
            )
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
                                ),
                            ),
                    ),
                )
            assertEquals(true, invented.isError)
            assertEquals(
                "confirmation_required",
                invented.json().getValue("error").jsonPrimitive.content,
            )
            assertEquals(
                confirmation.getValue("token").jsonPrimitive.content,
                invented
                    .json()
                    .getValue("confirmation")
                    .jsonObject
                    .getValue("token")
                    .jsonPrimitive
                    .content,
            )
            assertEquals(listOf("c1"), service.connections.map { it.id })

            val asString =
                client.callTool(
                    "delete_connection",
                    mapOf("connection_id" to "c1", "confirmation" to "not-an-object"),
                )
            assertEquals(true, asString.isError)
            assertEquals(
                "invalid_arguments",
                asString.json().getValue("error").jsonPrimitive.content,
            )
            assertEquals(listOf("c1"), service.connections.map { it.id })

            val deleted =
                client.callTool(
                    "delete_connection",
                    mapOf("connection_id" to "c1", "confirmation" to confirmation),
                )
            assertFalse(deleted.isError == true)
            assertTrue(deleted.text().contains("c1"))
            assertTrue(service.connections.isEmpty())

            val replayed =
                client.callTool(
                    "delete_connection",
                    mapOf("connection_id" to "c1", "confirmation" to confirmation),
                )
            assertEquals(true, replayed.isError)
            assertEquals("not_found", replayed.json().getValue("error").jsonPrimitive.content)
        }
    }

    @Test
    fun listAndDeleteServiceFailuresAreToolErrors() = runBlocking {
        val listService =
            object : RecordingSafeDbService() {
                override suspend fun listConnections() =
                    error("/secret/connections.json read failed")
            }
        withTempMcpClient(listService) { client ->
            val listed = client.callTool("list_connections", emptyMap())
            assertEquals(true, listed.isError)
            val listedBody = listed.json()
            assertEquals("internal", listedBody.getValue("error").jsonPrimitive.content)
            assertEquals(
                "list_connections failed",
                listedBody.getValue("message").jsonPrimitive.content,
            )
            assertFalse(listed.text().contains("/secret/connections.json"))
        }

        val deleteLookupService =
            object : RecordingSafeDbService() {
                override suspend fun listConnections() =
                    error("/secret/connections.json lookup failed")
            }
        withTempMcpClient(deleteLookupService) { client ->
            val lookupFailed = client.callTool("delete_connection", mapOf("connection_id" to "c1"))
            assertEquals(true, lookupFailed.isError)
            val lookupBody = lookupFailed.json()
            assertEquals("internal", lookupBody.getValue("error").jsonPrimitive.content)
            assertEquals(
                "delete_connection failed",
                lookupBody.getValue("message").jsonPrimitive.content,
            )
            assertFalse(lookupFailed.text().contains("/secret/connections.json"))
        }

        val deleteService =
            object : RecordingSafeDbService() {
                override suspend fun deleteConnection(id: String) {
                    error("/secret/connections.json write failed")
                }
            }
        deleteService.connections += sampleMcpConnection()
        deleteService.passwords["c1"] = "should-not-leak"
        withTempMcpClient(deleteService) { client ->
            val deleted = client.deleteConnectionConfirmed("c1")
            assertEquals(true, deleted.isError)
            val deletedBody = deleted.json()
            assertEquals("internal", deletedBody.getValue("error").jsonPrimitive.content)
            assertEquals(
                "delete_connection failed",
                deletedBody.getValue("message").jsonPrimitive.content,
            )
            assertFalse(deleted.text().contains("/secret/connections.json"))
            assertFalse(deleted.text().contains("should-not-leak"))
            assertEquals(listOf("c1"), deleteService.connections.map { it.id })
        }
    }

    @Test
    fun listConnectionsPaginatesAndValidatesOffsetAndLimit() = runBlocking {
        val service = RecordingSafeDbService()
        repeat(5) { index ->
            service.connections += sampleMcpConnection(id = "c$index", name = "Conn $index")
        }
        withTempMcpClient(service) { client ->
            val page =
                client.callTool("list_connections", mapOf("offset" to 1, "limit" to 2)).json()
            assertEquals(
                listOf("c1", "c2"),
                page.getValue("connections").jsonArray.map {
                    it.jsonObject.getValue("id").jsonPrimitive.content
                },
            )
            assertEquals("5", page.getValue("total_count").jsonPrimitive.content)
            assertEquals("true", page.getValue("truncated").jsonPrimitive.content)
            assertEquals("3", page.getValue("next_offset").jsonPrimitive.content)

            assertError(
                client.callTool("list_connections", mapOf("offset" to -1)),
                "invalid_arguments",
                "offset must be 0 or more",
            )
        }
    }

    @Test
    fun deleteConnectionCopyMentionsDesktopAppWhenStoreIsShared() = runBlocking {
        val service = RecordingSafeDbService()
        service.connections += sampleMcpConnection(id = "c1", name = "Prod", database = "shop")
        withTempMcpClient(service, sharesDesktopStore = true) { client ->
            val tools = client.listTools().tools.associateBy { it.name }
            assertTrue(
                tools.getValue("delete_connection").description.orEmpty().contains("desktop app"),
            )

            val pending =
                client.callTool("delete_connection", mapOf("connection_id" to "c1")).json()
            assertTrue(pending.getValue("message").jsonPrimitive.content.contains("desktop app"))
            val reasons = pending.getValue("reasons").jsonArray.map { it.jsonPrimitive.content }
            assertTrue(reasons.all { it.contains("desktop app") })
            assertTrue(reasons.any { it.contains("saved queries and query history") })
        }
    }
}
