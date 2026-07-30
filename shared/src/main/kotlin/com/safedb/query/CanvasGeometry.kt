package com.safedb.query

import com.safedb.model.JoinSpec
import com.safedb.model.TableInfo
import kotlin.math.abs

data class CanvasTableLike(
    val alias: String,
    val x: Float,
    val y: Float,
    val width: Float? = null,
    val height: Float? = null,
    val fieldScrollOffset: Float = 0f,
    val layoutScale: Float = 1f,
    val tableInfo: TableInfo,
)

const val CANVAS_CARD_WIDTH = 224f
const val CANVAS_CARD_HEIGHT = 224f
const val CANVAS_INITIAL_TABLE_Y = 232f
const val CANVAS_HEADER_HEIGHT = 50f
const val CANVAS_ROW_HEIGHT = 34f
const val CANVAS_RESIZE_FOOTER_HEIGHT = 24f
const val CANVAS_FIELD_BODY_PADDING_Y = 3f
const val CANVAS_ROW_HIT_PAD_X = 6f

const val MIN_TABLE_WIDTH = 180f
const val MAX_TABLE_WIDTH = 520f
const val MIN_TABLE_HEIGHT = 140f
const val MAX_TABLE_HEIGHT = 640f
const val JOIN_PORT_EXIT = 28f
const val JOIN_ROUTE_MARGIN = 14f
const val JOIN_ROUTE_LANE_STEP = 8f
const val JOIN_LINE_HIT_TOLERANCE = 10f

fun clampDimension(value: Float, min: Float, max: Float): Float {
    if (!value.isFinite()) return min
    return value.coerceIn(min, max).let { kotlin.math.round(it) }
}

/** Top of the row containing [columnName] inside the given table card. */
fun columnY(
    ct: CanvasTableLike,
    columnName: String,
    cardWidth: Float = CANVAS_CARD_WIDTH * ct.layoutScale,
    headerHeight: Float = CANVAS_HEADER_HEIGHT * ct.layoutScale,
    rowHeight: Float = CANVAS_ROW_HEIGHT * ct.layoutScale,
): Float {
    val idx = ct.tableInfo.columns.indexOfFirst { it.name == columnName }
    return ct.y + headerHeight + scaled(CANVAS_FIELD_BODY_PADDING_Y, ct) + idx * rowHeight + rowHeight / 2f
}

fun tableRightX(ct: CanvasTableLike, cardWidth: Float = CANVAS_CARD_WIDTH * ct.layoutScale): Float =
    ct.x + (ct.width ?: cardWidth)

fun tableLeftX(ct: CanvasTableLike): Float = ct.x

fun tableBottomY(ct: CanvasTableLike, cardHeight: Float = CANVAS_CARD_HEIGHT * ct.layoutScale): Float =
    ct.y + (ct.height ?: cardHeight)

fun tableCenterX(ct: CanvasTableLike, cardWidth: Float = CANVAS_CARD_WIDTH * ct.layoutScale): Float =
    ct.x + (ct.width ?: cardWidth) / 2f

fun tableCenterY(ct: CanvasTableLike, cardHeight: Float = CANVAS_CARD_HEIGHT * ct.layoutScale): Float =
    ct.y + (ct.height ?: cardHeight) / 2f

data class CanvasPoint(
    val x: Float,
    val y: Float,
)

data class CanvasRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    fun expanded(amount: Float): CanvasRect =
        CanvasRect(left - amount, top - amount, right + amount, bottom + amount)

    fun contains(point: CanvasPoint): Boolean =
        point.x > left && point.x < right && point.y > top && point.y < bottom

    fun intersectsHorizontal(y: Float, x1: Float, x2: Float): Boolean {
        if (y <= top || y >= bottom) return false
        val start = minOf(x1, x2)
        val end = maxOf(x1, x2)
        return start < right && end > left
    }

    fun intersectsVertical(x: Float, y1: Float, y2: Float): Boolean {
        if (x <= left || x >= right) return false
        val start = minOf(y1, y2)
        val end = maxOf(y1, y2)
        return start < bottom && end > top
    }
}

