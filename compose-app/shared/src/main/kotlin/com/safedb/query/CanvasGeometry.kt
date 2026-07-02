package com.safedb.query

import com.safedb.model.TableInfo

data class CanvasTableLike(
    val alias: String,
    val x: Float,
    val y: Float,
    val width: Float? = null,
    val height: Float? = null,
    val tableInfo: TableInfo,
)

const val CANVAS_CARD_WIDTH = 224f
const val CANVAS_CARD_HEIGHT = 297f
const val CANVAS_HEADER_HEIGHT = 41f
const val CANVAS_ROW_HEIGHT = 28f

const val MIN_TABLE_WIDTH = 180f
const val MAX_TABLE_WIDTH = 520f
const val MIN_TABLE_HEIGHT = 140f
const val MAX_TABLE_HEIGHT = 640f

fun clampDimension(value: Float, min: Float, max: Float): Float {
    if (!value.isFinite()) return min
    return value.coerceIn(min, max).let { kotlin.math.round(it) }
}

/** Top of the row containing [columnName] inside the given table card. */
fun columnY(
    ct: CanvasTableLike,
    columnName: String,
    cardWidth: Float = CANVAS_CARD_WIDTH,
    headerHeight: Float = CANVAS_HEADER_HEIGHT,
    rowHeight: Float = CANVAS_ROW_HEIGHT,
): Float {
    val idx = ct.tableInfo.columns.indexOfFirst { it.name == columnName }
    return ct.y + headerHeight + idx * rowHeight + rowHeight / 2f
}

fun tableRightX(ct: CanvasTableLike, cardWidth: Float = CANVAS_CARD_WIDTH): Float =
    ct.x + (ct.width ?: cardWidth)

fun tableLeftX(ct: CanvasTableLike): Float = ct.x

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
