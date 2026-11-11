package com.safedb.mcp

import com.safedb.model.QueryResult
import com.safedb.model.ResultCell
import com.safedb.model.ResultColumn
import com.safedb.service.QueryRunResult
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
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
        val service = exploreService()
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
        val service = exploreService()
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
        val service = exploreService()
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
        val service = exploreService()
        withTempMcpClient(service) { client ->
            val unknown = client.callTool("pivot_result", mapOf("result_id" to "missing"))
            assertEquals(true, unknown.isError)
            assertEquals("not_found", unknown.json().getValue("error").jsonPrimitive.content)
            assertEquals(
                "Result not found",
                unknown.json().getValue("message").jsonPrimitive.content,
            )

            val resultId = storeExploreResult(client)
            val unknownColumn =
                client.callTool(
                    "pivot_result",
                    mapOf(
                        "result_id" to resultId,
                        "row_dimensions" to
                            listOf(Json.parseToJsonElement("""{"column":"nope"}""")),
                    ),
                )
            assertEquals(true, unknownColumn.isError)
            assertEquals(
                "invalid_arguments",
                unknownColumn.json().getValue("error").jsonPrimitive.content,
            )
            assertEquals(
                "Unknown column 'nope'. Known columns: amount, created, region",
                unknownColumn.json().getValue("message").jsonPrimitive.content,
            )

            val unknownMeasureColumn =
                client.callTool(
                    "pivot_result",
                    mapOf(
                        "result_id" to resultId,
                        "measures" to
                            listOf(
                                Json.parseToJsonElement(
                                    """{"alias":"x","fn":"sum","source_column":"nope"}"""
                                )
                            ),
                    ),
                )
            assertEquals(true, unknownMeasureColumn.isError)
            assertEquals(
                "Unknown column 'nope'. Known columns: amount, created, region",
                unknownMeasureColumn.json().getValue("message").jsonPrimitive.content,
            )

            val unknownFn =
                client.callTool(
                    "pivot_result",
                    mapOf(
                        "result_id" to resultId,
                        "measures" to
                            listOf(Json.parseToJsonElement("""{"alias":"x","fn":"median"}""")),
                    ),
                )
            assertEquals(true, unknownFn.isError)
            assertTrue(
                unknownFn
                    .json()
                    .getValue("message")
                    .jsonPrimitive
                    .content
                    .contains("Unknown measure fn")
            )

            val unknownGrouping =
                client.callTool(
                    "pivot_result",
                    mapOf(
                        "result_id" to resultId,
                        "row_dimensions" to
                            listOf(
                                Json.parseToJsonElement(
                                    """{"column":"created","grouping":"fortnight"}"""
                                )
                            ),
                    ),
                )
            assertEquals(true, unknownGrouping.isError)
            assertTrue(
                unknownGrouping
                    .json()
                    .getValue("message")
                    .jsonPrimitive
                    .content
                    .contains("Unknown grouping")
            )

            val missingColumn =
                client.callTool(
                    "pivot_result",
                    mapOf(
                        "result_id" to resultId,
                        "row_dimensions" to listOf(Json.parseToJsonElement("""{"label":"x"}""")),
                    ),
                )
            assertEquals(true, missingColumn.isError)
            assertTrue(
                missingColumn.json().getValue("message").jsonPrimitive.content.contains("column")
            )

            val unknownFilterOp =
                client.callTool(
                    "pivot_result",
                    mapOf(
                        "result_id" to resultId,
                        "filters" to
                            listOf(
                                Json.parseToJsonElement(
                                    """{"column":"region","op":"regex","measure_alias":"count"}"""
                                )
                            ),
                    ),
                )
            assertEquals(true, unknownFilterOp.isError)
            assertTrue(
                unknownFilterOp
                    .json()
                    .getValue("message")
                    .jsonPrimitive
                    .content
                    .contains("Unknown filter op")
            )

            val badSort =
                client.callTool(
                    "pivot_result",
                    mapOf(
                        "result_id" to resultId,
                        "sort" to Json.parseToJsonElement("""{"direction":"asc"}"""),
                    ),
                )
            assertEquals(true, badSort.isError)
            assertTrue(
                badSort
                    .json()
                    .getValue("message")
                    .jsonPrimitive
                    .content
                    .contains("column or measure_alias")
            )

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
        val service = exploreService()
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
        val service = exploreService()
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
    fun filterResultRowsSupportsNullAndBetweenFilters() = runBlocking {
        val service = exploreService()
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
    fun filterResultRowsHandlesUnknownIdsAndInvalidArguments() = runBlocking {
        val service = exploreService()
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

            val unknownColumn =
                client.callTool(
                    "filter_result_rows",
                    mapOf(
                        "result_id" to resultId,
                        "filters" to
                            listOf(
                                Json.parseToJsonElement(
                                    """{"column":"nope","op":"equals","value":"x"}"""
                                )
                            ),
                    ),
                )
            assertEquals(true, unknownColumn.isError)
            assertEquals(
                "Unknown column 'nope'. Known columns: amount, created, region",
                unknownColumn.json().getValue("message").jsonPrimitive.content,
            )

            val unknownSortColumn =
                client.callTool(
                    "filter_result_rows",
                    mapOf(
                        "result_id" to resultId,
                        "sorts" to listOf(Json.parseToJsonElement("""{"column":"nope"}""")),
                    ),
                )
            assertEquals(true, unknownSortColumn.isError)
            assertEquals(
                "Unknown column 'nope'. Known columns: amount, created, region",
                unknownSortColumn.json().getValue("message").jsonPrimitive.content,
            )

            val unknownProjection =
                client.callTool(
                    "filter_result_rows",
                    mapOf("result_id" to resultId, "columns" to listOf("nope")),
                )
            assertEquals(true, unknownProjection.isError)
            assertEquals(
                "Unknown column 'nope'. Known columns: amount, created, region",
                unknownProjection.json().getValue("message").jsonPrimitive.content,
            )

            val unknownOp =
                client.callTool(
                    "filter_result_rows",
                    mapOf(
                        "result_id" to resultId,
                        "filters" to
                            listOf(Json.parseToJsonElement("""{"column":"region","op":"regex"}""")),
                    ),
                )
            assertEquals(true, unknownOp.isError)
            assertTrue(
                unknownOp
                    .json()
                    .getValue("message")
                    .jsonPrimitive
                    .content
                    .contains("Unknown filter op")
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
        val service = exploreService()
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
    fun pivotResultCoversGroupingAndSortModeVariants() = runBlocking {
        val service = exploreService()
        withTempMcpClient(service) { client ->
            val resultId = storeExploreResult(client)
            val body =
                client.callJson(
                    "pivot_result",
                    mapOf(
                        "result_id" to resultId,
                        "row_dimensions" to
                            listOf(
                                Json.parseToJsonElement(
                                    """{"column":"created","grouping":"exact"}"""
                                ),
                                Json.parseToJsonElement(
                                    """{"column":"created","grouping":"year","sort_mode":"label_desc"}"""
                                ),
                                Json.parseToJsonElement(
                                    """{"column":"created","grouping":"quarter","sort_mode":"value_asc","sort_measure_alias":"count"}"""
                                ),
                                Json.parseToJsonElement(
                                    """{"column":"created","grouping":"iso_week","sort_mode":"value_desc","sort_measure_alias":"count"}"""
                                ),
                                Json.parseToJsonElement(
                                    """{"column":"created","grouping":"day","sort_mode":"source_order"}"""
                                ),
                            ),
                        "measures" to
                            listOf(
                                Json.parseToJsonElement("""{"alias":"count","fn":"count"}"""),
                                Json.parseToJsonElement(
                                    """{"alias":"share","fn":"sum","source_column":"amount","show_as":"percent_grand_total"}"""
                                ),
                            ),
                        "show_subtotals" to false,
                    ),
                )
            assertTrue(body.getValue("row_count").jsonPrimitive.content.toInt() > 0)
        }
    }

    @Test
    fun pivotResultRejectsMissingResultIdAndMalformedPayload() = runBlocking {
        val service = exploreService()
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
    fun filterResultRowsCoversFilterOpAndSortVariants() = runBlocking {
        val service = exploreService()
        withTempMcpClient(service) { client ->
            val resultId = storeExploreResult(client)
            val allOps =
                client.callJson(
                    "filter_result_rows",
                    mapOf(
                        "result_id" to resultId,
                        "filters" to
                            listOf(
                                Json.parseToJsonElement(
                                    """{"column":"region","op":"contains","value":"e"}"""
                                ),
                                Json.parseToJsonElement(
                                    """{"column":"region","op":"not_equals","value":"west"}"""
                                ),
                                Json.parseToJsonElement(
                                    """{"column":"region","op":"starts_with","value":"ea"}"""
                                ),
                                Json.parseToJsonElement(
                                    """{"column":"region","op":"ends_with","value":"st"}"""
                                ),
                                Json.parseToJsonElement(
                                    """{"column":"amount","op":"gt","value":"5"}"""
                                ),
                                Json.parseToJsonElement(
                                    """{"column":"amount","op":"gte","value":"10"}"""
                                ),
                                Json.parseToJsonElement(
                                    """{"column":"amount","op":"lt","value":"100"}"""
                                ),
                                Json.parseToJsonElement(
                                    """{"column":"amount","op":"lte","value":"20"}"""
                                ),
                                Json.parseToJsonElement(
                                    """{"column":"amount","op":"is_not_null"}"""
                                ),
                                Json.parseToJsonElement(
                                    """{"column":"amount","op":"between","value":"5","second_value":"25"}"""
                                ),
                            ),
                        "sorts" to
                            listOf(
                                Json.parseToJsonElement(
                                    """{"column":"region","direction":"asc"}"""
                                ),
                                Json.parseToJsonElement(
                                    """{"column":"amount","direction":"desc"}"""
                                ),
                            ),
                    ),
                )
            assertEquals("2", allOps.getValue("matched_row_count").jsonPrimitive.content)

            val members =
                client.callJson(
                    "filter_result_rows",
                    mapOf(
                        "result_id" to resultId,
                        "filters" to
                            listOf(
                                Json.parseToJsonElement(
                                    """{"column":"region","op":"members","included_keys":["TextCell:east"]}"""
                                )
                            ),
                    ),
                )
            assertEquals("2", members.getValue("matched_row_count").jsonPrimitive.content)
        }
    }

    @Test
    fun filterResultRowsRejectsMissingResultIdAndMalformedPayload() = runBlocking {
        val service = exploreService()
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
}

private suspend fun Client.callJson(name: String, arguments: Map<String, Any?>): JsonObject =
    callTool(name, arguments).json()

private fun exploreService(): RecordingSafeDbService {
    val service = RecordingSafeDbService()
    service.connections += sampleMcpConnection()
    service.passwords["c1"] = "should-not-leak"
    service.schemas["c1"] = sampleMcpSchema()
    service.runQueryResult = QueryRunResult(exploreQueryResult(), allowedMcpRisk())
    return service
}

private fun exploreQueryResult(): QueryResult =
    QueryResult(
        columns =
            listOf(
                ResultColumn("region", "text"),
                ResultColumn("amount", "int"),
                ResultColumn("created", "text"),
            ),
        rows =
            listOf(
                listOf(
                    ResultCell.text("east"),
                    ResultCell.integer(10),
                    ResultCell.text("2024-01-05"),
                ),
                listOf(
                    ResultCell.text("east"),
                    ResultCell.integer(20),
                    ResultCell.text("2024-02-15"),
                ),
                listOf(
                    ResultCell.text("west"),
                    ResultCell.integer(30),
                    ResultCell.text("2024-03-10"),
                ),
                listOf(
                    ResultCell.text("west"),
                    ResultCell.integer(40),
                    ResultCell.text("2024-04-20"),
                ),
                listOf(
                    ResultCell.text("north"),
                    ResultCell.integer(50),
                    ResultCell.text("2025-01-01"),
                ),
            ),
        rowCount = 5,
        truncated = false,
        warnings = emptyList(),
    )

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

private fun CallToolResult.text(): String = (content.single() as TextContent).text

private fun CallToolResult.json(): JsonObject = Json.parseToJsonElement(text()).jsonObject
