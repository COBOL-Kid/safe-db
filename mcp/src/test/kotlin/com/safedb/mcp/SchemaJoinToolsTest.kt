package com.safedb.mcp

import com.safedb.model.Settings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class SchemaJoinToolsTest {
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
            service.settings = Settings(defaultConnectionId = "c1", defaultSchema = "public")
            val fromSettings =
                client
                    .callTool(
                        "find_join_paths",
                        mapOf("connection_id" to "c1", "from_table" to "orders"),
                    )
                    .json()
            assertEquals("public.orders", fromSettings.getValue("from_table").jsonPrimitive.content)

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
    fun findJoinPathsRejectsNonStringDefaultSchemaWithoutLoadingCatalog() = runBlocking {
        val service = catalogService()
        withTempMcpClient(service) { client ->
            val result =
                client.callTool(
                    "find_join_paths",
                    mapOf(
                        "connection_id" to "c1",
                        "from_table" to "orders",
                        "default_schema" to 123,
                    ),
                )
            assertEquals(true, result.isError)
            assertEquals(
                "invalid_arguments",
                result.json().getValue("error").jsonPrimitive.content,
            )
            assertEquals(
                "default_schema must be a string",
                result.json().getValue("message").jsonPrimitive.content,
            )
        }
        assertTrue(service.schemaCalls.isEmpty())
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
    fun findJoinPathsForeignKeyOnlyExcludesIndexedCandidates() = runBlocking {
        val service = joinService()
        withTempMcpClient(service) { client ->
            val all =
                client
                    .callTool(
                        "find_join_paths",
                        mapOf("connection_id" to "c1", "from_table" to "public.customers"),
                    )
                    .json()
            val neighbors =
                all.getValue("neighbors").jsonArray.map {
                    it.jsonObject.getValue("to_table").jsonPrimitive.content to
                        it.jsonObject.getValue("kind").jsonPrimitive.content
                }
            assertTrue(neighbors.contains("orders" to "foreign_key"))
            assertTrue(neighbors.contains("balances" to "indexed_columns"))

            val foreignKeyOnly =
                client
                    .callTool(
                        "find_join_paths",
                        mapOf(
                            "connection_id" to "c1",
                            "from_table" to "public.customers",
                            "foreign_key_only" to true,
                        ),
                    )
                    .json()
            val foreignKeyNeighbors =
                foreignKeyOnly.getValue("neighbors").jsonArray.map {
                    it.jsonObject.getValue("to_table").jsonPrimitive.content to
                        it.jsonObject.getValue("kind").jsonPrimitive.content
                }
            assertTrue(foreignKeyNeighbors.contains("orders" to "foreign_key"))
            assertTrue(foreignKeyNeighbors.none { it.first == "balances" })
            assertTrue(foreignKeyNeighbors.all { it.second == "foreign_key" })

            val paths =
                client
                    .callTool(
                        "find_join_paths",
                        mapOf(
                            "connection_id" to "c1",
                            "from_table" to "public.customers",
                            "to_table" to "public.orders",
                            "foreign_key_only" to true,
                        ),
                    )
                    .json()
            val pathSteps = paths.getValue("paths").jsonArray.first().jsonObject
            assertEquals(
                "orders",
                pathSteps
                    .getValue("steps")
                    .jsonArray
                    .first()
                    .jsonObject
                    .getValue("to_table")
                    .jsonPrimitive
                    .content,
            )
        }
    }

    @Test
    fun findJoinPathsToolMetadataIsDocumented() = runBlocking {
        withTempMcpClient(catalogService()) { client ->
            val tool = client.listTools().tools.single { it.name == "find_join_paths" }
            assertEquals(true, tool.annotations?.readOnlyHint)
            assertTrue(tool.outputSchema?.properties?.containsKey("from_table") == true)
        }
    }
}
