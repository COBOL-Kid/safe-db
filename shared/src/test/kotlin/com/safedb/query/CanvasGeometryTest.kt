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
        assertTrue(bounds.contains(60f, 80f + CANVAS_HEADER_HEIGHT + CANVAS_FIELD_BODY_PADDING_Y + CANVAS_ROW_HEIGHT + 4f))
        assertFalse(bounds.contains(60f, 80f + CANVAS_HEADER_HEIGHT + CANVAS_FIELD_BODY_PADDING_Y - 1f))
        assertFalse(bounds.contains(60f, 80f + CANVAS_HEADER_HEIGHT + CANVAS_FIELD_BODY_PADDING_Y + (CANVAS_ROW_HEIGHT * 2) + 2f))
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
    fun indexedJoinTargetAtUsesScrolledVisibleColumnBounds() {
        val left = canvasTable(alias = "t0", x = 40f, y = 40f)
        val right = canvasTable(
            alias = "t1",
            x = 340f,
            y = 40f,
            height = 150f,
            fieldScrollOffset = CANVAS_ROW_HEIGHT * 4,
            columns = (0 until 10).map { ColumnInfo("col_$it", "int", nullable = true, isIndexed = true) },
        )

        val visibleCol4Y = right.y + CANVAS_HEADER_HEIGHT + CANVAS_FIELD_BODY_PADDING_Y + CANVAS_ROW_HEIGHT / 2f
        assertEquals(
            "t1" to "col_4",
            indexedJoinTargetAt(listOf(left, right), x = 360f, y = visibleCol4Y, sourceAlias = "t0", sourceColumn = "id"),
        )

        val unscrolledCol4Y = columnY(right.copy(fieldScrollOffset = 0f), "col_4")
        assertNull(
            indexedJoinTargetAt(listOf(left, right), x = 360f, y = unscrolledCol4Y, sourceAlias = "t0", sourceColumn = "id"),
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
    fun columnJoinPortAlignsToVisibleColumnRowAndSideEdge() {
        val table = canvasTable(x = 40f, y = 80f, width = 260f)

        val port = assertNotNull(columnJoinPort(table, "org_id", JoinPortSide.Right))

        assertEquals(300f, port.point.x)
        assertEquals(columnY(table, "org_id"), port.point.y)
        assertEquals(JoinPortSide.Right, port.side)
        assertEquals(JoinPortVisibility.Visible, port.visibility)
    }

    @Test
    fun columnJoinPortScalesToRenderedCardSizeOnHighDensityDisplays() {
        val table = canvasTable(x = 40f, y = 30f, width = 448f, height = 448f, layoutScale = 2f)

        val port = assertNotNull(columnJoinPort(table, "id", JoinPortSide.Right))

        assertEquals(40f + 448f, port.point.x)
        assertEquals(30f + CANVAS_HEADER_HEIGHT * 2f + CANVAS_FIELD_BODY_PADDING_Y * 2f + CANVAS_ROW_HEIGHT, port.point.y)
        assertEquals(40f + 448f, tableRightX(table))
    }

    @Test
    fun columnJoinPortClampsHiddenRowsToFieldViewport() {
        val table = canvasTable(
            x = 20f,
            y = 30f,
            height = 150f,
            columns = (0 until 10).map { ColumnInfo("col_$it", "int", nullable = true, isIndexed = true) },
        )

        val below = assertNotNull(columnJoinPort(table, "col_9", JoinPortSide.Left))
        val scrolled = assertNotNull(columnJoinPort(table.copy(fieldScrollOffset = CANVAS_ROW_HEIGHT * 4), "col_0", JoinPortSide.Left))

        assertEquals(JoinPortVisibility.HiddenBelow, below.visibility)
        assertEquals(table.y + table.height!! - CANVAS_RESIZE_FOOTER_HEIGHT - CANVAS_FIELD_BODY_PADDING_Y - 8f, below.point.y)
        assertEquals(JoinPortVisibility.HiddenAbove, scrolled.visibility)
        assertEquals(table.y + CANVAS_HEADER_HEIGHT + CANVAS_FIELD_BODY_PADDING_Y + 8f, scrolled.point.y)
    }

    @Test
    fun routeJoinEdgeUsesColumnPortsAndAvoidsBlockingTable() {
        val source = canvasTable(alias = "t0", x = 20f, y = 80f, width = 220f)
        val target = canvasTable(alias = "t1", x = 620f, y = 80f, width = 220f)
        val blocker = canvasTable(alias = "t2", x = 330f, y = 30f, width = 180f, height = 260f)

        val edge = assertNotNull(routeJoinEdge(source, "id", target, "customer_id", listOf(source, target, blocker)))

        assertEquals(tableRightX(source), edge.sourcePort.point.x)
        assertEquals(tableRightX(target), edge.targetPort.point.x)
        assertEquals(tableRightX(source), edge.points.first().x)
        assertEquals(tableRightX(source) + JOIN_PORT_EXIT, edge.points[1].x)
        assertEquals(tableRightX(target) + JOIN_PORT_EXIT, edge.points[edge.points.lastIndex - 1].x)
        assertEquals(tableRightX(target), edge.points.last().x)
        assertTrue(edge.points.size >= 4)
        assertFalse(edgeIntersects(edge.points, tableBounds(blocker).expanded(JOIN_ROUTE_MARGIN)))
    }

    @Test
    fun routeJoinEdgeUsesRightSideEdgesForRightToLeftTables() {
        val source = canvasTable(alias = "t0", x = 620f, y = 80f, width = 220f)
        val target = canvasTable(alias = "t1", x = 20f, y = 80f, width = 220f)

        val edge = assertNotNull(routeJoinEdge(source, "id", target, "customer_id", listOf(source, target)))

        assertEquals(JoinPortSide.Right, edge.sourcePort.side)
        assertEquals(JoinPortSide.Right, edge.targetPort.side)
        assertEquals(tableRightX(source), edge.sourcePort.point.x)
        assertEquals(tableRightX(target), edge.targetPort.point.x)
        assertEquals(tableRightX(source), edge.points.first().x)
        assertEquals(tableRightX(source) + JOIN_PORT_EXIT, edge.points[1].x)
        assertEquals(tableRightX(target) + JOIN_PORT_EXIT, edge.points[edge.points.lastIndex - 1].x)
        assertEquals(tableRightX(target), edge.points.last().x)
    }

    @Test
    fun routeJoinEdgeHandlesVerticallyStackedTables() {
        val source = canvasTable(alias = "t0", x = 120f, y = 40f)
        val target = canvasTable(alias = "t1", x = 120f, y = 420f)

        val edge = assertNotNull(routeJoinEdge(source, "id", target, "customer_id", listOf(source, target)))

        assertEquals(columnY(source, "id"), edge.sourcePort.point.y)
        assertEquals(columnY(target, "customer_id"), edge.targetPort.point.y)
        assertTrue(edge.points.size >= 4)
    }

    @Test
    fun routeJoinEdgeAppliesLaneOffsetsForParallelEdges() {
        val source = canvasTable(alias = "t0", x = 20f, y = 80f, width = 220f)
        val target = canvasTable(alias = "t1", x = 620f, y = 80f, width = 220f)

        val first = assertNotNull(routeJoinEdge(source, "id", target, "customer_id", listOf(source, target), laneIndex = 0))
        val second = assertNotNull(routeJoinEdge(source, "org_id", target, "id", listOf(source, target), laneIndex = 1))

        assertEquals(tableRightX(source) + JOIN_PORT_EXIT, first.points[1].x)
        assertEquals(tableRightX(source) + JOIN_PORT_EXIT + JOIN_ROUTE_LANE_STEP, second.points[1].x)
    }

    @Test
    fun pathHitTestingMatchesHorizontalAndVerticalSegmentsWithinTolerance() {
        val points = listOf(
            CanvasPoint(10f, 20f),
            CanvasPoint(90f, 20f),
            CanvasPoint(90f, 80f),
        )

        assertTrue(pathContainsPoint(points, CanvasPoint(55f, 25f), tolerance = 6f))
        assertTrue(pathContainsPoint(points, CanvasPoint(84f, 60f), tolerance = 6f))
        assertFalse(pathContainsPoint(points, CanvasPoint(55f, 30.5f), tolerance = 6f))
        assertFalse(pathContainsPoint(points, CanvasPoint(75f, 60f), tolerance = 6f))
    }

    @Test
    fun pathHitTestingIncludesEndpointsAndShortSegments() {
        val points = listOf(CanvasPoint(10f, 10f), CanvasPoint(12f, 10f))

        assertTrue(pathContainsPoint(points, CanvasPoint(10f, 10f), tolerance = 1f))
        assertTrue(pathContainsPoint(points, CanvasPoint(11f, 10.5f), tolerance = 1f))
        assertFalse(pathContainsPoint(points, CanvasPoint(11f, 12f), tolerance = 1f))
    }

    @Test
    fun routedEdgeHitTestingUsesScaledToleranceWhenProvided() {
        val source = canvasTable(alias = "t0", x = 20f, y = 80f, width = 440f, height = 448f, layoutScale = 2f)
        val target = canvasTable(alias = "t1", x = 900f, y = 80f, width = 440f, height = 448f, layoutScale = 2f)
        val edge = assertNotNull(routeJoinEdge(source, "id", target, "customer_id", listOf(source, target)))

        val firstSegmentStart = edge.points.first()
        val firstSegmentEnd = edge.points[1]
        val midX = (firstSegmentStart.x + firstSegmentEnd.x) / 2f
        val midY = firstSegmentStart.y

        assertTrue(routedEdgeContainsPoint(edge, midX, midY + 19f, tolerance = JOIN_LINE_HIT_TOLERANCE * source.layoutScale))
        assertFalse(routedEdgeContainsPoint(edge, midX, midY + 21f, tolerance = JOIN_LINE_HIT_TOLERANCE * source.layoutScale))
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
                    joins = listOf(JoinSpec("t0", "customer_id", "t1", "id")),
                ),
            ),
            suggestedRelationships(listOf(orders, customers), emptyList()),
        )
    }

