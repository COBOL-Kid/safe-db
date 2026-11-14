package com.safedb.mcp

import io.modelcontextprotocol.kotlin.sdk.client.Client
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class ExportToolsTest {
    @Test
    fun exportResultWritesCsvWithTheDefaultFilename() = runBlocking {
        val service = exportService()
        withExportClient(service) { client, _, exportsDir ->
            val resultId = storeExportResult(client)
            val receipt = client.callJson("export_result", mapOf("result_id" to resultId))
            assertEquals(resultId, receipt.string("result_id"))
            assertEquals("2", receipt.string("row_count"))
            assertFalse(receipt.containsKey("sample_capped"))
            val target = exportsDir.resolve("$resultId.csv")
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
        withExportClient(service) { client, _, exportsDir ->
            val resultId = storeExportResult(client)

            val withoutExtension =
                client.callJson(
                    "export_result",
                    mapOf("result_id" to resultId, "filename" to "report"),
                )
            val report = exportsDir.resolve("report.csv")
            assertEquals(report.toString(), withoutExtension.string("file_path"))
            assertTrue(Files.isRegularFile(report))

            val withUppercaseExtension =
                client.callJson(
                    "export_result",
                    mapOf("result_id" to resultId, "filename" to "Report.CSV"),
                )
            val upper = exportsDir.resolve("Report.CSV")
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
                exportsDir.resolve("$resultId.csv").toString(),
                blank.string("file_path"),
            )
        }
    }

    @Test
    fun exportResultRejectsPathSeparatorsAndUnknownResults() = runBlocking {
        val service = exportService()
        withExportClient(service) { client, resultsDir, exportsDir ->
            val resultId = storeExportResult(client)
            val exportsBefore = directoryNames(exportsDir)
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
            assertFalse(Files.exists(exportsDir.resolve("evil")))
            assertFalse(Files.exists(exportsDir.resolve("evil.csv")))
            assertFalse(Files.exists(exportsDir.parent.resolve("evil")))
            assertFalse(Files.exists(exportsDir.parent.resolve("evil.csv")))
            assertFalse(Files.exists(exportsDir.resolve("sub").resolve("evil")))
            assertFalse(Files.exists(exportsDir.resolve("sub").resolve("evil.csv")))
            assertEquals(exportsBefore, directoryNames(exportsDir))

            val unknown = client.callTool("export_result", mapOf("result_id" to "missing"))
            assertError(unknown, "not_found", "Result not found")

            val unknownArgument =
                client.callTool(
                    "export_result",
                    mapOf("result_id" to resultId, "bogus" to true),
                )
            assertError(unknownArgument, "invalid_arguments", "unknown argument 'bogus'")
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
        withExportClient(service) { client, _, _ ->
            val resultId = storeExportResult(client)
            val receipt = client.callJson("export_result", mapOf("result_id" to resultId))
            assertEquals("true", receipt.string("sample_capped"))
        }
    }

    @Test
    fun exportedCsvSurvivesResultStoreRestart() = runBlocking {
        val service = exportService()
        val dataDir = Files.createTempDirectory("safedb-mcp-export-persist")
        val resultsDir = dataDir.resolve("results")
        val exportsDir = dataDir.resolve("exports")
        try {
            lateinit var csvPath: Path
            lateinit var csvContents: String
            withMcpClient(
                createSafeDbMcpServer(
                    service,
                    resultsDir = resultsDir,
                    exportsDir = exportsDir,
                ),
            ) { client ->
                val resultId = storeExportResult(client)
                val receipt = client.callJson("export_result", mapOf("result_id" to resultId))
                csvPath = exportsDir.resolve("$resultId.csv")
                assertEquals(csvPath.toString(), receipt.string("file_path"))
                assertTrue(Files.isRegularFile(csvPath))
                csvContents = Files.readString(csvPath)
            }
            ResultStore(resultsDir)
            assertTrue(Files.isRegularFile(csvPath))
            assertEquals(csvContents, Files.readString(csvPath))
        } finally {
            dataDir.toFile().deleteRecursively()
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
    block: suspend (Client, Path, Path) -> Unit,
) {
    val dataDir = Files.createTempDirectory("safedb-mcp-export")
    val resultsDir = dataDir.resolve("results")
    val exportsDir = dataDir.resolve("exports")
    try {
        withMcpClient(
            createSafeDbMcpServer(
                service,
                resultsDir = resultsDir,
                exportsDir = exportsDir,
            ),
        ) { client ->
            block(client, resultsDir, exportsDir)
        }
    } finally {
        dataDir.toFile().deleteRecursively()
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

private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content

private fun directoryNames(directory: Path): List<String> =
    if (!Files.isDirectory(directory)) {
        emptyList()
    } else {
        Files.newDirectoryStream(directory).use { stream ->
            stream.map { it.fileName.toString() }.sorted()
        }
    }
