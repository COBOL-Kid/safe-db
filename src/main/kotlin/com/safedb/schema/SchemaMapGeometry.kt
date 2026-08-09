package com.safedb.schema

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import com.safedb.canvas.CanvasPoint
import com.safedb.canvas.CanvasRect
import com.safedb.canvas.routeOrthogonalPath
import kotlin.math.abs

internal data class SchemaMapRelationshipGeometry(
    val source: SchemaMapPoint,
    val target: SchemaMapPoint,
    val bends: List<SchemaMapPoint>,
    val anchor: SchemaMapPoint,
    val sourceTowardRight: Boolean,
    val targetTowardRight: Boolean,
)

internal fun SchemaMapPoint.translated(deltaX: Float, deltaY: Float): SchemaMapPoint =
    SchemaMapPoint(x + deltaX, y + deltaY)

internal fun SchemaMapRelationshipGeometry.translated(
    deltaX: Float,
    deltaY: Float,
): SchemaMapRelationshipGeometry =
    copy(
        source = source.translated(deltaX, deltaY),
        target = target.translated(deltaX, deltaY),
        bends = bends.map { it.translated(deltaX, deltaY) },
        anchor = anchor.translated(deltaX, deltaY),
    )

internal fun schemaMapRelationshipGeometries(
    graph: SchemaMapGraph,
    positions: Map<String, SchemaMapPoint>,
    sizes: Map<String, SchemaMapSize>,
): Map<String, SchemaMapRelationshipGeometry> {
    val grouped =
        graph.relationships.groupBy { relationship ->
            listOf(relationship.sourceNodeId, relationship.targetNodeId).sorted().joinToString("|")
        }
    val tableObstacles =
        graph.nodes.map { node ->
            val point = positions.getValue(node.id)
            val size = sizes.getValue(node.id)
            CanvasRect(
                    left = point.x,
                    top = point.y,
                    right = point.x + size.width,
                    bottom = point.y + size.height,
                )
                .expanded(RELATIONSHIP_TABLE_CLEARANCE)
        }
    val occupiedVerticalLanes = mutableListOf<SchemaMapVerticalLane>()
    return buildMap {
        grouped.toSortedMap().values.forEach { relationships ->
            relationships.sortedBy(SchemaMapRelationship::id).forEachIndexed { index, relationship
                ->
                val lane = index - (relationships.size - 1) / 2f
                val source = positions.getValue(relationship.sourceNodeId)
                val target = positions.getValue(relationship.targetNodeId)
                val sourceSize = sizes.getValue(relationship.sourceNodeId)
                val targetSize = sizes.getValue(relationship.targetNodeId)
                val sameNode = relationship.sourceNodeId == relationship.targetNodeId
                val sameColumn = !sameNode && kotlin.math.abs(source.x - target.x) < 1f
                val route =
                    if (sameNode || sameColumn) {
                        val right = maxOf(source.x + sourceSize.width, target.x + targetSize.width)
                        val outerX = right + 54f + index * 18f
                        val sourceY =
                            if (sameNode) source.y + sourceSize.height * 0.36f + lane * 9f
                            else source.y + sourceSize.height / 2f + lane * 18f
                        val targetY =
                            if (sameNode) target.y + targetSize.height * 0.64f + lane * 9f
                            else target.y + targetSize.height / 2f + lane * 18f
                        schemaMapObstacleAvoidingRoute(
                            source = SchemaMapPoint(source.x + sourceSize.width, sourceY),
                            target = SchemaMapPoint(target.x + targetSize.width, targetY),
                            sourceTowardRight = true,
                            targetTowardRight = true,
                            preferredMiddleX = outerX,
                            obstacles = tableObstacles,
                        )
                    } else {
                        val sourceOnRight = source.x < target.x
                        val laneOffset = lane * 18f
                        val sourcePoint =
                            SchemaMapPoint(
                                source.x + if (sourceOnRight) sourceSize.width else 0f,
                                source.y + sourceSize.height / 2f + laneOffset,
                            )
                        val targetPoint =
                            SchemaMapPoint(
                                target.x + if (sourceOnRight) 0f else targetSize.width,
                                target.y + targetSize.height / 2f + laneOffset,
                            )
                        val middleX =
                            separatedRelationshipLane(
                                source = sourcePoint,
                                target = targetPoint,
                                preferredX = (sourcePoint.x + targetPoint.x) / 2f + lane * 4f,
                                occupied = occupiedVerticalLanes,
                            )
                        schemaMapObstacleAvoidingRoute(
                            source = sourcePoint,
                            target = targetPoint,
                            sourceTowardRight = sourceOnRight,
                            targetTowardRight = !sourceOnRight,
                            preferredMiddleX = middleX,
                            obstacles = tableObstacles,
                        )
                    }
                recordVerticalLanes(route, occupiedVerticalLanes)
                put(relationship.id, route)
            }
        }
    }
}

