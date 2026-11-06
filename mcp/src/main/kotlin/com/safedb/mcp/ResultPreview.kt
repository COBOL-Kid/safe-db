package com.safedb.mcp

import com.safedb.model.QueryResult
import com.safedb.model.ResultCell
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

internal const val PREVIEW_ROW_LIMIT = 10
internal const val GET_RESULT_ROWS_MAX = 50
internal const val MCP_PAYLOAD_MAX_BYTES = 32 * 1024
internal const val MCP_CELL_MAX_BYTES = 512
internal const val MCP_CELL_TRUNCATION_MARKER = "…[truncated]"

internal data class ResultSlice(
    val rows: List<JsonObject>,
    val moreRowsRemain: Boolean,
    val cellsTruncated: Boolean,
    val nextOffset: Int?,
)

internal fun flattenCell(cell: ResultCell): JsonElement = flattenCellInternal(cell, clip = false)

internal fun flattenRow(columnNames: List<String>, row: List<ResultCell>): JsonObject =
    flattenRowInternal(columnNames, row, clip = false).row

internal fun flattenRows(
    result: QueryResult,
    offset: Int = 0,
    limit: Int = Int.MAX_VALUE,
): List<JsonObject> {
    val names = result.columns.map { it.name }
    val start = offset.coerceAtLeast(0)
    return result.rows.drop(start).take(limit.coerceAtLeast(0)).map { flattenRow(names, it) }
}

internal fun previewRows(
    result: QueryResult,
    limit: Int = PREVIEW_ROW_LIMIT,
): ResultSlice = sliceRows(result, offset = 0, limit = limit, maxBytes = MCP_PAYLOAD_MAX_BYTES)

internal fun sliceRows(
    result: QueryResult,
    offset: Int,
    limit: Int,
    maxBytes: Int = MCP_PAYLOAD_MAX_BYTES,
): ResultSlice {
    val names = result.columns.map { it.name }
    val start = offset.coerceAtLeast(0)
    val rows = mutableListOf<JsonObject>()
    var used = 2
    var cellsTruncated = false
    val remaining = result.rows.drop(start)
    for (row in remaining) {
        if (rows.size >= limit.coerceAtLeast(0)) break
        val flattened = flattenRowInternal(names, row, clip = true)
        val encoded = flattened.row.toString().toByteArray(Charsets.UTF_8)
        val extra = encoded.size + if (rows.isEmpty()) 0 else 1
        if (rows.isNotEmpty() && used + extra > maxBytes) break
        rows += flattened.row
        used += extra
        cellsTruncated = cellsTruncated || flattened.truncated
        if (used >= maxBytes) break
    }
    val consumed = start + rows.size
    val more = consumed < result.rowCount
    return ResultSlice(
        rows = rows,
        moreRowsRemain = more,
        cellsTruncated = cellsTruncated,
        nextOffset = if (more) consumed else null,
    )
}

private data class FlattenedRow(val row: JsonObject, val truncated: Boolean)

private fun flattenRowInternal(
    columnNames: List<String>,
    row: List<ResultCell>,
    clip: Boolean,
): FlattenedRow {
    var truncated = false
    val json = buildJsonObject {
        val used = mutableSetOf<String>()
        columnNames.forEachIndexed { index, original ->
            var key = original
            var n = 2
            while (!used.add(key)) {
                key = "${original}_$n"
                n++
            }
            put(
                key,
                flattenCellInternal(row.getOrElse(index) { ResultCell.Null }, clip) {
                    truncated = true
                },
            )
        }
    }
    return FlattenedRow(json, truncated)
}

private fun flattenCellInternal(
    cell: ResultCell,
    clip: Boolean,
    onTruncate: () -> Unit = {},
): JsonElement =
    when (cell) {
        ResultCell.Null -> JsonNull
        is ResultCell.BoolCell -> JsonPrimitive(cell.value)
        is ResultCell.IntegerCell -> JsonPrimitive(cell.value)
        is ResultCell.FloatCell -> flattenFloat(cell.value)
        is ResultCell.TextCell -> JsonPrimitive(maybeClip(cell.value.text, clip, onTruncate))
        is ResultCell.BinaryCell -> JsonPrimitive(maybeClip(cell.value.base64, clip, onTruncate))
    }

private fun maybeClip(value: String, clip: Boolean, onTruncate: () -> Unit): String =
    if (clip) clipForMcp(value, onTruncate) else value

private fun clipForMcp(value: String, onTruncate: () -> Unit): String {
    val bytes = value.toByteArray(Charsets.UTF_8)
    if (bytes.size <= MCP_CELL_MAX_BYTES) return value
    onTruncate()
    var end = 0
    var used = 0
    while (end < value.length) {
        val codePoint = value.codePointAt(end)
        val chars = Character.charCount(codePoint)
        val width = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8).size
        if (used + width > MCP_CELL_MAX_BYTES) break
        used += width
        end += chars
    }
    return value.substring(0, end) + MCP_CELL_TRUNCATION_MARKER
}

private fun flattenFloat(value: Double): JsonPrimitive =
    when {
        value.isNaN() -> JsonPrimitive("NaN")
        value == Double.POSITIVE_INFINITY -> JsonPrimitive("Infinity")
        value == Double.NEGATIVE_INFINITY -> JsonPrimitive("-Infinity")
        else -> JsonPrimitive(value)
    }
