package com.safedb.explore

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
        assertFalse(configured[1].visible)
        assertTrue(configured[2].visible, "Columns missing from a saved layout append visibly")

        val moved = moveWorksheetColumn(configured.toWorksheetColumnLayout(), fromIndex = 2, toIndex = 0)
        assertEquals(
            listOf(WorksheetValueRef.Column("amount"), WorksheetValueRef.Calculation("double"), WorksheetValueRef.Column("name")),
            moved.map { it.ref },
        )
        assertFalse(moved.last().visible)
    }
}
