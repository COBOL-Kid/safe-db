package com.safedb.adapter

import com.safedb.model.ColumnCategory
import com.safedb.model.ColumnInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SchemaMetadataTest {
    @Test
    fun assemblerPreservesCompositeIndexAndForeignKeyOrder() {
        val child = MetadataTableKey("public", "child")
        val schema = assembleSchema(
            tables = listOf(child),
            columns = listOf(
                MetadataColumn(child, ColumnInfo("a", "int", false)),
                MetadataColumn(child, ColumnInfo("b", "int", false)),
            ),
            indexes = listOf(
                MetadataIndex(child, "child_pk", "a", "btree", true, true, true),
                MetadataIndex(child, "child_pk", "b", "btree", true, true, true),
            ),
            foreignKeys = listOf(
                MetadataForeignKey(child, "child_parent_fk", "a", "public", "parent", "x"),
                MetadataForeignKey(child, "child_parent_fk", "b", "public", "parent", "y"),
            ),
        )

        val table = schema.tables.single()
        assertEquals(listOf("a", "b"), table.indexes.single().columns)
        assertEquals(listOf("a", "b"), table.foreignKeys.single().columns)
        assertEquals(listOf("x", "y"), table.foreignKeys.single().referencedColumns)
        assertTrue(table.columns.all { it.isIndexed && it.category == ColumnCategory.Integer })
    }
}
