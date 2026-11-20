package com.safedb.explore

import com.safedb.model.QueryResult
import com.safedb.model.ResultCell
import com.safedb.model.ResultColumn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExploreSampleSupportTest {
    @Test
    fun sampleIndexHandlesMissingColumnsShortRowsAndNullCells() {
        val sample =
            QueryResult(
                columns = listOf(ResultColumn("status", "varchar"), ResultColumn("amount", "int")),
                rows =
                    listOf(
                        listOf(ResultCell.text("pending"), ResultCell.IntegerCell(10)),
                        listOf(ResultCell.Null),
                    ),
                rowCount = 2,
                truncated = false,
                warnings = emptyList(),
            )
        val index = SampleIndex(sample)

        assertTrue("status" in index)
        assertFalse("missing" in index)
        assertEquals(0, index.columnIndex("status"))
        assertNull(index.columnIndex("missing"))

        assertEquals(ResultCell.text("pending"), index.cell(sample.rows[0], "status"))
        assertEquals(ResultCell.Null, index.cell(sample.rows[1], "status"))
        assertEquals(ResultCell.Null, index.cell(sample.rows[1], "amount"))
        assertNull(index.cellOrNull(sample.rows[1], "amount"))
        assertEquals(ResultCell.Null, index.cell(sample.rows[0], "missing"))
        assertNull(index.cellOrNull(sample.rows[0], "missing"))
    }

    @Test
    fun pivotFilterHelpersHandleNullCellsMissingColumnsAndCaseInsensitiveOps() {
        val sample =
            QueryResult(
                columns =
                    listOf(ResultColumn("region", "varchar"), ResultColumn("note", "varchar")),
                rows =
                    listOf(
                        listOf(ResultCell.text("NorthEast"), ResultCell.text("urgent")),
                        listOf(ResultCell.Null, ResultCell.text("normal")),
                    ),
                rowCount = 2,
                truncated = false,
                warnings = emptyList(),
            )
        val index = SampleIndex(sample)

        assertTrue(matchesMembers(ResultCell.text("NorthEast"), emptySet()))
        assertTrue(matchesMembers(null, setOf("<null>")))
        assertFalse(matchesMembers(null, setOf("NorthEast")))

        assertTrue(matchesLabelOp("NorthEast", LabelFilterOp.Equals, "northeast"))
        assertTrue(matchesLabelOp("NorthEast", LabelFilterOp.StartsWith, "north"))
        assertTrue(matchesLabelOp("NorthEast", LabelFilterOp.EndsWith, "EAST"))
        assertTrue(matchesLabelOp("NorthEast", LabelFilterOp.Contains, "the"))
        assertFalse(matchesLabelOp("", LabelFilterOp.Contains, "east"))

        val filters =
            listOf(
                PivotFilter.Label("f1", "region", "Region", LabelFilterOp.StartsWith, "north"),
                PivotFilter.Members("f2", "missing", "Missing", setOf("<null>")),
                PivotFilter.Label("f3", "missing", "Missing", LabelFilterOp.Equals, ""),
            )
        assertTrue(passesPivotFilters(sample.rows[0], filters, index))
        assertFalse(passesPivotFilters(sample.rows[1], filters, index))
    }
}
