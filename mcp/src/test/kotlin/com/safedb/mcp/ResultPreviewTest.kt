package com.safedb.mcp

import com.safedb.model.QueryResult
import com.safedb.model.ResultCell
import com.safedb.model.ResultColumn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

class ResultPreviewTest {
    @Test
    fun flattenCellMapsToJsonPrimitives() {
        assertEquals(JsonNull, flattenCell(ResultCell.Null))
        assertEquals(JsonPrimitive(true), flattenCell(ResultCell.bool(true)))
        assertEquals(JsonPrimitive(12L), flattenCell(ResultCell.integer(12)))
        assertEquals(JsonPrimitive(2.5), flattenCell(ResultCell.float(2.5)))
        assertEquals(JsonPrimitive("NaN"), flattenCell(ResultCell.float(Double.NaN)))
        assertEquals(
            JsonPrimitive("Infinity"),
            flattenCell(ResultCell.float(Double.POSITIVE_INFINITY)),
        )
        assertEquals(
            JsonPrimitive("-Infinity"),
            flattenCell(ResultCell.float(Double.NEGATIVE_INFINITY)),
        )
        assertEquals(JsonPrimitive("pending"), flattenCell(ResultCell.text("pending")))
        val binary = ResultCell.binary("hi".toByteArray()) as ResultCell.BinaryCell
        assertEquals(JsonPrimitive(binary.value.base64), flattenCell(binary))
    }

    @Test
    fun previewRowsCapsAndKeysByColumnName() {
        val rows =
            (1..15).map { index ->
                listOf(ResultCell.integer(index.toLong()), ResultCell.text("r$index"))
            }
        val result =
            QueryResult(
                columns = listOf(ResultColumn("id", "int"), ResultColumn("email", "text")),
                rows = rows,
                rowCount = 15,
                truncated = false,
                warnings = emptyList(),
            )
        val preview = previewRows(result)
        assertEquals(PREVIEW_ROW_LIMIT, preview.size)
        assertEquals("1", preview.first().getValue("id").jsonPrimitive.content)
        assertEquals("r1", preview.first().getValue("email").jsonPrimitive.content)
        assertEquals("10", preview.last().getValue("id").jsonPrimitive.content)
        assertTrue(preview.none { "kind" in it.toString() })
    }
}