enum class JoinPortSide {
    Left,
    Right,
}

enum class JoinPortVisibility {
    Visible,
    HiddenAbove,
    HiddenBelow,
}

data class ColumnJoinPort(
    val point: CanvasPoint,
    val side: JoinPortSide,
    val visibility: JoinPortVisibility,
)

data class RoutedJoinEdge(
    val points: List<CanvasPoint>,
    val sourcePort: ColumnJoinPort,
    val targetPort: ColumnJoinPort,
)

fun routedEdgeContainsPoint(
    edge: RoutedJoinEdge,
    x: Float,
    y: Float,
    tolerance: Float = JOIN_LINE_HIT_TOLERANCE,
): Boolean =
    pathContainsPoint(edge.points, CanvasPoint(x, y), tolerance)

fun pathContainsPoint(
    points: List<CanvasPoint>,
    point: CanvasPoint,
    tolerance: Float = JOIN_LINE_HIT_TOLERANCE,
): Boolean {
    if (points.size < 2 || tolerance < 0f) return false
    val toleranceSquared = tolerance * tolerance
    return points.zipWithNext().any { (start, end) ->
        distanceToSegmentSquared(point, start, end) <= toleranceSquared
    }
}

fun tableBounds(
    ct: CanvasTableLike,
    cardWidth: Float = CANVAS_CARD_WIDTH * ct.layoutScale,
    cardHeight: Float = CANVAS_CARD_HEIGHT * ct.layoutScale,
): CanvasRect =
    CanvasRect(
        left = tableLeftX(ct),
        top = ct.y,
        right = tableRightX(ct, cardWidth),
        bottom = tableBottomY(ct, cardHeight),
    )

fun fieldViewportBounds(
    ct: CanvasTableLike,
    cardWidth: Float = CANVAS_CARD_WIDTH * ct.layoutScale,
    cardHeight: Float = CANVAS_CARD_HEIGHT * ct.layoutScale,
): CanvasRect {
    val bounds = tableBounds(ct, cardWidth, cardHeight)
    return CanvasRect(
        left = bounds.left,
        top = bounds.top + scaled(CANVAS_HEADER_HEIGHT, ct) + scaled(CANVAS_FIELD_BODY_PADDING_Y, ct),
        right = bounds.right,
        bottom = bounds.bottom - scaled(CANVAS_RESIZE_FOOTER_HEIGHT, ct) - scaled(CANVAS_FIELD_BODY_PADDING_Y, ct),
    )
}

data class ColumnHitBounds(
    val alias: String,
    val column: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    fun contains(x: Float, y: Float): Boolean =
        x in left..right && y in top..bottom
}

fun columnHitBounds(
    ct: CanvasTableLike,
    columnName: String,
    cardWidth: Float = CANVAS_CARD_WIDTH * ct.layoutScale,
    cardHeight: Float = CANVAS_CARD_HEIGHT * ct.layoutScale,
    headerHeight: Float = CANVAS_HEADER_HEIGHT * ct.layoutScale,
    rowHeight: Float = CANVAS_ROW_HEIGHT * ct.layoutScale,
): ColumnHitBounds? {
    val idx = ct.tableInfo.columns.indexOfFirst { it.name == columnName }
    if (idx < 0) return null
    val viewport = fieldViewportBounds(ct, cardWidth, cardHeight)
    val rowTop = ct.y + headerHeight + scaled(CANVAS_FIELD_BODY_PADDING_Y, ct) + idx * rowHeight - ct.fieldScrollOffset
    val rowBottom = rowTop + rowHeight
    if (rowBottom <= viewport.top || rowTop >= viewport.bottom) return null
    val top = maxOf(rowTop, viewport.top)
    return ColumnHitBounds(
        alias = ct.alias,
        column = columnName,
        left = ct.x + scaled(CANVAS_ROW_HIT_PAD_X, ct),
        top = top,
        right = tableRightX(ct, cardWidth) - scaled(CANVAS_ROW_HIT_PAD_X, ct),
        bottom = minOf(rowBottom, viewport.bottom),
    )
}