private fun schemaMapObstacleAvoidingRoute(
    source: SchemaMapPoint,
    target: SchemaMapPoint,
    sourceTowardRight: Boolean,
    targetTowardRight: Boolean,
    preferredMiddleX: Float,
    obstacles: List<CanvasRect>,
): SchemaMapRelationshipGeometry {
    val sourceExit = source.horizontalExit(sourceTowardRight)
    val targetExit = target.horizontalExit(targetTowardRight)
    val middle =
        routeOrthogonalPath(
                start = sourceExit.toCanvasPoint(),
                target = targetExit.toCanvasPoint(),
                obstacles = obstacles,
                preferredMiddleX = preferredMiddleX,
            )
            .map(CanvasPoint::toSchemaMapPoint)
    val points = buildList {
        add(source)
        addAll(middle)
        add(target)
    }
        .distinctAdjacent()
    return SchemaMapRelationshipGeometry(
        source = source,
        target = target,
        bends = points.drop(1).dropLast(1),
        anchor = relationshipPathMidpoint(points),
        sourceTowardRight = sourceTowardRight,
        targetTowardRight = targetTowardRight,
    )
}

private fun SchemaMapPoint.horizontalExit(towardRight: Boolean): SchemaMapPoint =
    copy(x = x + if (towardRight) RELATIONSHIP_TABLE_CLEARANCE else -RELATIONSHIP_TABLE_CLEARANCE)

private fun SchemaMapPoint.toCanvasPoint(): CanvasPoint = CanvasPoint(x, y)

private fun CanvasPoint.toSchemaMapPoint(): SchemaMapPoint = SchemaMapPoint(x, y)

private fun List<SchemaMapPoint>.distinctAdjacent(): List<SchemaMapPoint> =
    fold(mutableListOf<SchemaMapPoint>()) { result, point ->
        if (result.lastOrNull() != point) result += point
        result
    }

private fun relationshipPathMidpoint(points: List<SchemaMapPoint>): SchemaMapPoint {
    val segmentLengths =
        points.zipWithNext().map { (start, end) ->
            kotlin.math.abs(end.x - start.x) + kotlin.math.abs(end.y - start.y)
        }
    var remaining = segmentLengths.sum() / 2f
    points.zipWithNext().forEachIndexed { index, (start, end) ->
        val length = segmentLengths[index]
        if (remaining <= length) {
            if (length == 0f) return start
            val progress = remaining / length
            return SchemaMapPoint(
                x = start.x + (end.x - start.x) * progress,
                y = start.y + (end.y - start.y) * progress,
            )
        }
        remaining -= length
    }
    return points.lastOrNull() ?: SchemaMapPoint(0f, 0f)
}

private fun recordVerticalLanes(
    geometry: SchemaMapRelationshipGeometry,
    occupied: MutableList<SchemaMapVerticalLane>,
) {
    val points = listOf(geometry.source) + geometry.bends + geometry.target
    points.zipWithNext().forEach { (start, end) ->
        if (start.x == end.x && start.y != end.y) {
            occupied += SchemaMapVerticalLane(start.x, minOf(start.y, end.y), maxOf(start.y, end.y))
        }
    }
}

private data class SchemaMapVerticalLane(val x: Float, val top: Float, val bottom: Float)

