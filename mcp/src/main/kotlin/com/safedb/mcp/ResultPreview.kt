package com.safedb.mcp

import com.safedb.model.QueryResult
import com.safedb.model.ResultCell
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

internal const val PREVIEW_ROW_LIMIT = 10

internal fun flattenCell(cell: ResultCell): JsonElement =
    when (cell) {
        ResultCell.Null -> JsonNull
        is ResultCell.BoolCell -> JsonPrimitive(cell.value)
        is ResultCell.IntegerCell -> JsonPrimitive(cell.value)
        is ResultCell.FloatCell -> flattenFloat(cell.value)
        is ResultCell.TextCell -> JsonPrimitive(cell.value.text)
        is ResultCell.BinaryCell -> JsonPrimitive(cell.value.base64)
    }

internal fun previewRows(result: QueryResult, limit: Int = PREVIEW_ROW_LIMIT): List<JsonObject> {
    val names = result.columns.map { it.name }
    return result.rows.take(limit).map { row ->
        buildJsonObject {
            names.forEachIndexed { index, name ->
                put(name, flattenCell(row.getOrElse(index) { ResultCell.Null }))
            }
        }
    }
}

private fun flattenFloat(value: Double): JsonPrimitive =
    when {
        value.isNaN() -> JsonPrimitive("NaN")
        value == Double.POSITIVE_INFINITY -> JsonPrimitive("Infinity")
        value == Double.NEGATIVE_INFINITY -> JsonPrimitive("-Infinity")
        else -> JsonPrimitive(value)
    }
