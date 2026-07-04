package com.safedb.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.safedb.model.JoinSpec
import com.safedb.query.ColumnJoinPort
import com.safedb.query.JoinPortVisibility
import com.safedb.query.CanvasTableLike
import com.safedb.query.RoutedJoinEdge
import com.safedb.query.columnY
import com.safedb.query.indexedJoinTargetAt
import com.safedb.query.routeJoinEdge
import com.safedb.query.suggestedRelationships
import com.safedb.query.tableLeftX
import com.safedb.query.tableRightX
import com.safedb.ui.theme.SafeDbTheme
import com.safedb.viewmodel.CanvasTable
import com.safedb.viewmodel.QueryViewModel
import kotlin.math.roundToInt

private const val CANVAS_MIN_WIDTH_DP = 2400
private const val CANVAS_MIN_HEIGHT_DP = 1800

private data class DragJoinState(
    val sourceAlias: String,
    val sourceColumn: String,
    val mouseX: Float,
    val mouseY: Float,
)

private sealed interface CanvasGesture {
    data class TableMove(val alias: String) : CanvasGesture
    data class TableResize(val alias: String) : CanvasGesture
    data class JoinDrag(val state: DragJoinState) : CanvasGesture
}

@Composable
fun Canvas(
    queryViewModel: QueryViewModel,
    modifier: Modifier = Modifier,
) {
    var gesture by remember { mutableStateOf<CanvasGesture?>(null) }
    val horizontalScroll = rememberScrollState()
    val verticalScroll = rememberScrollState()
    val fieldScrollStates = remember { mutableStateMapOf<String, ScrollState>() }

    val aliases = queryViewModel.canvasTables.map { it.alias }.toSet()
    fieldScrollStates.keys.toList()
        .filter { it !in aliases }
        .forEach { fieldScrollStates.remove(it) }
    for (table in queryViewModel.canvasTables) {
        fieldScrollStates.getOrPut(table.alias) { ScrollState(0) }
    }

    fun canvasTableLike(table: CanvasTable): CanvasTableLike =
        CanvasTableLike(
            alias = table.alias,
            x = table.x,
            y = table.y,
            width = table.width,
            height = table.height,
            fieldScrollOffset = fieldScrollStates[table.alias]?.value?.toFloat() ?: 0f,
            tableInfo = table.tableInfo,
        )

    fun canvasTablesLike(): List<CanvasTableLike> =
        queryViewModel.canvasTables.map(::canvasTableLike)

    fun addJoin(source: DragJoinState, targetAlias: String, targetColumn: String) {
        if (targetAlias == source.sourceAlias && targetColumn == source.sourceColumn) return
        queryViewModel.addJoin(
            JoinSpec(
                leftAlias = source.sourceAlias,
                leftColumn = source.sourceColumn,
                rightAlias = targetAlias,
                rightColumn = targetColumn,
            ),
        )
    }

    fun completeJoin(source: DragJoinState) {
        val target = indexedJoinTargetAt(
            tables = canvasTablesLike(),
            x = source.mouseX,
            y = source.mouseY,
            sourceAlias = source.sourceAlias,
            sourceColumn = source.sourceColumn,
        )
        if (target != null) {
            addJoin(source, target.first, target.second)
        }
    }

    fun startJoin(canvasTable: CanvasTable, column: String) {
        val like = canvasTableLike(canvasTable)
        gesture = CanvasGesture.JoinDrag(
            DragJoinState(
                sourceAlias = canvasTable.alias,
                sourceColumn = column,
                mouseX = tableRightX(like),
                mouseY = columnY(like, column),
            ),
        )
    }

    fun endGesture() {
        (gesture as? CanvasGesture.JoinDrag)?.let { completeJoin(it.state) }
        gesture = null
    }

    val dragJoin = (gesture as? CanvasGesture.JoinDrag)?.state

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(verticalScroll)
                .horizontalScroll(horizontalScroll),
        ) {
            Box(
                modifier = Modifier
                    .size(CANVAS_MIN_WIDTH_DP.dp, CANVAS_MIN_HEIGHT_DP.dp),
            ) {
                val joinColor = SafeDbTheme.colors.actionPrimary
                val haloColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
                Canvas(modifier = Modifier.fillMaxSize()) {
                    fun drawRoutedEdge(
                        edge: RoutedJoinEdge,
                        alpha: Float,
                        dashed: Boolean,
                        endpointRadius: Float,
                    ) {
                        fun drawPort(port: ColumnJoinPort) {
                            val point = Offset(port.point.x, port.point.y)
                            drawCircle(joinColor.copy(alpha = alpha), endpointRadius, point)
                            val markerLength = 7f
                            when (port.visibility) {
                                JoinPortVisibility.HiddenAbove -> drawLine(
                                    color = joinColor.copy(alpha = alpha),
                                    start = Offset(point.x, point.y - markerLength),
                                    end = Offset(point.x, point.y - 1f),
                                    strokeWidth = 2f,
                                    cap = StrokeCap.Round,
                                )
                                JoinPortVisibility.HiddenBelow -> drawLine(
                                    color = joinColor.copy(alpha = alpha),
                                    start = Offset(point.x, point.y + 1f),
                                    end = Offset(point.x, point.y + markerLength),
                                    strokeWidth = 2f,
                                    cap = StrokeCap.Round,
                                )
                                JoinPortVisibility.Visible -> Unit
                            }
                        }

                        val points = edge.points
                        if (points.size < 2) return
                        val path = Path().apply {
                            moveTo(points.first().x, points.first().y)
                            for (point in points.drop(1)) {
                                lineTo(point.x, point.y)
                            }
                        }
                        val pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(8f, 7f)) else null
                        drawPath(
                            path = path,
                            color = haloColor,
                            style = Stroke(
                                width = 5f,
                                cap = StrokeCap.Round,
                                pathEffect = pathEffect,
                            ),
                        )
                        drawPath(
                            path = path,
                            color = joinColor.copy(alpha = alpha),
                            style = Stroke(
                                width = 2f,
                                cap = StrokeCap.Round,
                                pathEffect = pathEffect,
                            ),
                        )
                        drawPort(edge.sourcePort)
                        drawPort(edge.targetPort)
                    }

                    val tablesLike = canvasTablesLike()
                    suggestedRelationships(tablesLike, queryViewModel.joins).forEachIndexed { index, relationship ->
                        val foreign = queryViewModel.canvasTables.find { it.alias == relationship.foreignAlias }
                            ?: return@forEachIndexed
                        val referenced = queryViewModel.canvasTables.find { it.alias == relationship.referencedAlias }
                            ?: return@forEachIndexed
                        val edge = routeJoinEdge(
                            canvasTableLike(foreign),
                            relationship.foreignColumn,
                            canvasTableLike(referenced),
                            relationship.referencedColumn,
                            allTables = tablesLike,
                            laneIndex = index,
                        )
                        if (edge != null) {
                            drawRoutedEdge(edge, alpha = 0.5f, dashed = true, endpointRadius = 3f)
                        }
                    }

                    queryViewModel.joins.forEachIndexed { index, join ->
                        val left = queryViewModel.canvasTables.find { it.alias == join.leftAlias } ?: return@forEachIndexed
                        val right = queryViewModel.canvasTables.find { it.alias == join.rightAlias } ?: return@forEachIndexed
                        val edge = routeJoinEdge(
                            canvasTableLike(left),
                            join.leftColumn,
                            canvasTableLike(right),
                            join.rightColumn,
                            allTables = tablesLike,
                            laneIndex = index,
                        )
                        if (edge != null) {
                            drawRoutedEdge(edge, alpha = 1f, dashed = false, endpointRadius = 4f)
                        }
                    }

                    dragJoin?.let { state ->
                        val source = queryViewModel.canvasTables.find { it.alias == state.sourceAlias }
                        if (source != null) {
                            val like = canvasTableLike(source)
                            drawLine(
                                color = joinColor.copy(alpha = 0.6f),
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
                        fieldScrollState = fieldScrollStates.getValue(canvasTable.alias),
                        joinDragActive = dragJoin != null,
                        highlightJoinTargets = dragJoin?.let { it.sourceAlias to it.sourceColumn },
                        onStartDrag = {
                            gesture = CanvasGesture.TableMove(canvasTable.alias)
                        },
                        onDragTable = { delta ->
                            val active = gesture as? CanvasGesture.TableMove
                            if (active?.alias == canvasTable.alias) {
                                val current = queryViewModel.canvasTables.find { it.alias == canvasTable.alias }
                                if (current != null) {
                                    queryViewModel.moveTable(
                                        canvasTable.alias,
                                        current.x + delta.x,
                                        current.y + delta.y,
                                    )
                                }
                            }
                        },
                        onStartJoin = { column ->
                            startJoin(canvasTable, column)
                        },
                        onDragJoin = { delta ->
                            val active = gesture as? CanvasGesture.JoinDrag
                            if (active?.state?.sourceAlias == canvasTable.alias) {
                                gesture = CanvasGesture.JoinDrag(
                                    active.state.copy(
                                        mouseX = active.state.mouseX + delta.x,
                                        mouseY = active.state.mouseY + delta.y,
                                    ),
                                )
                            }
                        },
                        onJoinClick = { column ->
                            val source = dragJoin
                            if (source == null) {
                                startJoin(canvasTable, column)
                            } else {
                                addJoin(source, canvasTable.alias, column)
                                gesture = null
                            }
                        },
                        onJoinTargetClick = { targetAlias, targetColumn ->
                            val source = dragJoin ?: return@TableCard
                            addJoin(source, targetAlias, targetColumn)
                            gesture = null
                        },
                        onStartResize = {
                            gesture = CanvasGesture.TableResize(canvasTable.alias)
                        },
                        onResizeTable = { delta ->
                            val active = gesture as? CanvasGesture.TableResize
                            if (active?.alias == canvasTable.alias) {
                                val current = queryViewModel.canvasTables.find { it.alias == canvasTable.alias }
                                if (current != null) {
                                    queryViewModel.resizeTable(
                                        canvasTable.alias,
                                        current.width + delta.x,
                                        current.height + delta.y,
                                    )
                                }
                            }
                        },
                        onEndGesture = {
                            endGesture()
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
