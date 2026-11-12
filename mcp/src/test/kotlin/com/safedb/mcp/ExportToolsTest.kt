package com.safedb.mcp

import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ExportToolsTest {
    @Test
    fun exportResultWritesCsvWithTheDefaultFilename() = runBlocking {
        val service = exportService()
        withExportClient(service) { client, resultsDir ->
            val resultId = storeExportResult(client)
            val receipt = client.callJson("export_result", mapOf("result_id" to resultId))
            assertEquals(resultId, receipt.string("result_id"))
            assertEquals("2", receipt.string("row_count"))
            assertFalse(receipt.containsKey("sample_capped"))
            val target = resultsDir.resolve("exports").resolve("$resultId.csv")
            assertEquals(target.toString(), receipt.string("file_path"))
            assertTrue(Files.isRegularFile(target))
            assertEquals(Files.size(target).toString(), receipt.string("bytes"))
            assertEquals(
                "id,email\r\n1,user1@example.com\r\n2,user2@example.com\r\n",
                Files.readString(target),
            )
        }
    }

    @Test
    fun exportResultAcceptsSimpleCustomFilenames() = runBlocking {
        val service = exportService()
        withExportClient(service) { client, resultsDir ->
            val resultId = storeExportResult(client)

            val withoutExtension =
                client.callJson(
                    "export_result",
                    mapOf("result_id" to resultId, "filename" to "report"),
                )
            val report = resultsDir.resolve("exports").resolve("report.csv")
            assertEquals(report.toString(), withoutExtension.string("file_path"))
            assertTrue(Files.isRegularFile(report))

            val withUppercaseExtension =
                client.callJson(
                    "export_result",
                    mapOf("result_id" to resultId, "filename" to "Report.CSV"),
                )
            val upper = resultsDir.resolve("exports").resolve("Report.CSV")
            assertEquals(upper.toString(), withUppercaseExtension.string("file_path"))
            assertTrue(Files.isRegularFile(upper))

            val dotted =
                client.callJson(
                    "export_result",
                    mapOf("result_id" to resultId, "filename" to "daily.summary"),
                )
            assertTrue(dotted.string("file_path").endsWith("daily.summary.csv"))

            val blank =
                client.callJson("export_result", mapOf("result_id" to resultId, "filename" to ""))
            assertEquals(
                resultsDir.resolve("exports").resolve("$resultId.csv").toString(),
                blank.string("file_path"),
            )
        }
    }

    @Test
    fun exportResultRejectsPathSeparatorsAndUnknownResults() = runBlocking {
        val service = exportService()
        withExportClient(service) { client, resultsDir ->
            val resultId = storeExportResult(client)
            listOf("../evil", "sub/evil", "..", ".", ".hidden", "a b.csv", """a\b.csv""").forEach {
                filename ->
                val result =
                    client.callTool(
                        "export_result",
                        mapOf("result_id" to resultId, "filename" to filename),
                    )
                assertEquals(true, result.isError, "expected failure for $filename")
                assertEquals(
                    "invalid_arguments",
                    result.json().string("error"),
                    "expected invalid_arguments for $filename",
                )
                assertEquals(
                    "filename must be a simple file name without path separators",
                    result.json().string("message"),
                )
            }
            assertFalse(Files.exists(resultsDir.resolve("evil.csv")))
            assertFalse(Files.exists(resultsDir.resolve("evil")))

            val unknown = client.callTool("export_result", mapOf("result_id" to "missing"))
            assertEquals(true, unknown.isError)
            assertEquals("not_found", unknown.json().string("error"))
            assertEquals("Result not found", unknown.json().string("message"))

            val unknownArgument =
                client.callTool(
                    "export_result",
                    mapOf("result_id" to resultId, "bogus" to true),
                )
            assertEquals("invalid_arguments", unknownArgument.json().string("error"))
            assertEquals(
                "unknown argument 'bogus'",
                unknownArgument.json().string("message"),
            )
        }
    }

    @Test
    fun exportResultMarksCappedSamples() = runBlocking {
        val service = exportService()
        service.runQueryResult =
            com.safedb.service.QueryRunResult(
                sampleMcpQueryResult(truncated = true),
                allowedMcpRisk(),
            )
        withExportClient(service) { client, _ ->
            val resultId = storeExportResult(client)
            val receipt = client.callJson("export_result", mapOf("result_id" to resultId))
            assertEquals("true", receipt.string("sample_capped"))
        }
    }

    @Test
    fun exportResultToolHasNoAnnotations() = runBlocking {
        withTempMcpClient(exportService()) { client ->
            val tool = client.listTools().tools.single { it.name == "export_result" }
            assertNull(tool.annotations)
        }
    }
}

private suspend fun withExportClient(
    service: RecordingSafeDbService,
    block: suspend (Client, Path) -> Unit,
) {
    val resultsDir = Files.createTempDirectory("safedb-mcp-export")
    try {
        withMcpClient(createSafeDbMcpServer(service, resultsDir = resultsDir)) { client ->
            block(client, resultsDir)
        }
    } finally {
        resultsDir.toFile().deleteRecursively()
    }
}

private fun exportService(): RecordingSafeDbService {
    val service = RecordingSafeDbService()
    service.connections += sampleMcpConnection()
    service.schemas["c1"] = sampleMcpSchema()
    service.runQueryResult =
        com.safedb.service.QueryRunResult(
            sampleMcpQueryResult(rowCount = 2),
            allowedMcpRisk(),
        )
    return service
}

private suspend fun storeExportResult(client: Client): String {
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
    return receipt.string("result_id")
}

private suspend fun Client.callJson(name: String, arguments: Map<String, Any?>): JsonObject =
    callTool(name, arguments).json()

private fun CallToolResult.json(): JsonObject =
    Json.parseToJsonElement((content.single() as TextContent).text).jsonObject

private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content
