package com.safedb.explore

import com.safedb.model.ResultCell
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorksheetColumnLayoutTest {
    @Test
    fun resolvesCustomLayoutAndMovesColumnsWithoutLosingSourceIndexes() {
        val columns = listOf(
            WorksheetDisplayColumn("column:name", "Name", "varchar", sourceColumn = "name"),
            WorksheetDisplayColumn("column:amount", "Amount", "decimal", sourceColumn = "amount"),
            WorksheetDisplayColumn("calculation:double", "Double", "calculated", calculationId = "double"),
        )

        val natural = resolveWorksheetColumnLayout(columns, emptyList())
        assertEquals(listOf("Name", "Amount", "Double"), natural.map { it.column.label })
        assertEquals(listOf(0, 1, 2), natural.map { it.sourceIndex })
        assertTrue(natural.all { it.visible })

        val configured = resolveWorksheetColumnLayout(
            columns,
            listOf(
                WorksheetColumnLayout(WorksheetValueRef.Calculation("double")),
                WorksheetColumnLayout(WorksheetValueRef.Column("missing"), visible = false),
                WorksheetColumnLayout(WorksheetValueRef.Column("name"), visible = false),
                WorksheetColumnLayout(WorksheetValueRef.Calculation("double"), visible = false),
            ),
        )
        assertEquals(listOf("Double", "Name", "Amount"), configured.map { it.column.label })
        assertEquals(listOf(2, 0, 1), configured.map { it.sourceIndex })
        assertTrue(configured[0].visible, "The first duplicate layout entry controls visibility")
        assertFalse(configured[1].visible)
        assertTrue(configured[2].visible, "Columns missing from a saved layout append visibly")

        val moved = moveWorksheetColumn(configured.toWorksheetColumnLayout(), fromIndex = 2, toIndex = 0)
        assertEquals(
            listOf(WorksheetValueRef.Column("amount"), WorksheetValueRef.Calculation("double"), WorksheetValueRef.Column("name")),
            moved.map { it.ref },
        )
        assertFalse(moved.last().visible)
    }

    @Test
    fun projectsGroupLabelsWithoutReplacingReorderedCalculationCells() {
        val region = WorksheetDisplayColumn("column:region", "Region", "varchar", sourceColumn = "region")
        val revenue = WorksheetDisplayColumn("calculation:revenue", "Revenue", "calculated", calculationId = "revenue")
        val preview = WorksheetPreview(
            columns = listOf(region, revenue),
            rows = listOf(
                WorksheetDisplayRow(
                    kind = WorksheetRowKind.Group,
                    depth = 1,
                    pathKey = "east/january",
                    label = "month: January",
                    cells = listOf(WorksheetCell(), WorksheetCell(ResultCell.FloatCell(30.0))),
                ),
                WorksheetDisplayRow(
                    kind = WorksheetRowKind.GrandTotal,
                    depth = 0,
                    pathKey = "__grand_total__",
                    label = "Grand total",
                    cells = listOf(WorksheetCell(), WorksheetCell(ResultCell.FloatCell(50.0))),
                ),
            ),
        )

        val projection = projectWorksheetTable(
            preview,
            listOf(
                WorksheetColumnLayout(WorksheetValueRef.Calculation("revenue")),
                WorksheetColumnLayout(WorksheetValueRef.Column("region"), visible = false),
            ),
        )

        assertTrue(projection.hasRowLabels)
        assertEquals(listOf("Revenue"), projection.columns.map { it.label })
        assertEquals(listOf("month: January", "Grand total"), projection.rows.map { it.rowLabel })
        assertEquals(
            listOf(30.0, 50.0),
            projection.rows.map { (it.cells.single().value as ResultCell.FloatCell).value },
        )
    }
}