fun columnJoinPort(
    ct: CanvasTableLike,
    columnName: String,
    side: JoinPortSide,
    cardWidth: Float = CANVAS_CARD_WIDTH * ct.layoutScale,
    cardHeight: Float = CANVAS_CARD_HEIGHT * ct.layoutScale,
    headerHeight: Float = CANVAS_HEADER_HEIGHT * ct.layoutScale,
    rowHeight: Float = CANVAS_ROW_HEIGHT * ct.layoutScale,
): ColumnJoinPort? {
    val idx = ct.tableInfo.columns.indexOfFirst { it.name == columnName }
    if (idx < 0) return null

    val viewport = fieldViewportBounds(ct, cardWidth, cardHeight)
    val unclampedY = ct.y + headerHeight + scaled(CANVAS_FIELD_BODY_PADDING_Y, ct) + idx * rowHeight + rowHeight / 2f - ct.fieldScrollOffset
    val markerInset = scaled(8f, ct)
    val visibility = when {
        unclampedY < viewport.top -> JoinPortVisibility.HiddenAbove
        unclampedY > viewport.bottom -> JoinPortVisibility.HiddenBelow
        else -> JoinPortVisibility.Visible
    }
    val y = when (visibility) {
        JoinPortVisibility.Visible -> unclampedY
        JoinPortVisibility.HiddenAbove -> viewport.top + markerInset
        JoinPortVisibility.HiddenBelow -> viewport.bottom - markerInset
    }
    return ColumnJoinPort(tableSidePoint(ct, side, y, cardWidth), side, visibility)
}

fun routeJoinEdge(
    source: CanvasTableLike,
    sourceColumn: String,
    target: CanvasTableLike,
    targetColumn: String,
    allTables: List<CanvasTableLike>,
    laneIndex: Int = 0,
    cardWidth: Float = CANVAS_CARD_WIDTH * source.layoutScale,
    cardHeight: Float = CANVAS_CARD_HEIGHT * source.layoutScale,
): RoutedJoinEdge? {
    val sourceSide = JoinPortSide.Right
    val targetSide = JoinPortSide.Right
    val sourcePort = columnJoinPort(source, sourceColumn, sourceSide, cardWidth, cardHeight) ?: return null
    val targetPort = columnJoinPort(target, targetColumn, targetSide, cardWidth, cardHeight) ?: return null
    val routeScale = source.layoutScale
    val laneOffset = laneIndex.coerceAtLeast(0) * scaled(JOIN_ROUTE_LANE_STEP, routeScale)
    val sourceSidePoint = tableSidePoint(source, sourceSide, sourcePort.point.y, cardWidth)
    val targetSidePoint = tableSidePoint(target, targetSide, targetPort.point.y, cardWidth)
    val sourceExit = sourceSidePoint.exitPoint(sourceSide, scaled(JOIN_PORT_EXIT, routeScale) + laneOffset)
    val targetExit = targetSidePoint.exitPoint(targetSide, scaled(JOIN_PORT_EXIT, routeScale) + laneOffset)
    val obstacles = allTables
        .map { tableBounds(it).expanded(scaled(JOIN_ROUTE_MARGIN, routeScale)) }

    val middle = routeOrthogonal(sourceExit, targetExit, obstacles)
    val points = mutableListOf(sourcePort.point)
    points.addIfDistinct(sourceSidePoint)
    points.addIfDistinct(sourceExit)
    points.addAll(middle.drop(1))
    points.addIfDistinct(targetSidePoint)
    points.addIfDistinct(targetPort.point)
    return RoutedJoinEdge(
        points = points,
        sourcePort = sourcePort,
        targetPort = targetPort,
    )
}

