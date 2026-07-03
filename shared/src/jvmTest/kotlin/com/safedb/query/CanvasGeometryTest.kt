package com.safedb.query

import com.safedb.model.ColumnInfo
import com.safedb.model.TableInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CanvasGeometryTest {
    @Test
    fun columnHitBoundsCoversOnlyTheNamedColumnRow() {
        val table = canvasTable(x = 40f, y = 80f)
        val bounds = assertNotNull(columnHitBounds(table, "email"))

        assertEquals("t0", bounds.alias)
        assertEquals("email", bounds.column)
        assertTrue(bounds.contains(60f, 80f + CANVAS_HEADER_HEIGHT + CANVAS_ROW_HEIGHT + 4f))
        assertFalse(bounds.contains(60f, 80f + CANVAS_HEADER_HEIGHT - 1f))
        assertFalse(bounds.contains(60f, 80f + CANVAS_HEADER_HEIGHT + (CANVAS_ROW_HEIGHT * 2) + 2f))
    }

    @Test
    fun indexedJoinTargetAtReturnsEligibleIndexedColumnOnly() {
        val left = canvasTable(alias = "t0", x = 40f, y = 40f)
        val right = canvasTable(alias = "t1", x = 340f, y = 40f)

        val idY = columnY(right, "id")
        val emailY = columnY(right, "email")

        assertEquals(
            "t1" to "id",
            indexedJoinTargetAt(listOf(left, right), x = 360f, y = idY, sourceAlias = "t0", sourceColumn = "id"),
        )
        assertNull(
            indexedJoinTargetAt(listOf(left, right), x = 360f, y = emailY, sourceAlias = "t0", sourceColumn = "id"),
        )
        assertNull(
            indexedJoinTargetAt(listOf(left, right), x = 60f, y = columnY(left, "id"), sourceAlias = "t0", sourceColumn = "id"),
        )
    }

    @Test
    fun joinEdgePointsHonorResizedTableWidth() {
        val left = canvasTable(x = 10f, y = 20f, width = 360f)
        val right = canvasTable(alias = "t1", x = 500f, y = 80f)

        val points = joinEdgePoints(left, "id", right, "id")

        assertEquals(370f, points.sourceX)
        assertEquals(500f, points.targetX)
        assertEquals(columnY(left, "id"), points.sourceY)
        assertEquals(columnY(right, "id"), points.targetY)
    }

    @Test
    fun clampDimensionRejectsInvalidAndClampsBounds() {
        assertEquals(MIN_TABLE_WIDTH, clampDimension(Float.NaN, MIN_TABLE_WIDTH, MAX_TABLE_WIDTH))
        assertEquals(MIN_TABLE_WIDTH, clampDimension(-100f, MIN_TABLE_WIDTH, MAX_TABLE_WIDTH))
        assertEquals(MAX_TABLE_WIDTH, clampDimension(9999f, MIN_TABLE_WIDTH, MAX_TABLE_WIDTH))
        assertEquals(244f, clampDimension(244.4f, MIN_TABLE_WIDTH, MAX_TABLE_WIDTH))
    }
}

private fun canvasTable(
    alias: String = "t0",
    x: Float = 0f,
    y: Float = 0f,
    width: Float = CANVAS_CARD_WIDTH,
    height: Float = CANVAS_CARD_HEIGHT,
) = CanvasTableLike(
    alias = alias,
    x = x,
    y = y,
    width = width,
    height = height,
    tableInfo = TableInfo(
        schema = "public",
        name = "users",
        columns = listOf(
            ColumnInfo("id", "int", nullable = false, isIndexed = true),
            ColumnInfo("email", "varchar", nullable = true, isIndexed = false),
            ColumnInfo("org_id", "int", nullable = false, isIndexed = true),
        ),
        indexes = emptyList(),
    ),
)
