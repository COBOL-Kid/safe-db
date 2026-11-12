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

class ExploreToolsTest {
    @Test
    fun pivotResultUsesTheDefaultConfiguration() = runBlocking {
        val service = exploreSampleService()
        withTempMcpClient(service) { client ->
            val resultId = storeExploreResult(client)
            val body = client.callTool("pivot_result", mapOf("result_id" to resultId)).json()
            assertEquals(resultId, body.getValue("result_id").jsonPrimitive.content)
            val columns = body.getValue("columns").jsonArray
            assertEquals(
                listOf("region", "Count"),
                columns.map { it.jsonObject.getValue("name").jsonPrimitive.content },
            )
            assertEquals("4", body.getValue("row_count").jsonPrimitive.content)
            assertEquals("false", body.getValue("pivot_truncated").jsonPrimitive.content)
            assertFalse(body.containsKey("sample_capped"))
            assertTrue(body.getValue("warnings").jsonArray.isEmpty())
            val rows = body.getValue("rows").jsonArray
            assertEquals(4, rows.size)
            assertEquals("east", rows[0].jsonObject.getValue("region").jsonPrimitive.content)
            assertEquals("2", rows[0].jsonObject.getValue("Count").jsonPrimitive.content)
            assertEquals("north", rows[2].jsonObject.getValue("region").jsonPrimitive.content)
            assertEquals("1", rows[2].jsonObject.getValue("Count").jsonPrimitive.content)
            assertEquals("Total", rows[3].jsonObject.getValue("region").jsonPrimitive.content)
            assertEquals("5", rows[3].jsonObject.getValue("Count").jsonPrimitive.content)
        }
    }

    @Test
    fun pivotResultBuildsCustomDimensionsGroupingMeasuresFiltersAndSort() = runBlocking {
        val service = exploreSampleService()
        withTempMcpClient(service) { client ->
            val resultId = storeExploreResult(client)
            val body =
                client
                    .callTool(
                        "pivot_result",
                        mapOf(
                            "result_id" to resultId,
                            "row_dimensions" to
                                listOf(
                                    Json.parseToJsonElement(
                                        """{"column":"created","label":"Created","grouping":"month","sort_mode":"label_asc"}"""
                                    )
                                ),
                            "measures" to
                                listOf(
                                    Json.parseToJsonElement(
                                        """{"alias":"total","fn":"sum","source_column":"amount","label":"Total amount"}"""
                                    )
                                ),
                            "filters" to
                                listOf(
                                    Json.parseToJsonElement(
                                        """{"column":"region","op":"contains","value":"east"}"""
                                    )
                                ),
                            "sort" to
                                Json.parseToJsonElement(
                                    """{"measure_alias":"total","direction":"desc"}"""
                                ),
                            "show_column_totals" to false,
                            "show_subtotals" to false,
                        ),
                    )
                    .json()
            val columns = body.getValue("columns").jsonArray
            assertEquals(
                listOf("Created", "Total amount"),
                columns.map { it.jsonObject.getValue("name").jsonPrimitive.content },
            )
            assertEquals("2", body.getValue("row_count").jsonPrimitive.content)
            val rows = body.getValue("rows").jsonArray
            assertEquals(2, rows.size)
            assertEquals("Feb 2024", rows[0].jsonObject.getValue("Created").jsonPrimitive.content)
            assertEquals("20", rows[0].jsonObject.getValue("Total amount").jsonPrimitive.content)
            assertEquals("Jan 2024", rows[1].jsonObject.getValue("Created").jsonPrimitive.content)
            assertEquals("10", rows[1].jsonObject.getValue("Total amount").jsonPrimitive.content)
        }
    }

    @Test
    fun pivotResultSupportsNumericBinsAndColumnTotals() = runBlocking {
        val service = exploreSampleService()
        withTempMcpClient(service) { client ->
            val resultId = storeExploreResult(client)
            val body =
                client
                    .callTool(
                        "pivot_result",
                        mapOf(
                            "result_id" to resultId,
                            "row_dimensions" to
                                listOf(
                                    Json.parseToJsonElement(
                                        """{"column":"amount","grouping":{"bin_size":"25","bin_start":"0"}}"""
                                    )
                                ),
                            "measures" to
                                listOf(Json.parseToJsonElement("""{"alias":"n","fn":"count"}""")),
                            "show_column_totals" to false,
                        ),
                    )
                    .json()
            assertEquals("3", body.getValue("row_count").jsonPrimitive.content)
            val rows = body.getValue("rows").jsonArray
            assertEquals("0 – 25", rows[0].jsonObject.getValue("amount").jsonPrimitive.content)
            assertEquals("2", rows[0].jsonObject.getValue("Count").jsonPrimitive.content)
            assertEquals("25 – 50", rows[1].jsonObject.getValue("amount").jsonPrimitive.content)
            assertEquals("2", rows[1].jsonObject.getValue("Count").jsonPrimitive.content)
            assertEquals("50 – 75", rows[2].jsonObject.getValue("amount").jsonPrimitive.content)
            assertEquals("1", rows[2].jsonObject.getValue("Count").jsonPrimitive.content)
        }
    }