private fun scaled(value: Float, ct: CanvasTableLike): Float =
    scaled(value, ct.layoutScale)

private fun scaled(value: Float, scale: Float): Float =
    value * scale

private fun tableSidePoint(
    ct: CanvasTableLike,
    side: JoinPortSide,
    y: Float,
    cardWidth: Float,
): CanvasPoint =
    CanvasPoint(
        x = when (side) {
            JoinPortSide.Left -> tableLeftX(ct)
            JoinPortSide.Right -> tableRightX(ct, cardWidth)
        },
        y = y,
    )

private fun MutableList<CanvasPoint>.addIfDistinct(point: CanvasPoint) {
    if (lastOrNull()?.nearlyEquals(point) != true) add(point)
}

private fun CanvasPoint.exitPoint(side: JoinPortSide, amount: Float): CanvasPoint =
    when (side) {
        JoinPortSide.Left -> copy(x = x - amount)
        JoinPortSide.Right -> copy(x = x + amount)
    }

private fun distanceToSegmentSquared(point: CanvasPoint, start: CanvasPoint, end: CanvasPoint): Float {
    val dx = end.x - start.x
    val dy = end.y - start.y
    val lengthSquared = dx * dx + dy * dy
    if (lengthSquared <= 0f) {
        val pointDx = point.x - start.x
        val pointDy = point.y - start.y
        return pointDx * pointDx + pointDy * pointDy
    }

    val t = (((point.x - start.x) * dx + (point.y - start.y) * dy) / lengthSquared).coerceIn(0f, 1f)
    val projectionX = start.x + t * dx
    val projectionY = start.y + t * dy
    val pointDx = point.x - projectionX
    val pointDy = point.y - projectionY
    return pointDx * pointDx + pointDy * pointDy
}

private fun routeOrthogonal(
    start: CanvasPoint,
    target: CanvasPoint,
    obstacles: List<CanvasRect>,
): List<CanvasPoint> {
    val direct = listOf(start, CanvasPoint((start.x + target.x) / 2f, start.y), CanvasPoint((start.x + target.x) / 2f, target.y), target)
    if (direct.isClear(obstacles)) return direct

    val xCandidates = mutableSetOf(start.x, target.x)
    val yCandidates = mutableSetOf(start.y, target.y)
    for (obstacle in obstacles) {
        xCandidates.add(obstacle.left)
        xCandidates.add(obstacle.right)
        yCandidates.add(obstacle.top)
        yCandidates.add(obstacle.bottom)
    }
    val xs = xCandidates.sorted()
    val ys = yCandidates.sorted()
    val nodes = mutableListOf<CanvasPoint>()
    for (x in xs) {
        for (y in ys) {
            val point = CanvasPoint(x, y)
            if (obstacles.none { it.contains(point) }) {
                nodes.add(point)
            }
        }
    }

    val startIndex = nodes.indexOfFirst { it.nearlyEquals(start) }
    val targetIndex = nodes.indexOfFirst { it.nearlyEquals(target) }
    if (startIndex < 0 || targetIndex < 0) return direct

    val edges = buildOrthogonalEdges(nodes, obstacles)
    val route = shortestRoute(startIndex, targetIndex, nodes, edges)
    return route ?: direct
}

private fun buildOrthogonalEdges(
    nodes: List<CanvasPoint>,
    obstacles: List<CanvasRect>,
): Map<Int, List<Int>> {
    val edges = mutableMapOf<Int, MutableList<Int>>()
    val byX = nodes.withIndex().groupBy { it.value.x }
    val byY = nodes.withIndex().groupBy { it.value.y }

    for (group in byX.values) {
        val sorted = group.sortedBy { it.value.y }
        for ((a, b) in sorted.zipWithNext()) {
            if (segmentClear(a.value, b.value, obstacles)) {
                edges.getOrPut(a.index) { mutableListOf() }.add(b.index)
                edges.getOrPut(b.index) { mutableListOf() }.add(a.index)
            }
        }
    }
    for (group in byY.values) {
        val sorted = group.sortedBy { it.value.x }
        for ((a, b) in sorted.zipWithNext()) {
            if (segmentClear(a.value, b.value, obstacles)) {
                edges.getOrPut(a.index) { mutableListOf() }.add(b.index)
                edges.getOrPut(b.index) { mutableListOf() }.add(a.index)
            }
        }
    }
    return edges
}

