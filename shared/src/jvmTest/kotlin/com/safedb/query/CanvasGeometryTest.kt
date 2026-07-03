package com.safedb.query

import com.safedb.model.ColumnInfo
import com.safedb.model.ForeignKeyInfo
import com.safedb.model.IndexInfo
import com.safedb.model.JoinSpec
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

    @Test
    fun suggestedRelationshipsRequireBothTablesOnCanvas() {
        val orders = canvasTable(
            alias = "t0",
            name = "orders",
            foreignKeys = listOf(
                ForeignKeyInfo(
                    name = "orders_customer_id_fkey",
                    columns = listOf("customer_id"),
                    referencedSchema = "public",
                    referencedTable = "customers",
                    referencedColumns = listOf("id"),
                ),
            ),
        )
        val customers = canvasTable(
            alias = "t1",
            name = "customers",
            indexes = listOf(IndexInfo("customers_pkey", listOf("id"), isPrimary = true, isUnique = true)),
        )

        assertEquals(emptyList(), suggestedRelationships(listOf(orders), emptyList()))

        assertEquals(
            listOf(
                SuggestedRelationship(
                    name = "orders_customer_id_fkey",
                    foreignAlias = "t0",
                    foreignColumn = "customer_id",
                    referencedAlias = "t1",
                    referencedColumn = "id",
                ),
            ),
            suggestedRelationships(listOf(orders, customers), emptyList()),
        )
    }

    @Test
    fun suggestedRelationshipsSkipExistingUserJoin() {
        val orders = canvasTable(
            alias = "t0",
            name = "orders",
            foreignKeys = listOf(
                ForeignKeyInfo(
                    name = "orders_customer_id_fkey",
                    columns = listOf("customer_id"),
                    referencedSchema = "public",
                    referencedTable = "customers",
                    referencedColumns = listOf("id"),
                ),
            ),
        )
        val customers = canvasTable(
            alias = "t1",
            name = "customers",
            indexes = listOf(IndexInfo("customers_pkey", listOf("id"), isPrimary = true, isUnique = true)),
        )

        val joins = listOf(
            JoinSpec(leftAlias = "t1", leftColumn = "id", rightAlias = "t0", rightColumn = "customer_id"),
        )

        assertEquals(emptyList(), suggestedRelationships(listOf(orders, customers), joins))
    }

    @Test
    fun suggestedRelationshipsExpandCompositeForeignKeys() {
        val items = canvasTable(
            alias = "t0",
            name = "line_items",
            columns = listOf(
                ColumnInfo("order_id", "bigint", nullable = false, isIndexed = true),
                ColumnInfo("store_id", "bigint", nullable = false, isIndexed = true),
            ),
            foreignKeys = listOf(
                ForeignKeyInfo(
                    name = "line_items_order_fkey",
                    columns = listOf("order_id", "store_id"),
                    referencedSchema = "public",
                    referencedTable = "orders",
                    referencedColumns = listOf("id", "store_id"),
                ),
            ),
        )
        val orders = canvasTable(
            alias = "t1",
            name = "orders",
            columns = listOf(
                ColumnInfo("id", "bigint", nullable = false, isIndexed = true),
                ColumnInfo("store_id", "bigint", nullable = false, isIndexed = true),
            ),
            indexes = listOf(IndexInfo("orders_unique", listOf("id", "store_id"), isUnique = true)),
        )

        assertEquals(
            listOf(
                SuggestedRelationship("line_items_order_fkey", "t0", "order_id", "t1", "id"),
                SuggestedRelationship("line_items_order_fkey", "t0", "store_id", "t1", "store_id"),
            ),
            suggestedRelationships(listOf(items, orders), emptyList()),
        )
    }

    @Test
    fun suggestedRelationshipsIgnoreStaleColumnsAndNonUniqueTargets() {
        val orders = canvasTable(
            alias = "t0",
            name = "orders",
            foreignKeys = listOf(
                ForeignKeyInfo(
                    name = "orders_customer_id_fkey",
                    columns = listOf("missing_customer_id"),
                    referencedSchema = "public",
                    referencedTable = "customers",
                    referencedColumns = listOf("id"),
                ),
                ForeignKeyInfo(
                    name = "orders_sales_rep_fkey",
                    columns = listOf("customer_id"),
                    referencedSchema = "public",
                    referencedTable = "sales_reps",
                    referencedColumns = listOf("id"),
                ),
            ),
        )
        val customers = canvasTable(alias = "t1", name = "customers")
        val salesReps = canvasTable(
            alias = "t2",
            name = "sales_reps",
            indexes = listOf(IndexInfo("sales_reps_id_idx", listOf("id"), isUnique = false)),
        )

        assertEquals(emptyList(), suggestedRelationships(listOf(orders, customers, salesReps), emptyList()))
    }
}

private fun canvasTable(
    alias: String = "t0",
    name: String = "users",
    x: Float = 0f,
    y: Float = 0f,
    width: Float = CANVAS_CARD_WIDTH,
    height: Float = CANVAS_CARD_HEIGHT,
    columns: List<ColumnInfo> = listOf(
        ColumnInfo("id", "int", nullable = false, isIndexed = true),
        ColumnInfo("email", "varchar", nullable = true, isIndexed = false),
        ColumnInfo("org_id", "int", nullable = false, isIndexed = true),
        ColumnInfo("customer_id", "int", nullable = false, isIndexed = true),
    ),
    indexes: List<IndexInfo> = emptyList(),
    foreignKeys: List<ForeignKeyInfo> = emptyList(),
) = CanvasTableLike(
    alias = alias,
    x = x,
    y = y,
    width = width,
    height = height,
    tableInfo = TableInfo(
        schema = "public",
        name = name,
        columns = columns,
        indexes = indexes,
        foreignKeys = foreignKeys,
    ),
)
