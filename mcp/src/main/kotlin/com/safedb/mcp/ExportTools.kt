package com.safedb.mcp

import com.safedb.export.writeQueryResultCsv
import com.safedb.persist.ensurePrivateDir
import com.safedb.persist.restrictToOwnerReadWrite
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val SAFE_EXPORT_FILENAME = Regex("^[A-Za-z0-9._-]+$")

internal fun registerExportTools(server: Server, resultStore: ResultStore, resultsDir: Path) {
    server.addTool(
        name = "export_result",
        description =
            "Write the in-memory sample of a previous run_query to a CSV file under the " +
                "MCP results directory. Pass result_id and an optional simple filename " +
                "(letters, digits, dot, underscore, and dash only); .csv is appended when " +
                "missing. Returns result_id, file_path, row_count, bytes, and sample_capped. " +
                "Unknown or expired result_id returns not_found.",
        inputSchema =
            ToolSchema(
                properties =
                    buildJsonObject {
                        put(
                            "result_id",
                            buildJsonObject {
                                put("type", "string")
                                put("description", "result_id from a successful run_query receipt")
                            },
                        )
                        put(
                            "filename",
                            buildJsonObject {
                                put("type", "string")
                                put(
                                    "description",
                                    "Optional simple filename without path separators. " +
                                        "Defaults to <result_id>.csv.",
                                )
                            },
                        )
                    },
                required = listOf("result_id"),
            ),
    ) { request ->
        runMcpTool("export_result failed") { handleExportResult(resultStore, resultsDir, request) }
    }
}

private suspend fun handleExportResult(
    resultStore: ResultStore,
    resultsDir: Path,
    request: CallToolRequest,
): CallToolResult {
    requireKnownArguments(request, "result_id", "filename").getOrElse {
        return it
    }
    val resultId =
        requiredString(request, "result_id").getOrElse {
            return it
        }
    val filename =
        optionalString(request, "filename").getOrElse {
            return it
        }
    val entry =
        resultStore.getEntry(resultId) ?: return toolError(ERROR_NOT_FOUND, "Result not found")
    val resolvedFilename =
        when (val resolved = resolveExportFilename(resultId, filename)) {
            is Arg.Ok -> resolved.value
            is Arg.Err -> return resolved.result
        }

    val exportsDir = resultsDir.resolve("exports")
    val target = exportsDir.resolve(resolvedFilename)
    val bytes =
        withContext(Dispatchers.IO) {
            ensurePrivateDir(exportsDir)
            Files.newOutputStream(
                    target,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE,
                )
                .use { output -> writeQueryResultCsv(entry.result, output) }
            restrictToOwnerReadWrite(target)
            Files.size(target)
        }

    val receipt =
        ExportResultReceipt(
            resultId = resultId,
            filePath = target.toString(),
            rowCount = entry.result.rowCount,
            bytes = bytes,
            sampleCapped = entry.result.truncated,
        )
    return CallToolResult(content = listOf(TextContent(text = toolJson.encodeToString(receipt))))
}

private fun resolveExportFilename(resultId: String, filename: String?): Arg<String> {
    if (filename == null) return Arg.Ok("$resultId.csv")
    if (
        !SAFE_EXPORT_FILENAME.matches(filename) ||
            filename == "." ||
            filename == ".." ||
            filename.startsWith(".")
    ) {
        return Arg.Err(
            toolError(
                ERROR_INVALID_ARGUMENTS,
                "filename must be a simple file name without path separators",
            )
        )
    }
    return Arg.Ok(if (filename.endsWith(".csv", ignoreCase = true)) filename else "$filename.csv")
}

@Serializable
internal data class ExportResultReceipt(
    @SerialName("result_id") val resultId: String,
    @SerialName("file_path") val filePath: String,
    @SerialName("row_count") val rowCount: Int,
    val bytes: Long,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("sample_capped")
    val sampleCapped: Boolean = false,
)
