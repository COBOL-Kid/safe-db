package com.safedb.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.safedb.model.JoinSpec
import com.safedb.query.CanvasTableLike
import com.safedb.query.columnY
import com.safedb.query.joinEdgePoints
import com.safedb.query.tableLeftX
import com.safedb.query.tableRightX
import com.safedb.viewmodel.CanvasTable
import com.safedb.viewmodel.QueryViewModel
import kotlin.math.roundToInt

private const val CANVAS_MIN_WIDTH_DP = 2400
private const val CANVAS_MIN_HEIGHT_DP = 1800

private data class DragTableState(
    val alias: String,
    val offsetX: Float,
    val offsetY: Float,
)

private data class ResizeTableState(
    val alias: String,
    val startX: Float,
    val startY: Float,
    val startWidth: Float,
    val startHeight: Float,
)

private data class DragJoinState(
    val sourceAlias: String,
    val sourceColumn: String,
    val mouseX: Float,
    val mouseY: Float,
)

@Composable
fun Canvas(
    queryViewModel: QueryViewModel,
    modifier: Modifier = Modifier,
) {
    var dragTable by remember { mutableStateOf<DragTableState?>(null) }
    var resizeTable by remember { mutableStateOf<ResizeTableState?>(null) }
    var dragJoin by remember { mutableStateOf<DragJoinState?>(null) }
    var canvasOrigin by remember { mutableStateOf(Offset.Zero) }
    val horizontalScroll = rememberScrollState()
    val verticalScroll = rememberScrollState()

    fun toCanvasCoords(position: Offset): Offset =
        Offset(
            x = position.x + horizontalScroll.value - canvasOrigin.x,
            y = position.y + verticalScroll.value - canvasOrigin.y,
        )

    fun canvasTableLike(table: CanvasTable): CanvasTableLike =
        CanvasTableLike(
            alias = table.alias,
            x = table.x,
            y = table.y,
            width = table.width,
            height = table.height,
            tableInfo = table.tableInfo,
        )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .pointerInput(dragTable, resizeTable, dragJoin) {
                detectDragGestures(
                    onDragEnd = {
                        dragTable = null
                        resizeTable = null
                        dragJoin = null
                    },
                    onDragCancel = {
                        dragTable = null
                        resizeTable = null
                        dragJoin = null
                    },
                    onDrag = { change, _ ->
                        val coords = toCanvasCoords(change.position)
                        dragTable?.let { state ->
                            queryViewModel.moveTable(
                                state.alias,
                                coords.x - state.offsetX,
                                coords.y - state.offsetY,
                            )
                        }
                        resizeTable?.let { state ->
                            queryViewModel.resizeTable(
                                state.alias,
                                state.startWidth + coords.x - state.startX,
                                state.startHeight + coords.y - state.startY,
                            )
                        }
                        dragJoin?.let { state ->
                            dragJoin = state.copy(mouseX = coords.x, mouseY = coords.y)
                        }
                    },
                )
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(verticalScroll)
                .horizontalScroll(horizontalScroll),
        ) {
            Box(
                modifier = Modifier
                    .size(CANVAS_MIN_WIDTH_DP.dp, CANVAS_MIN_HEIGHT_DP.dp)
                    .onGloballyPositioned { coordinates ->
                        canvasOrigin = coordinates.positionInRoot()
                    },
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    queryViewModel.joins.forEach { join ->
                        val left = queryViewModel.canvasTables.find { it.alias == join.leftAlias } ?: return@forEach
                        val right = queryViewModel.canvasTables.find { it.alias == join.rightAlias } ?: return@forEach
                        val points = joinEdgePoints(
                            canvasTableLike(left),
                            join.leftColumn,
                            canvasTableLike(right),
                            join.rightColumn,
                        )
                        val path = Path().apply {
                            moveTo(points.sourceX, points.sourceY)
                            cubicTo(
                                points.control1X,
                                points.control1Y,
                                points.control2X,
                                points.control2Y,
                                points.targetX,
                                points.targetY,
                            )
                        }
                        drawPath(
                            path = path,
                            color = Color(0xFF0EA5E9),
                            style = Stroke(width = 2f, cap = StrokeCap.Round),
                        )
                        drawCircle(Color(0xFF0EA5E9), 4f, Offset(points.sourceX, points.sourceY))
                        drawCircle(Color(0xFF0EA5E9), 4f, Offset(points.targetX, points.targetY))
                    }

                    dragJoin?.let { state ->
                        val source = queryViewModel.canvasTables.find { it.alias == state.sourceAlias }
                        if (source != null) {
                            val like = canvasTableLike(source)
                            drawLine(
                                color = Color(0xFF0EA5E9).copy(alpha = 0.6f),
                                start = Offset(tableRightX(like), columnY(like, state.sourceColumn)),
                                end = Offset(state.mouseX, state.mouseY),
                                strokeWidth = 2f,
                                cap = StrokeCap.Round,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f)),
                            )
                        }
                    }
                }

                for (canvasTable in queryViewModel.canvasTables) {
                    TableCard(
                        canvasTable = canvasTable,
                        queryViewModel = queryViewModel,
                        joinDragActive = dragJoin != null,
                        highlightJoinTargets = dragJoin?.let { it.sourceAlias to it.sourceColumn },
                        onStartDrag = { offset ->
                            val coords = toCanvasCoords(offset)
                            dragTable = DragTableState(
                                alias = canvasTable.alias,
                                offsetX = coords.x - canvasTable.x,
                                offsetY = coords.y - canvasTable.y,
                            )
                        },
                        onStartJoin = { offset, column ->
                            val coords = toCanvasCoords(offset)
                            dragJoin = DragJoinState(
                                sourceAlias = canvasTable.alias,
                                sourceColumn = column,
                                mouseX = coords.x,
                                mouseY = coords.y,
                            )
                        },
                        onJoinTargetClick = { targetAlias, targetColumn ->
                            val source = dragJoin ?: return@TableCard
                            if (targetAlias != source.sourceAlias || targetColumn != source.sourceColumn) {
                                queryViewModel.addJoin(
                                    JoinSpec(
                                        leftAlias = source.sourceAlias,
                                        leftColumn = source.sourceColumn,
                                        rightAlias = targetAlias,
                                        rightColumn = targetColumn,
                                    ),
                                )
                            }
                            dragJoin = null
                        },
                        onStartResize = { offset ->
                            val coords = toCanvasCoords(offset)
                            resizeTable = ResizeTableState(
                                alias = canvasTable.alias,
                                startX = coords.x,
                                startY = coords.y,
                                startWidth = canvasTable.width,
                                startHeight = canvasTable.height,
                            )
                        },
                        modifier = Modifier.offset {
                            IntOffset(canvasTable.x.roundToInt(), canvasTable.y.roundToInt())
                        },
                    )
                }
            }
        }

        if (queryViewModel.canvasTables.isNotEmpty()) {
            Text(
                buildString {
                    append("${queryViewModel.canvasTables.size} table")
                    if (queryViewModel.canvasTables.size != 1) append('s')
                    if (queryViewModel.joins.isNotEmpty()) {
                        append(" · ${queryViewModel.joins.size} join")
                        if (queryViewModel.joins.size != 1) append('s')
                    }
                    if (queryViewModel.selectedColumns.isNotEmpty()) {
                        append(" · ${queryViewModel.selectedColumns.size} column")
                        if (queryViewModel.selectedColumns.size != 1) append('s')
                    }
                    if (queryViewModel.filterCount > 0) {
                        append(" · ${queryViewModel.filterCount} filter")
                        if (queryViewModel.filterCount != 1) append('s')
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp)
                    .background(
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                        MaterialTheme.shapes.small,
                    )
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
