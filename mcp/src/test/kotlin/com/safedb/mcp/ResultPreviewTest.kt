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
        val longText = "x".repeat(2_048)
        assertEquals(JsonPrimitive(longText), flattenCell(ResultCell.text(longText)))
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
        assertEquals(PREVIEW_ROW_LIMIT, preview.rows.size)
        assertEquals("1", preview.rows.first().getValue("id").jsonPrimitive.content)
        assertEquals("r1", preview.rows.first().getValue("email").jsonPrimitive.content)
        assertEquals("10", preview.rows.last().getValue("id").jsonPrimitive.content)
        assertTrue(preview.moreRowsRemain)
        assertEquals(10, preview.nextOffset)
        assertTrue(preview.rows.none { "kind" in it.toString() })
    }

    @Test
    fun flattenRowKeepsDuplicateColumnLabels() {
        val unique =
            flattenRow(
                listOf("id", "email"),
                listOf(ResultCell.integer(1), ResultCell.text("a@b.com")),
            )
        assertEquals("1", unique.getValue("id").jsonPrimitive.content)
        assertEquals("a@b.com", unique.getValue("email").jsonPrimitive.content)
        assertEquals(2, unique.size)

        val duplicates =
            flattenRow(
                listOf("id", "id"),
                listOf(ResultCell.integer(1), ResultCell.integer(2)),
            )
        assertEquals("1", duplicates.getValue("id").jsonPrimitive.content)
        assertEquals("2", duplicates.getValue("id_2").jsonPrimitive.content)
        assertEquals(2, duplicates.size)

        val collisionWithExisting =
            flattenRow(
                listOf("id", "id_2", "id"),
                listOf(ResultCell.integer(1), ResultCell.integer(2), ResultCell.integer(3)),
            )
        assertEquals("1", collisionWithExisting.getValue("id").jsonPrimitive.content)
        assertEquals("2", collisionWithExisting.getValue("id_2").jsonPrimitive.content)
        assertEquals("3", collisionWithExisting.getValue("id_3").jsonPrimitive.content)
        assertEquals(3, collisionWithExisting.size)
    }

    @Test
    fun flattenRowsPagesByOffsetAndLimit() {
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
        val page = flattenRows(result, offset = 10, limit = 3)
        assertEquals(3, page.size)
        assertEquals("11", page.first().getValue("id").jsonPrimitive.content)
        assertEquals("r11", page.first().getValue("email").jsonPrimitive.content)
        assertEquals("13", page.last().getValue("id").jsonPrimitive.content)
        val fromStart = flattenRows(result, offset = -4, limit = 2)
        assertEquals("1", fromStart.first().getValue("id").jsonPrimitive.content)
        assertEquals("2", fromStart.last().getValue("id").jsonPrimitive.content)
    }

    @Test
    fun sliceRowsStopsAtTheByteBudgetAndTruncatesLongCells() {
        val columns =
            (1..24).map { index -> ResultColumn("c${index.toString().padStart(2, '0')}", "text") }
        val payload = "x".repeat(2_048)
        val result =
            QueryResult(
                columns = columns,
                rows = List(50) { columns.map { ResultCell.text(payload) } },
                rowCount = 50,
                truncated = false,
                warnings = emptyList(),
            )
        val page = sliceRows(result, offset = 0, limit = GET_RESULT_ROWS_MAX, MCP_PAYLOAD_MAX_BYTES)
        assertTrue(page.rows.size < 50)
        assertTrue(page.rows.isNotEmpty())
        assertTrue(page.moreRowsRemain)
        assertEquals(page.rows.size, page.nextOffset)
        val encoded = page.rows.toString().toByteArray(Charsets.UTF_8).size
        assertTrue(encoded <= MCP_PAYLOAD_MAX_BYTES + 8 * 1024)
        val cell = page.rows.first().getValue("c01").jsonPrimitive.content
        assertTrue(cell.endsWith(MCP_CELL_TRUNCATION_MARKER))
        assertTrue(page.cellsTruncated)

        val preview = previewRows(result)
        assertTrue(preview.cellsTruncated)
        assertTrue(preview.rows.size < PREVIEW_ROW_LIMIT)
        assertTrue(
            preview.rows
                .first()
                .getValue("c01")
                .jsonPrimitive
                .content
                .endsWith(MCP_CELL_TRUNCATION_MARKER),
        )
    }
}