    @Test
    fun pivotResultHandlesUnknownIdsAndInvalidArguments() = runBlocking {
        val service = exploreSampleService()
        withTempMcpClient(service) { client ->
            val unknown = client.callTool("pivot_result", mapOf("result_id" to "missing"))
            assertEquals(true, unknown.isError)
            assertEquals("not_found", unknown.json().getValue("error").jsonPrimitive.content)
            assertEquals(
                "Result not found",
                unknown.json().getValue("message").jsonPrimitive.content,
            )

            val resultId = storeExploreResult(client)
            val notArray =
                client.callTool(
                    "pivot_result",
                    mapOf("result_id" to resultId, "row_dimensions" to "region"),
                )
            assertEquals(true, notArray.isError)
            assertEquals(
                "row_dimensions must be an array",
                notArray.json().getValue("message").jsonPrimitive.content,
            )

            val unknownArgument =
                client.callTool(
                    "pivot_result",
                    mapOf("result_id" to resultId, "bogus" to true),
                )
            assertEquals(true, unknownArgument.isError)
            assertEquals(
                "unknown argument 'bogus'",
                unknownArgument.json().getValue("message").jsonPrimitive.content,
            )

            val badTotals =
                client.callTool(
                    "pivot_result",
                    mapOf("result_id" to resultId, "show_row_totals" to "yes"),
                )
            assertEquals(true, badTotals.isError)
            assertEquals(
                "show_row_totals must be a boolean",
                badTotals.json().getValue("message").jsonPrimitive.content,
            )
        }
    }