private fun edgeIntersects(points: List<CanvasPoint>, rect: CanvasRect): Boolean =
    points.zipWithNext().any { (a, b) ->
        when {
            a.y == b.y -> rect.intersectsHorizontal(a.y, a.x, b.x)
            a.x == b.x -> rect.intersectsVertical(a.x, a.y, b.y)
            else -> true
        }
    }

    @Test
    fun suggestedRelationshipsTrustForeignKeysWhenReferencedIndexMetadataIsMissing() {
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
        val customers = canvasTable(alias = "t1", name = "customers", indexes = emptyList())

        assertEquals(
            listOf(
                SuggestedRelationship(
                    name = "orders_customer_id_fkey",
                    joins = listOf(JoinSpec("t0", "customer_id", "t1", "id")),
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
    fun suggestedRelationshipsKeepCompositeForeignKeysAtomic() {
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
                SuggestedRelationship(
                    "line_items_order_fkey",
                    listOf(
                        JoinSpec("t0", "order_id", "t1", "id"),
                        JoinSpec("t0", "store_id", "t1", "store_id"),
                    ),
                ),
            ),
            suggestedRelationships(listOf(items, orders), emptyList()),
        )

        val partialJoin = listOf(JoinSpec("t0", "order_id", "t1", "id"))
        assertEquals(
            listOf(
                SuggestedRelationship(
                    "line_items_order_fkey",
                    listOf(
                        JoinSpec("t0", "order_id", "t1", "id"),
                        JoinSpec("t0", "store_id", "t1", "store_id"),
                    ),
                ),
            ),
            suggestedRelationships(listOf(items, orders), partialJoin),
        )
    }

    @Test
    fun suggestedRelationshipsIgnoreStaleColumnsButDoNotRequireUniqueTargetMetadata() {
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

        assertEquals(
            listOf(
                SuggestedRelationship(
                    "orders_sales_rep_fkey",
                    listOf(JoinSpec("t0", "customer_id", "t2", "id")),
                ),
            ),
            suggestedRelationships(listOf(orders, customers, salesReps), emptyList()),
        )
    }
}

private fun canvasTable(
    alias: String = "t0",
    name: String = "users",
    x: Float = 0f,
    y: Float = 0f,
    width: Float = CANVAS_CARD_WIDTH,
    height: Float = CANVAS_CARD_HEIGHT,
    layoutScale: Float = 1f,
    fieldScrollOffset: Float = 0f,
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
    layoutScale = layoutScale,
    fieldScrollOffset = fieldScrollOffset,
    tableInfo = TableInfo(
        schema = "public",
        name = name,
        columns = columns,
        indexes = indexes,
        foreignKeys = foreignKeys,
    ),
)