private fun separatedRelationshipLane(
    source: SchemaMapPoint,
    target: SchemaMapPoint,
    preferredX: Float,
    occupied: MutableList<SchemaMapVerticalLane>,
): Float {
    val top = minOf(source.y, target.y)
    val bottom = maxOf(source.y, target.y)
    val minimumX = minOf(source.x, target.x) + RELATIONSHIP_MIN_HORIZONTAL_RUN
    val maximumX = maxOf(source.x, target.x) - RELATIONSHIP_MIN_HORIZONTAL_RUN
    if (minimumX > maximumX) return preferredX
    val candidates = buildList {
        add(preferredX)
        for (distance in 1..RELATIONSHIP_LANE_SEARCH_LIMIT) {
            add(preferredX + distance * RELATIONSHIP_LANE_SPACING)
            add(preferredX - distance * RELATIONSHIP_LANE_SPACING)
        }
    }
    val selected =
        candidates.firstOrNull { candidate ->
            candidate in minimumX..maximumX &&
                occupied.none { lane ->
                    verticalRangesOverlap(top, bottom, lane.top, lane.bottom) &&
                        kotlin.math.abs(candidate - lane.x) < RELATIONSHIP_LANE_SPACING
                }
        } ?: preferredX.coerceIn(minimumX, maximumX)
    return selected
}

private fun verticalRangesOverlap(
    firstTop: Float,
    firstBottom: Float,
    secondTop: Float,
    secondBottom: Float,
) = maxOf(firstTop, secondTop) < minOf(firstBottom, secondBottom)

internal fun SchemaMapPoint.toPxOffset(scope: DrawScope): Offset =
    with(scope) { Offset(x.dp.toPx(), y.dp.toPx()) }

internal fun schemaMapContentBounds(
    graph: SchemaMapGraph,
    positions: Map<String, SchemaMapPoint>,
    sizes: Map<String, SchemaMapSize>,
    density: Float,
    relationshipGeometry: Map<String, SchemaMapRelationshipGeometry> = emptyMap(),
): Rect {
    val relationshipPoints =
        relationshipGeometry.values.flatMap { geometry ->
            listOf(geometry.source, geometry.target) + geometry.bends
        }
    val left =
        minOf(
            graph.nodes.minOf { positions.getValue(it.id).x },
            relationshipPoints.minOfOrNull(SchemaMapPoint::x) ?: Float.MAX_VALUE,
        ) * density
    val top =
        minOf(
            graph.nodes.minOf { positions.getValue(it.id).y },
            relationshipPoints.minOfOrNull(SchemaMapPoint::y) ?: Float.MAX_VALUE,
        ) * density
    val right =
        graph.nodes.maxOf { node ->
            (positions.getValue(node.id).x + sizes.getValue(node.id).width) * density
        }
    val nodeBottom =
        graph.nodes.maxOf { node ->
            (positions.getValue(node.id).y + sizes.getValue(node.id).height) * density
        }
    val relationshipRight =
        (relationshipPoints.maxOfOrNull(SchemaMapPoint::x) ?: Float.MIN_VALUE) * density
    val relationshipBottom =
        (relationshipPoints.maxOfOrNull(SchemaMapPoint::y) ?: Float.MIN_VALUE) * density
    return Rect(left, top, maxOf(right, relationshipRight), maxOf(nodeBottom, relationshipBottom))
}

internal fun schemaMapNodeBounds(
    nodeId: String,
    positions: Map<String, SchemaMapPoint>,
    sizes: Map<String, SchemaMapSize>,
    density: Float,
): Rect {
    val point = positions.getValue(nodeId)
    val size = sizes.getValue(nodeId)
    return Rect(
        point.x * density,
        point.y * density,
        (point.x + size.width) * density,
        (point.y + size.height) * density,
    )
}

internal fun Int?.orZero(): Int = this ?: 0

internal const val MAP_CARD_HEADER = 48f
internal const val MAP_CARD_ROW = 29f
internal const val MAP_CARD_FOOTER = 32f
internal const val SCHEMA_MAP_RENDER_PADDING_DP = 20f
internal const val SCHEMA_MAP_SCROLLBAR_CORNER = 10f
private const val RELATIONSHIP_LANE_SPACING = 18f
private const val RELATIONSHIP_MIN_HORIZONTAL_RUN = 24f
private const val RELATIONSHIP_LANE_SEARCH_LIMIT = 24
private const val RELATIONSHIP_TABLE_CLEARANCE = 12f