    @Test
    fun filterResultRowsFiltersSortsAndProjects() = runBlocking {
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
                                        """{"column":"region","op":"eq","value":"east"}"""
                                    )
                                ),
                            "sorts" to
                                listOf(
                                    Json.parseToJsonElement(
                                        """{"column":"amount","direction":"desc"}"""
                                    )
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
        }
    }

    @Test
    fun filterResultRowsPagesAndClampsOffsets() = runBlocking {
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
    fun filterResultRowsProjectsOnlyThePageWindow() = runBlocking {
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
    fun filterResultRowsSupportsNullAndBetweenFilters() = runBlocking {
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
                                        """{"column":"amount","op":"between","value":"5","second_value":"20"}"""
                                    )
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
                                        """{"column":"amount","op":"is_null"}"""
                                    )
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
    fun pivotAndFilterRejectUnknownNestedKeysAndWrongTypes() = runBlocking {
        val service = exploreSampleService()
        withTempMcpClient(service) { client ->
            val resultId = storeExploreResult(client)
            val unknownDimensionKey =
                client.callTool(
                    "pivot_result",
                    mapOf(
                        "result_id" to resultId,
                        "row_dimensions" to
                            listOf(Json.parseToJsonElement("""{"column":"region","bogus":true}""")),
                    ),
                )
            assertEquals(true, unknownDimensionKey.isError)
            assertEquals(
                "unknown field 'row_dimensions[0].bogus'",
                unknownDimensionKey.json().getValue("message").jsonPrimitive.content,
            )

            val showSubtotalsString =
                client.callTool(
                    "pivot_result",
                    mapOf(
                        "result_id" to resultId,
                        "row_dimensions" to
                            listOf(
                                Json.parseToJsonElement(
                                    """{"column":"region","show_subtotals":"true"}"""
                                )
                            ),
                    ),
                )
            assertEquals(true, showSubtotalsString.isError)
            assertEquals(
                "row_dimensions[0].show_subtotals must be a boolean",
                showSubtotalsString.json().getValue("message").jsonPrimitive.content,
            )

            val includedKeysNumber =
                client.callTool(
                    "filter_result_rows",
                    mapOf(
                        "result_id" to resultId,
                        "filters" to
                            listOf(
                                Json.parseToJsonElement(
                                    """{"column":"region","op":"members","included_keys":[1]}"""
                                )
                            ),
                    ),
                )
            assertEquals(true, includedKeysNumber.isError)
            assertEquals(
                "filters[0].included_keys[0] must be a string",
                includedKeysNumber.json().getValue("message").jsonPrimitive.content,
            )
        }
    }

    @Test
    fun filterResultRowsStopsAtTheByteBudgetAndReturnsNextOffset() = runBlocking {
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

    @Test
    fun filterResultRowsHandlesUnknownIdsAndInvalidArguments() = runBlocking {
        val service = exploreSampleService()
        withTempMcpClient(service) { client ->
            val unknown = client.callTool("filter_result_rows", mapOf("result_id" to "missing"))
            assertEquals(true, unknown.isError)
            assertEquals("not_found", unknown.json().getValue("error").jsonPrimitive.content)

            val resultId = storeExploreResult(client)
            val zeroLimit =
                client.callTool(
                    "filter_result_rows",
                    mapOf("result_id" to resultId, "limit" to 0),
                )
            assertEquals(true, zeroLimit.isError)
            assertEquals(
                "limit must be 1 or more",
                zeroLimit.json().getValue("message").jsonPrimitive.content,
            )

            val unknownArgument =
                client.callTool(
                    "filter_result_rows",
                    mapOf("result_id" to resultId, "bogus" to 1),
                )
            assertEquals(true, unknownArgument.isError)
            assertEquals(
                "unknown argument 'bogus'",
                unknownArgument.json().getValue("message").jsonPrimitive.content,
            )
        }
    }

    @Test
    fun unknownColumnErrorsSuggestKnownColumnsInDialectCasing() = runBlocking {
        val service = exploreSampleService()
        service.runQueryResult =
            QueryRunResult(
                QueryResult(
                    columns =
                        listOf(
                            ResultColumn("CUSTOMERS__ID", "int"),
                            ResultColumn("CUSTOMERS__EMAIL", "text"),
                        ),
                    rows =
                        listOf(listOf(ResultCell.integer(1), ResultCell.text("user@example.com"))),
                    rowCount = 1,
                    truncated = false,
                    warnings = emptyList(),
                ),
                allowedMcpRisk(),
            )
        withTempMcpClient(service) { client ->
            val resultId = storeExploreResult(client)
            val unknown =
                client.callTool(
                    "pivot_result",
                    mapOf(
                        "result_id" to resultId,
                        "row_dimensions" to
                            listOf(Json.parseToJsonElement("""{"column":"customers__id"}""")),
                    ),
                )
            assertEquals(true, unknown.isError)
            assertEquals(
                "Unknown column 'customers__id'. Known columns: CUSTOMERS__EMAIL, CUSTOMERS__ID",
                unknown.json().getValue("message").jsonPrimitive.content,
            )
        }
    }

    @Test
    fun pivotResultRejectsMissingResultIdAndMalformedPayload() = runBlocking {
        val service = exploreSampleService()
        withTempMcpClient(service) { client ->
            val resultId = storeExploreResult(client)
            val missingResultId = client.callTool("pivot_result", emptyMap())
            assertEquals(
                "result_id is required",
                missingResultId.json().getValue("message").jsonPrimitive.content,
            )

            val malformed =
                client.callTool(
                    "pivot_result",
                    mapOf("result_id" to resultId, "row_dimensions" to listOf(5)),
                )
            assertEquals(true, malformed.isError)
            assertEquals(
                "invalid_arguments",
                malformed.json().getValue("error").jsonPrimitive.content,
            )
        }
    }

    @Test
    fun filterResultRowsRejectsMissingResultIdAndMalformedPayload() = runBlocking {
        val service = exploreSampleService()
        withTempMcpClient(service) { client ->
            val resultId = storeExploreResult(client)
            val missingResultId = client.callTool("filter_result_rows", emptyMap())
            assertEquals(
                "result_id is required",
                missingResultId.json().getValue("message").jsonPrimitive.content,
            )

            val malformed =
                client.callTool(
                    "filter_result_rows",
                    mapOf("result_id" to resultId, "filters" to "x"),
                )
            assertEquals(true, malformed.isError)
            assertEquals(
                "invalid_arguments",
                malformed.json().getValue("error").jsonPrimitive.content,
            )
        }
    }

    @Test
    fun exploreToolsAreReadOnly() = runBlocking {
        withTempMcpClient(exploreSampleService()) { client ->
            val tools = client.listTools().tools.associateBy { it.name }
            assertEquals(true, tools.getValue("pivot_result").annotations?.readOnlyHint)
            assertEquals(true, tools.getValue("filter_result_rows").annotations?.readOnlyHint)
        }
    }
}

private suspend fun Client.callJson(name: String, arguments: Map<String, Any?>): JsonObject =
    callTool(name, arguments).json()

private suspend fun storeExploreResult(client: Client): String {
    val receipt =
        client
            .callTool(
                "run_query",
                mapOf(
                    "connection_id" to "c1",
                    "sql" to "SELECT id, email FROM public.customers",
                ),
            )
            .json()
    return receipt.getValue("result_id").jsonPrimitive.content
}
