package com.safedb.ui

import com.safedb.model.QueryResult
import com.safedb.model.ResultCell
import com.safedb.model.ResultColumn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResultsTableLayoutTest {
    @Test
    fun buildsStableColumnLayoutsFromResultColumns() {
        val result =
            QueryResult(
                columns =
                    listOf(
                        ResultColumn("t0__id", "bigint"),
                        ResultColumn("t0__customer_name", "varchar"),
                        ResultColumn("plain_total", "decimal"),
                    ),
                rows =
                    listOf(
                        listOf(
                            ResultCell.IntegerCell(1),
                            ResultCell.text("Ada Lovelace"),
                            ResultCell.FloatCell(42.5),
                        ),
                        listOf(ResultCell.IntegerCell(200), ResultCell.text("Grace Hopper")),
                    ),
                rowCount = 2,
                truncated = false,
                warnings = emptyList(),
            )

        val columns = buildResultTableColumns(result)

        assertEquals(listOf(0, 1, 2), columns.map { it.index })
        assertEquals(listOf("id", "customer_name", "plain_total"), columns.map { it.label })
        assertEquals(ResultTableCellAlignment.End, columns[0].alignment)
        assertEquals(ResultTableCellAlignment.Start, columns[1].alignment)
        assertEquals(ResultTableCellAlignment.End, columns[2].alignment)
    }

    @Test
    fun clampsColumnWidthsToReadableBounds() {
        val narrow = resultColumnWidthDp("id", listOf(ResultCell.IntegerCell(1)))
        val wide = resultColumnWidthDp("description", listOf(ResultCell.text("x".repeat(500))))

        assertEquals(72, narrow)
        assertEquals(280, wide)
    }

    @Test
    fun formatsMissingAndNullCellsWithoutShiftingRows() {
        assertEquals("", formatCell(null))
        assertEquals("", formatCell(ResultCell.Null))
        assertEquals("true", formatCell(ResultCell.BoolCell(true)))
        assertEquals("12", formatCell(ResultCell.IntegerCell(12)))
        assertEquals("2.5", formatCell(ResultCell.FloatCell(2.5)))
        assertEquals("pending", formatCell(ResultCell.text("pending")))
    }

    @Test
    fun classifiesMixedOrEmptyColumnsAsLeftAligned() {
        assertEquals(
            ResultTableCellAlignment.Start,
            resultColumnAlignment(listOf(ResultCell.IntegerCell(1), ResultCell.text("pending"))),
        )
        assertEquals(
            ResultTableCellAlignment.Start,
            resultColumnAlignment(listOf(ResultCell.Null, null)),
        )
    }

    @Test
    fun classifiesNumericAndBooleanColumnsAsRightAligned() {
        assertEquals(
            ResultTableCellAlignment.End,
            resultColumnAlignment(listOf(ResultCell.IntegerCell(1), ResultCell.FloatCell(2.5))),
        )
        assertEquals(
            ResultTableCellAlignment.End,
            resultColumnAlignment(listOf(ResultCell.BoolCell(true), ResultCell.BoolCell(false))),
        )
    }

    @Test
    fun stripsOnlyBuilderGeneratedColumnPrefixes() {
        assertEquals("id", displayColumnName(ResultColumn("t0__id", "bigint")))
        assertEquals("total", displayColumnName(ResultColumn("t12__total", "decimal")))
        assertEquals("customer__id", displayColumnName(ResultColumn("customer__id", "varchar")))
        assertTrue(displayColumnName(ResultColumn("t__id", "varchar")).startsWith("t__"))
    }
}
