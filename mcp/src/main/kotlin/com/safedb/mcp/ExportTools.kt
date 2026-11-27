package com.safedb.mcp

import com.safedb.export.writeQueryResultCsv
import com.safedb.persist.ensurePrivateDir
import com.safedb.persist.restrictToOwnerReadWrite
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
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

private val SAFE_EXPORT_FILENAME = Regex("^[A-Za-z0-9._-]+$")

internal fun registerExportTools(server: Server, resultStore: ResultStore, exportsDir: Path) {
    server.addTool(
        name = "export_result",
        description =
            "Write the original stored sample from run_query or run_saved_query to a CSV " +
                "file under the MCP exports directory. This exports all original sample rows and " +
                "columns, not filtered, projected, or pivoted output, even if those tools echo " +
                "the same result_id. Pass result_id and an optional simple filename. " +
                "Returns result_id, file_path, row_count, bytes, and sample_capped. " +
                "Unknown or expired result_id returns not_found.",
        inputSchema =
            ToolSchema(
                properties =
                    buildJsonObject {
                        put("result_id", resultIdProperty())
                        put(
                            "filename",
                            stringProp(
                                "After trimming, use only ASCII letters, digits, dots, " +
                                    "underscores, and dashes, with no leading or trailing dot. " +
                                    "A .csv suffix is appended unless already present " +
                                    "(case-insensitive). Omit or leave blank for <result_id>.csv.",
                            ),
                        )
                    },
                required = listOf("result_id"),
            ),
        outputSchema =
            objectOutputSchema(listOf("result_id", "file_path", "row_count", "bytes")) {
                put("result_id", stringProp())
                put("file_path", stringProp())
                put("row_count", intProp())
                put("bytes", intProp())
                put("sample_capped", boolProp())
            },
    ) { request ->
        runMcpTool("export_result failed") { handleExportResult(resultStore, exportsDir, request) }
    }
}

private suspend fun handleExportResult(
    resultStore: ResultStore,
    exportsDir: Path,
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
    return objectToolResult(receipt)
}

private fun resolveExportFilename(resultId: String, filename: String?): Arg<String> {
    if (filename == null) return Arg.Ok("$resultId.csv")
    if (!SAFE_EXPORT_FILENAME.matches(filename) || filename.startsWith(".")) {
        return Arg.Err(
            toolError(
                ERROR_INVALID_ARGUMENTS,
                "filename must be a simple file name without path separators",
            ),
        )
    }
    if (filename.endsWith(".")) {
        return Arg.Err(
            toolError(
                ERROR_INVALID_ARGUMENTS,
                "filename must not end with a dot; remove the trailing dot or use a .csv extension",
            ),
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