private fun shortestRoute(
    startIndex: Int,
    targetIndex: Int,
    nodes: List<CanvasPoint>,
    edges: Map<Int, List<Int>>,
): List<CanvasPoint>? {
    val unvisited = nodes.indices.toMutableSet()
    val distance = FloatArray(nodes.size) { Float.POSITIVE_INFINITY }
    val previous = IntArray(nodes.size) { -1 }
    distance[startIndex] = 0f

    while (unvisited.isNotEmpty()) {
        val current = unvisited.minBy { distance[it] }
        if (!distance[current].isFinite()) break
        unvisited.remove(current)
        if (current == targetIndex) break
        for (next in edges[current].orEmpty()) {
            if (next !in unvisited) continue
            val cost = distance[current] + manhattan(nodes[current], nodes[next])
            if (cost < distance[next]) {
                distance[next] = cost
                previous[next] = current
            }
        }
    }
    if (!distance[targetIndex].isFinite()) return null

    val route = mutableListOf<CanvasPoint>()
    var cursor = targetIndex
    while (cursor >= 0) {
        route.add(nodes[cursor])
        cursor = previous[cursor]
    }
    return route.asReversed().simplifiedOrthogonalPoints()
}

private fun List<CanvasPoint>.isClear(obstacles: List<CanvasRect>): Boolean =
    zipWithNext().all { (a, b) -> segmentClear(a, b, obstacles) }

private fun segmentClear(a: CanvasPoint, b: CanvasPoint, obstacles: List<CanvasRect>): Boolean =
    obstacles.none { obstacle ->
        when {
            a.y == b.y -> obstacle.intersectsHorizontal(a.y, a.x, b.x)
            a.x == b.x -> obstacle.intersectsVertical(a.x, a.y, b.y)
            else -> true
        }
    }

private fun manhattan(a: CanvasPoint, b: CanvasPoint): Float =
    abs(a.x - b.x) + abs(a.y - b.y)

private fun CanvasPoint.nearlyEquals(other: CanvasPoint): Boolean =
    abs(x - other.x) < 0.001f && abs(y - other.y) < 0.001f

private fun List<CanvasPoint>.simplifiedOrthogonalPoints(): List<CanvasPoint> {
    if (size <= 2) return this
    val result = mutableListOf(first())
    for (idx in 1 until lastIndex) {
        val previous = result.last()
        val current = this[idx]
        val next = this[idx + 1]
        val sameVertical = previous.x == current.x && current.x == next.x
        val sameHorizontal = previous.y == current.y && current.y == next.y
        if (!sameVertical && !sameHorizontal) {
            result.add(current)
        }
    }
    result.add(last())
    return result
}

fun indexedJoinTargetAt(
    tables: List<CanvasTableLike>,
    x: Float,
    y: Float,
    sourceAlias: String,
    sourceColumn: String,
): Pair<String, String>? {
    for (table in tables.asReversed()) {
        for (column in table.tableInfo.columns) {
            if (!column.isIndexed) continue
            if (table.alias == sourceAlias && column.name == sourceColumn) continue
            val bounds = columnHitBounds(table, column.name) ?: continue
            if (bounds.contains(x, y)) {
                return table.alias to column.name
            }
        }
    }
    return null
}

data class SuggestedRelationship(
    val name: String,
    val joins: List<JoinSpec>,
)

