package com.safedb.mcp

import com.safedb.model.QueryResult
import com.safedb.model.ResultCell
import com.safedb.model.ResultColumn
import com.safedb.service.QueryRunResult
import io.modelcontextprotocol.kotlin.sdk.client.Client
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class FilterResultRowsToolTest {
    @Test
    fun filterResultRowsRejectsMissingUnknownAndMalformedArguments(): Unit = runBlocking {
        val service = exploreSampleService()
        withTempMcpClient(service) { client ->
            val missingResultFilter = client.callTool("filter_result_rows", emptyMap())
            assertEquals(
                "result_id is required",
                missingResultFilter.json().getValue("message").jsonPrimitive.content,
            )

            val unknown = client.callTool("filter_result_rows", mapOf("result_id" to "missing"))
            assertEquals(true, unknown.isError)
            assertEquals("not_found", unknown.json().getValue("error").jsonPrimitive.content)
            assertEquals(
                "Result not found",
                unknown.json().getValue("message").jsonPrimitive.content,
            )

            val resultId = storeExploreResult(client)
            listOf(
                    mapOf("result_id" to resultId, "filters" to "x") to "filters must be an array",
                    mapOf("result_id" to resultId, "limit" to 0) to "limit must be 1 or more",
                    mapOf("result_id" to resultId, "bogus" to 1) to "unknown argument 'bogus'",
                )
                .forEach { (args, expectedMessage) ->
                    val response = client.callTool("filter_result_rows", args)
                    assertEquals(true, response.isError)
                    assertEquals(
                        "invalid_arguments",
                        response.json().getValue("error").jsonPrimitive.content,
                    )
                    assertEquals(
                        expectedMessage,
                        response.json().getValue("message").jsonPrimitive.content,
                    )
                }
        }
    }

    @Test
    fun filterResultRowsFiltersSortsAndProjects(): Unit = runBlocking {
        val service = exploreSampleService()
        withTempMcpClient(service) { client ->
            val resultId = storeExploreResult(client)
            val body =
                client
                    .callTool(
                        "filter_result_rows",
                        mapOf(
                            "result_id" to resultId,
                            "filters" to
                                listOf(
                                    Json.parseToJsonElement(
                                        """{"column":"region","op":"eq","value":"east"}""",
                                    ),
                                ),
                            "sorts" to
                                listOf(
                                    Json.parseToJsonElement(
                                        """{"column":"amount","direction":"desc"}""",
                                    ),
                                ),
                            "columns" to listOf("amount", "region"),
                        ),
                    )
                    .json()
            assertEquals(resultId, body.getValue("result_id").jsonPrimitive.content)
            assertEquals("0", body.getValue("offset").jsonPrimitive.content)
            assertEquals("2", body.getValue("matched_row_count").jsonPrimitive.content)
            assertEquals("5", body.getValue("sample_row_count").jsonPrimitive.content)
            assertFalse(body.containsKey("sample_capped"))
            assertEquals("false", body.getValue("page_truncated").jsonPrimitive.content)
            assertFalse(body.containsKey("next_offset"))
            val rows = body.getValue("rows").jsonArray
            assertEquals(2, rows.size)
            assertEquals(
                listOf("amount", "region"),
                rows[0].jsonObject.keys.toList(),
            )
            assertEquals("20", rows[0].jsonObject.getValue("amount").jsonPrimitive.content)
            assertEquals("east", rows[0].jsonObject.getValue("region").jsonPrimitive.content)
            assertEquals("10", rows[1].jsonObject.getValue("amount").jsonPrimitive.content)

            val members =
                client
                    .callTool(
                        "filter_result_rows",
                        mapOf(
                            "result_id" to resultId,
                            "filters" to
                                listOf(
                                    Json.parseToJsonElement(
                                        """{"column":"region","op":"members","included_keys":["east"]}""",
                                    ),
                                ),
                        ),
                    )
                    .json()
            assertEquals("2", members.getValue("matched_row_count").jsonPrimitive.content)

            val numericMembers =
                client
                    .callTool(
                        "filter_result_rows",
                        mapOf(
                            "result_id" to resultId,
                            "filters" to
                                listOf(
                                    Json.parseToJsonElement(
                                        """{"column":"amount","op":"members","included_keys":["10"]}""",
                                    ),
                                ),
                        ),
                    )
                    .json()
            assertEquals("1", numericMembers.getValue("matched_row_count").jsonPrimitive.content)

            val internalKeys =
                client
                    .callTool(
                        "filter_result_rows",
                        mapOf(
                            "result_id" to resultId,
                            "filters" to
                                listOf(
                                    Json.parseToJsonElement(
                                        """{"column":"region","op":"members","included_keys":["TextCell:east"]}""",
                                    ),
                                ),
                        ),
                    )
                    .json()
            assertEquals("2", internalKeys.getValue("matched_row_count").jsonPrimitive.content)
        }
    }

    @Test
    fun filterResultRowsPagesAndClampsOffsets(): Unit = runBlocking {
        val service = exploreSampleService()
        withTempMcpClient(service) { client ->
            val resultId = storeExploreResult(client)
            val page =
                client
                    .callTool(
                        "filter_result_rows",
                        mapOf("result_id" to resultId, "offset" to 1, "limit" to 2),
                    )
                    .json()
            assertEquals("1", page.getValue("offset").jsonPrimitive.content)
            assertEquals("5", page.getValue("matched_row_count").jsonPrimitive.content)
            assertEquals("true", page.getValue("page_truncated").jsonPrimitive.content)
            assertEquals("3", page.getValue("next_offset").jsonPrimitive.content)
            assertEquals(2, page.getValue("rows").jsonArray.size)

            val clamped =
                client
                    .callTool(
                        "filter_result_rows",
                        mapOf("result_id" to resultId, "offset" to -5, "limit" to 2),
                    )
                    .json()
            assertEquals("0", clamped.getValue("offset").jsonPrimitive.content)
            assertEquals(
                "east",
                clamped
                    .getValue("rows")
                    .jsonArray[0]
                    .jsonObject
                    .getValue("region")
                    .jsonPrimitive
                    .content,
            )
        }
    }

    @Test
    fun filterResultRowsProjectsOnlyThePageWindow(): Unit = runBlocking {
        val service = exploreSampleService()
        withTempMcpClient(service) { client ->
            val resultId = storeExploreResult(client)
            val page =
                client.callJson(
                    "filter_result_rows",
                    mapOf(
                        "result_id" to resultId,
                        "columns" to listOf("region"),
                        "offset" to 2,
                        "limit" to 2,
                    ),
                )
            assertEquals("2", page.getValue("offset").jsonPrimitive.content)
            assertEquals("5", page.getValue("matched_row_count").jsonPrimitive.content)
            assertEquals("true", page.getValue("page_truncated").jsonPrimitive.content)
            assertEquals("4", page.getValue("next_offset").jsonPrimitive.content)
            val rows = page.getValue("rows").jsonArray
            assertEquals(2, rows.size)
            assertEquals(listOf("region"), rows[0].jsonObject.keys.toList())
            assertEquals("west", rows[0].jsonObject.getValue("region").jsonPrimitive.content)
            assertEquals("west", rows[1].jsonObject.getValue("region").jsonPrimitive.content)

            val lastPage =
                client.callJson(
                    "filter_result_rows",
                    mapOf(
                        "result_id" to resultId,
                        "columns" to listOf("region"),
                        "offset" to 4,
                    ),
                )
            assertEquals("5", lastPage.getValue("matched_row_count").jsonPrimitive.content)
            assertEquals("false", lastPage.getValue("page_truncated").jsonPrimitive.content)
            assertFalse(lastPage.containsKey("next_offset"))
            assertEquals(
                "north",
                lastPage
                    .getValue("rows")
                    .jsonArray
                    .single()
                    .jsonObject
                    .getValue("region")
                    .jsonPrimitive
                    .content,
            )
        }
    }

    @Test
    fun filterResultRowsSupportsNullAndBetweenFilters(): Unit = runBlocking {
        val service = exploreSampleService()
        service.runQueryResult =
            QueryRunResult(
                QueryResult(
                    columns =
                        listOf(
                            ResultColumn("region", "text"),
                            ResultColumn("amount", "int"),
                        ),
                    rows =
                        listOf(
                            listOf(ResultCell.text("east"), ResultCell.integer(10)),
                            listOf(ResultCell.text("west"), ResultCell.Null),
                            listOf(ResultCell.text("north"), ResultCell.integer(30)),
                        ),
                    rowCount = 3,
                    truncated = false,
                    warnings = emptyList(),
                ),
                allowedMcpRisk(),
            )
        withTempMcpClient(service) { client ->
            val resultId = storeExploreResult(client)
            val between =
                client
                    .callTool(
                        "filter_result_rows",
                        mapOf(
                            "result_id" to resultId,
                            "filters" to
                                listOf(
                                    Json.parseToJsonElement(
                                        """{"column":"amount","op":"between","value":"5","second_value":"20"}""",
                                    ),
                                ),
                        ),
                    )
                    .json()
            assertEquals("1", between.getValue("matched_row_count").jsonPrimitive.content)
            assertEquals(
                "10",
                between
                    .getValue("rows")
                    .jsonArray
                    .single()
                    .jsonObject
                    .getValue("amount")
                    .jsonPrimitive
                    .content,
            )

            val isNull =
                client
                    .callTool(
                        "filter_result_rows",
                        mapOf(
                            "result_id" to resultId,
                            "filters" to
                                listOf(
                                    Json.parseToJsonElement(
                                        """{"column":"amount","op":"is_null"}""",
                                    ),
                                ),
                        ),
                    )
                    .json()
            assertEquals("1", isNull.getValue("matched_row_count").jsonPrimitive.content)
            assertEquals(
                "west",
                isNull
                    .getValue("rows")
                    .jsonArray
                    .single()
                    .jsonObject
                    .getValue("region")
                    .jsonPrimitive
                    .content,
            )
        }
    }

    @Test
    fun filterResultRowsUsesReceiptTypesForDecimalEquality(): Unit = runBlocking {
        val service = exploreSampleService()
        service.runQueryResult =
            QueryRunResult(
                QueryResult.fromRows(
                    listOf(ResultColumn("total", "decimal(10,2)"), ResultColumn("code", "varchar")),
                    listOf(
                        listOf(ResultCell.text("146.97"), ResultCell.text("001")),
                        listOf(ResultCell.text("146.98"), ResultCell.text("1")),
                    ),
                ),
                allowedMcpRisk(),
            )
        withTempMcpClient(service) { client ->
            val resultId = storeExploreResult(client)
            suspend fun filter(column: String, op: String, value: String): JsonObject =
                client
                    .callJson(
                        "filter_result_rows",
                        mapOf(
                            "result_id" to resultId,
                            "filters" to
                                listOf(mapOf("column" to column, "op" to op, "value" to value)),
                        ),
                    )
                    .getValue("rows")
                    .jsonArray
                    .single()
                    .jsonObject

            assertEquals(
                "146.97",
                filter("total", "equals", "146.970").getValue("total").jsonPrimitive.content,
            )
            assertEquals(
                "146.98",
                filter("total", "not_equals", "146.970").getValue("total").jsonPrimitive.content,
            )
            assertEquals(
                "146.98",
                filter("code", "equals", "1").getValue("total").jsonPrimitive.content,
            )
        }
    }

    @Test
    fun filterResultRowsRejectsInvalidMembersAndWrongNestedTypes(): Unit = runBlocking {
        val service = exploreSampleService()
        withTempMcpClient(service) { client ->
            val resultId = storeExploreResult(client)
            val includedKeysNumber =
                client.callTool(
                    "filter_result_rows",
                    mapOf(
                        "result_id" to resultId,
                        "filters" to
                            listOf(
                                Json.parseToJsonElement(
                                    """{"column":"region","op":"members","included_keys":[1]}""",
                                ),
                            ),
                    ),
                )
            assertEquals(true, includedKeysNumber.isError)
            assertEquals(
                "filters[0].included_keys[0] must be a string",
                includedKeysNumber.json().getValue("message").jsonPrimitive.content,
            )

            val membersWithValue =
                client.callTool(
                    "filter_result_rows",
                    mapOf(
                        "result_id" to resultId,
                        "filters" to
                            listOf(
                                Json.parseToJsonElement(
                                    """{"column":"region","op":"members","value":"east"}""",
                                ),
                            ),
                    ),
                )
            assertEquals(true, membersWithValue.isError)
            assertEquals(
                "invalid_arguments",
                membersWithValue.json().getValue("error").jsonPrimitive.content,
            )

            val emptyMembers =
                client.callTool(
                    "filter_result_rows",
                    mapOf(
                        "result_id" to resultId,
                        "filters" to
                            listOf(
                                Json.parseToJsonElement(
                                    """{"column":"region","op":"members","included_keys":[]}""",
                                ),
                            ),
                    ),
                )
            assertEquals(true, emptyMembers.isError)
            assertEquals(
                "invalid_arguments",
                emptyMembers.json().getValue("error").jsonPrimitive.content,
            )
        }
    }

    @Test
    fun filterResultRowsStopsAtTheByteBudgetAndReturnsNextOffset(): Unit = runBlocking {
        val service = exploreSampleService()
        val payload = "x".repeat(2_048)
        val wideColumns =
            (1..24).map { index -> ResultColumn("c${index.toString().padStart(2, '0')}", "text") }
        val wideRows = List(50) { wideColumns.map { ResultCell.text(payload) } }
        service.runQueryResult =
            QueryRunResult(
                QueryResult(
                    columns = wideColumns,
                    rows = wideRows,
                    rowCount = 50,
                    truncated = false,
                    warnings = emptyList(),
                ),
                allowedMcpRisk(),
            )
        withTempMcpClient(service) { client ->
            val resultId = storeExploreResult(client)
            val page = client.callTool("filter_result_rows", mapOf("result_id" to resultId)).json()
            assertTrue(page.getValue("rows").jsonArray.size < GET_RESULT_ROWS_MAX)
            assertEquals("true", page.getValue("page_truncated").jsonPrimitive.content)
            assertEquals(
                page.getValue("rows").jsonArray.size.toString(),
                page.getValue("next_offset").jsonPrimitive.content,
            )
        }
    }
}

private suspend fun Client.callJson(name: String, arguments: Map<String, Any?>): JsonObject =
    callTool(name, arguments).json()
