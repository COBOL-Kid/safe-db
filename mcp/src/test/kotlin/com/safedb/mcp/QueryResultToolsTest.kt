package com.safedb.mcp

import com.safedb.model.ResultCell
import com.safedb.model.ResultColumn
import com.safedb.service.QueryRunResult
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class QueryResultToolsTest {
    @Test
    fun getResultRowsPagesWithCapAndSummarizeUsesStoredId() = runBlocking {
        val dataDir = Files.createTempDirectory("safedb-mcp-results")
        val resultsDir = dataDir.resolve("results")
        try {
            val service = queryService()
            service.runQueryResult = sampleMcpRunResult(rowCount = 60, truncated = true)
            withMcpClient(
                createSafeDbMcpServer(
                    service,
                    resultsDir = resultsDir,
                    exportsDir = dataDir.resolve("exports"),
                )
            ) { client ->
                val receipt =
                    client
                        .callTool(
                            "run_query",
                            mapOf(
                                "connection_id" to "c1",
                                "sql" to "SELECT id FROM public.customers LIMIT 60",
                            ),
                        )
                        .json()
                val resultId = receipt.getValue("result_id").jsonPrimitive.content
                assertEquals(PREVIEW_ROW_LIMIT, receipt.getValue("preview").jsonArray.size)

                val page = client.callTool("get_result_rows", mapOf("result_id" to resultId)).json()
                assertEquals(resultId, page.getValue("result_id").jsonPrimitive.content)
                assertEquals("0", page.getValue("offset").jsonPrimitive.content)
                assertEquals("60", page.getValue("row_count").jsonPrimitive.content)
                assertFalse(page.containsKey("sample_capped"))
                assertFalse(page.containsKey("truncated"))
                assertEquals(GET_RESULT_ROWS_MAX, page.getValue("rows").jsonArray.size)
                assertEquals("true", page.getValue("page_truncated").jsonPrimitive.content)
                assertEquals("50", page.getValue("next_offset").jsonPrimitive.content)
                assertEquals(
                    "1",
                    page
                        .getValue("rows")
                        .jsonArray[0]
                        .jsonObject
                        .getValue("id")
                        .jsonPrimitive
                        .content,
                )

                val capped =
                    client
                        .callTool(
                            "get_result_rows",
                            mapOf("result_id" to resultId, "limit" to 100),
                        )
                        .json()
                assertEquals(GET_RESULT_ROWS_MAX, capped.getValue("rows").jsonArray.size)

                val tail =
                    client
                        .callTool(
                            "get_result_rows",
                            mapOf("result_id" to resultId, "offset" to 55, "limit" to 50),
                        )
                        .json()
                assertEquals(5, tail.getValue("rows").jsonArray.size)
                assertEquals("false", tail.getValue("page_truncated").jsonPrimitive.content)
                assertFalse(tail.containsKey("next_offset"))
                assertEquals(
                    "56",
                    tail
                        .getValue("rows")
                        .jsonArray[0]
                        .jsonObject
                        .getValue("id")
                        .jsonPrimitive
                        .content,
                )

                val summary =
                    client.callTool("summarize_result", mapOf("result_id" to resultId)).json()
                assertEquals(resultId, summary.getValue("result_id").jsonPrimitive.content)
                assertEquals("60", summary.getValue("row_count").jsonPrimitive.content)
                assertEquals("true", summary.getValue("sample_capped").jsonPrimitive.content)
                assertFalse(summary.containsKey("truncated"))
                val columns = summary.getValue("columns").jsonArray
                assertEquals(2, columns.size)
                val idCol = columns[0].jsonObject
                assertEquals("id", idCol.getValue("name").jsonPrimitive.content)
                assertEquals("0", idCol.getValue("null_count").jsonPrimitive.content)
                assertEquals("1", idCol.getValue("min").jsonPrimitive.content)
                assertEquals("60", idCol.getValue("max").jsonPrimitive.content)
                assertEquals(DISTINCT_VALUE_LIMIT, idCol.getValue("distinct").jsonArray.size)
                assertEquals("true", idCol.getValue("distinct_truncated").jsonPrimitive.content)
            }
        } finally {
            dataDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun getResultRowsStopsAtTheByteBudgetAndReturnsNextOffset() = runBlocking {
        val service = queryService()
        val payload = "x".repeat(2_048)
        val wideColumns =
            (1..24).map { index -> ResultColumn("c${index.toString().padStart(2, '0')}", "text") }
        val wideRows = List(50) { wideColumns.map { ResultCell.text(payload) } }
        service.runQueryResult =
            QueryRunResult(
                sampleMcpQueryResult(rowCount = 50).let { sample ->
                    sample.copy(
                        columns = wideColumns,
                        rows = wideRows,
                    )
                },
                allowedMcpRisk(),
            )
        withTempMcpClient(service) { client ->
            val receipt =
                client
                    .callTool(
                        "run_query",
                        mapOf(
                            "connection_id" to "c1",
                            "sql" to "SELECT id FROM public.customers LIMIT 50",
                        ),
                    )
                    .json()
            val preview = receipt.getValue("preview").jsonArray
            assertTrue(preview.size <= PREVIEW_ROW_LIMIT)
            assertEquals("true", receipt.getValue("preview_truncated").jsonPrimitive.content)
            assertTrue(
                preview
                    .first()
                    .jsonObject
                    .getValue("c01")
                    .jsonPrimitive
                    .content
                    .endsWith(MCP_CELL_TRUNCATION_MARKER)
            )
            val resultId = receipt.getValue("result_id").jsonPrimitive.content
            val page = client.callTool("get_result_rows", mapOf("result_id" to resultId)).json()
            assertTrue(page.getValue("rows").jsonArray.size < GET_RESULT_ROWS_MAX)
            assertEquals("true", page.getValue("page_truncated").jsonPrimitive.content)
            assertEquals(
                page.getValue("rows").jsonArray.size.toString(),
                page.getValue("next_offset").jsonPrimitive.content,
            )
        }
    }

    @Test
    fun getResultRowsCoercesPagingArguments() = runBlocking {
        val service = queryService()
        service.runQueryResult = sampleMcpRunResult(rowCount = 3)
        withTempMcpClient(service) { client ->
            val receipt =
                client
                    .callTool(
                        "run_query",
                        mapOf(
                            "connection_id" to "c1",
                            "sql" to "SELECT id FROM public.customers LIMIT 3",
                        ),
                    )
                    .json()
            val resultId = receipt.getValue("result_id").jsonPrimitive.content

            val clampedOffset =
                client
                    .callTool(
                        "get_result_rows",
                        mapOf("result_id" to resultId, "offset" to -7, "limit" to 2),
                    )
                    .json()
            assertEquals("0", clampedOffset.getValue("offset").jsonPrimitive.content)
            assertEquals(2, clampedOffset.getValue("rows").jsonArray.size)

            val zeroLimit =
                client.callTool(
                    "get_result_rows",
                    mapOf("result_id" to resultId, "limit" to 0),
                )
            assertEquals(true, zeroLimit.isError)
            assertEquals(
                "invalid_arguments",
                zeroLimit.json().getValue("error").jsonPrimitive.content,
            )
            assertEquals(
                "limit must be 1 or more",
                zeroLimit.json().getValue("message").jsonPrimitive.content,
            )

            val negativeLimit =
                client.callTool(
                    "get_result_rows",
                    mapOf("result_id" to resultId, "limit" to -3),
                )
            assertEquals(true, negativeLimit.isError)
            assertEquals(
                "invalid_arguments",
                negativeLimit.json().getValue("error").jsonPrimitive.content,
            )

            val numericStrings =
                client.callTool(
                    "get_result_rows",
                    mapOf("result_id" to resultId, "offset" to "1", "limit" to "2"),
                )
            assertEquals(true, numericStrings.isError)
            assertEquals(
                "invalid_arguments",
                numericStrings.json().getValue("error").jsonPrimitive.content,
            )

            val invalidValues =
                client.callTool(
                    "get_result_rows",
                    mapOf(
                        "result_id" to resultId,
                        "offset" to JsonObject(emptyMap()),
                        "limit" to "not-a-number",
                    ),
                )
            assertEquals(true, invalidValues.isError)
            assertEquals(
                "invalid_arguments",
                invalidValues.json().getValue("error").jsonPrimitive.content,
            )
        }
    }

    @Test
    fun artifactWriteFailureStillReturnsPageableInMemoryResult() = runBlocking {
        val dataDir = Files.createTempDirectory("safedb-mcp-results")
        val resultsDir = dataDir.resolve("results")
        try {
            val service = queryService()
            val store =
                ResultStore(
                    resultsDir = resultsDir,
                    onAfterJsonlWrite = { error("simulated artifact failure") },
                )
            withMcpClient(
                createSafeDbMcpServer(
                    service,
                    resultsDir = resultsDir,
                    exportsDir = dataDir.resolve("exports"),
                    resultStore = store,
                )
            ) { client ->
                val receipt =
                    client
                        .callTool(
                            "run_query",
                            mapOf(
                                "connection_id" to "c1",
                                "sql" to "SELECT id FROM public.customers",
                            ),
                        )
                        .json()
                assertFalse(receipt.containsKey("artifact_path"))
                assertFalse(receipt.containsKey("artifact_bytes"))
                val resultId = receipt.getValue("result_id").jsonPrimitive.content

                val page =
                    client
                        .callTool(
                            "get_result_rows",
                            mapOf("result_id" to resultId, "offset" to 1, "limit" to 1),
                        )
                        .json()
                assertEquals(1, page.getValue("rows").jsonArray.size)
                assertEquals(
                    "2",
                    page
                        .getValue("rows")
                        .jsonArray
                        .single()
                        .jsonObject
                        .getValue("id")
                        .jsonPrimitive
                        .content,
                )
                Files.list(resultsDir).use { files -> assertEquals(0L, files.count()) }
            }
        } finally {
            dataDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun unknownAndExpiredResultIsNotFound() = runBlocking {
        val dataDir = Files.createTempDirectory("safedb-mcp-results")
        val resultsDir = dataDir.resolve("results")
        try {
            val service = queryService()
            val clock = mutableListOf(1_000L)
            withMcpClient(
                createSafeDbMcpServer(
                    service,
                    nowMs = { clock.single() },
                    resultStoreTtlMs = 100,
                    resultsDir = resultsDir,
                    exportsDir = dataDir.resolve("exports"),
                )
            ) { client ->
                val missing = client.callTool("get_result_rows", emptyMap())
                assertEquals(true, missing.isError)
                assertEquals(
                    "invalid_arguments",
                    missing.json().getValue("error").jsonPrimitive.content,
                )
                assertEquals(
                    "result_id is required",
                    missing.json().getValue("message").jsonPrimitive.content,
                )

                val unknown = client.callTool("get_result_rows", mapOf("result_id" to "missing"))
                assertEquals(true, unknown.isError)
                assertEquals("not_found", unknown.json().getValue("error").jsonPrimitive.content)
                assertEquals(
                    "Result not found",
                    unknown.json().getValue("message").jsonPrimitive.content,
                )

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
                val resultId = receipt.getValue("result_id").jsonPrimitive.content
                val live = client.callTool("get_result_rows", mapOf("result_id" to resultId))
                assertFalse(live.isError == true)

                clock[0] = 1_100
                val expired = client.callTool("get_result_rows", mapOf("result_id" to resultId))
                assertEquals(true, expired.isError)
                assertEquals("not_found", expired.json().getValue("error").jsonPrimitive.content)
                assertEquals(
                    "Result not found",
                    expired.json().getValue("message").jsonPrimitive.content,
                )

                val expiredSummary =
                    client.callTool("summarize_result", mapOf("result_id" to resultId))
                assertEquals(true, expiredSummary.isError)
                assertEquals(
                    "not_found",
                    expiredSummary.json().getValue("error").jsonPrimitive.content,
                )
                assertEquals(
                    "Result not found",
                    expiredSummary.json().getValue("message").jsonPrimitive.content,
                )
            }
        } finally {
            dataDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun resultToolsAreReadOnly() = runBlocking {
        withTempMcpClient(queryService()) { client ->
            val tools = client.listTools().tools.associateBy { it.name }
            assertEquals(true, tools.getValue("get_result_rows").annotations?.readOnlyHint)
            assertEquals(true, tools.getValue("summarize_result").annotations?.readOnlyHint)
        }
    }
}