fun suggestedRelationships(
    tables: List<CanvasTableLike>,
    joins: List<JoinSpec>,
): List<SuggestedRelationship> {
    val tableByQualifiedName = tables.associateBy { qualifiedTableKey(it.tableInfo.schema, it.tableInfo.name) }
    val suggestions = mutableListOf<SuggestedRelationship>()

    for (foreignTable in tables) {
        for (foreignKey in foreignTable.tableInfo.foreignKeys) {
            if (foreignKey.columns.size != foreignKey.referencedColumns.size) continue
            if (foreignKey.columns.isEmpty()) continue

            val referencedTable = tableByQualifiedName[
                qualifiedTableKey(foreignKey.referencedSchema, foreignKey.referencedTable)
            ] ?: continue

            val columnPairs = foreignKey.columns.zip(foreignKey.referencedColumns)
            if (columnPairs.any { (foreignColumn, referencedColumn) ->
                    !foreignTable.tableInfo.hasColumn(foreignColumn) ||
                        !referencedTable.tableInfo.hasColumn(referencedColumn)
                }
            ) continue

            val relationshipJoins = columnPairs.map { (foreignColumn, referencedColumn) ->
                JoinSpec(
                    leftAlias = foreignTable.alias,
                    leftColumn = foreignColumn,
                    rightAlias = referencedTable.alias,
                    rightColumn = referencedColumn,
                )
            }
            if (relationshipJoins.all { suggested ->
                    joins.hasJoin(
                        suggested.leftAlias,
                        suggested.leftColumn,
                        suggested.rightAlias,
                        suggested.rightColumn,
                    )
                }
            ) continue
            suggestions.add(SuggestedRelationship(name = foreignKey.name, joins = relationshipJoins))
        }
    }

    return suggestions.distinct()
}

private fun TableInfo.hasColumn(column: String): Boolean =
    columns.any { it.name == column }

private fun List<JoinSpec>.hasJoin(
    leftAlias: String,
    leftColumn: String,
    rightAlias: String,
    rightColumn: String,
): Boolean = any { join ->
    (join.leftAlias == leftAlias && join.leftColumn == leftColumn &&
        join.rightAlias == rightAlias && join.rightColumn == rightColumn) ||
        (join.leftAlias == rightAlias && join.leftColumn == rightColumn &&
            join.rightAlias == leftAlias && join.rightColumn == leftColumn)
}

private fun qualifiedTableKey(schema: String, table: String): String =
    "$schema.$table"

/** Cubic Bezier path between two tables' join endpoints (SVG path data). */
fun joinEdgePath(
    left: CanvasTableLike,
    leftColumn: String,
    right: CanvasTableLike,
    rightColumn: String,
    cardWidth: Float = CANVAS_CARD_WIDTH,
): String {
    val points = joinEdgePoints(left, leftColumn, right, rightColumn, cardWidth)
    return "M ${points.sourceX} ${points.sourceY} C ${points.control1X} ${points.control1Y}, ${points.control2X} ${points.control2Y}, ${points.targetX} ${points.targetY}"
}

data class JoinEdgePoints(
    val sourceX: Float,
    val sourceY: Float,
    val control1X: Float,
    val control1Y: Float,
    val control2X: Float,
    val control2Y: Float,
    val targetX: Float,
    val targetY: Float,
)

fun joinEdgePoints(
    left: CanvasTableLike,
    leftColumn: String,
    right: CanvasTableLike,
    rightColumn: String,
    cardWidth: Float = CANVAS_CARD_WIDTH,
): JoinEdgePoints {
    val sourceX = tableRightX(left, cardWidth)
    val targetX = tableLeftX(right)
    val sourceY = columnY(left, leftColumn, cardWidth)
    val targetY = columnY(right, rightColumn, cardWidth)
    val midX = (sourceX + targetX) / 2f
    return JoinEdgePoints(
        sourceX = sourceX,
        sourceY = sourceY,
        control1X = midX,
        control1Y = sourceY,
        control2X = midX,
        control2Y = targetY,
        targetX = targetX,
        targetY = targetY,
    )
}
