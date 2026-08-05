package com.safedb.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.safedb.model.JoinSpec
import com.safedb.query.CanvasPoint
import com.safedb.query.CanvasTableLike
import com.safedb.query.ColumnJoinPort
import com.safedb.query.JOIN_LINE_HIT_TOLERANCE
import com.safedb.query.JoinPortSide
import com.safedb.query.JoinPortVisibility
import com.safedb.query.RoutedJoinEdge
import com.safedb.query.columnJoinPort
import com.safedb.query.columnY
import com.safedb.query.indexedJoinTargetAt
import com.safedb.query.routeJoinEdge
import com.safedb.query.routedEdgeContainsPoint
import com.safedb.query.suggestedRelationships
import com.safedb.query.tableBounds
import com.safedb.query.tableRightX
import com.safedb.ui.theme.SafeDbTheme
import com.safedb.viewmodel.CanvasTable
import com.safedb.viewmodel.QueryViewModel
import com.safedb.viewmodel.matchesJoin
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

private sealed interface ClickableJoinLine {
    val edge: RoutedJoinEdge

    data class Existing(val join: JoinSpec, override val edge: RoutedJoinEdge) : ClickableJoinLine

    data class Suggested(val joins: List<JoinSpec>, override val edge: RoutedJoinEdge) :
        ClickableJoinLine
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun Canvas(
    queryViewModel: QueryViewModel,
    contentTopInset: Dp = 0.dp,
    modifier: Modifier = Modifier,
) {
    var gesture by remember { mutableStateOf<CanvasGesture?>(null) }
    var joinLineHovered by remember { mutableStateOf(false) }
    val horizontalScroll = rememberScrollState()
    val verticalScroll = rememberScrollState()
    val fieldScrollStates = remember { mutableStateMapOf<String, ScrollState>() }
    val density = LocalDensity.current
    val contentTopInsetPx = with(density) { contentTopInset.toPx() }

    val aliases = queryViewModel.canvasTables.map { it.alias }.toSet()
    fieldScrollStates.keys
        .toList()
        .filter { it !in aliases }
        .forEach { fieldScrollStates.remove(it) }
    for (table in queryViewModel.canvasTables) {
        fieldScrollStates.getOrPut(table.alias) { ScrollState(0) }
    }

    fun canvasTableLike(table: CanvasTable): CanvasTableLike =
        with(density) {
            CanvasTableLike(
                alias = table.alias,
                x = table.x,
                y = canvasDisplayY(table.y, contentTopInsetPx),
                width = table.width.dp.toPx(),
                height = table.height.dp.toPx(),
                fieldScrollOffset = fieldScrollStates[table.alias]?.value?.toFloat() ?: 0f,
                layoutScale = this.density,
                tableInfo = table.tableInfo,
            )
        }

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
            )
        )
    }

    fun completeJoin(source: DragJoinState) {
        val target =
            indexedJoinTargetAt(
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
        val port = columnJoinPort(like, column, JoinPortSide.Right)
        gesture =
            CanvasGesture.JoinDrag(
                DragJoinState(
                    sourceAlias = canvasTable.alias,
                    sourceColumn = column,
                    mouseX = tableRightX(like),
                    mouseY = port?.point?.y ?: columnY(like, column),
                )
            )
    }

    fun endGesture() {
        (gesture as? CanvasGesture.JoinDrag)?.let { completeJoin(it.state) }
        gesture = null
    }

    val dragJoin = (gesture as? CanvasGesture.JoinDrag)?.state

    fun clickableJoinLines(): List<ClickableJoinLine> {
        val tablesLike = canvasTablesLike()
        val existingLines =
            queryViewModel.joins.mapIndexedNotNull { index, join ->
                val left =
                    queryViewModel.canvasTables.find { it.alias == join.leftAlias }
                        ?: return@mapIndexedNotNull null
                val right =
                    queryViewModel.canvasTables.find { it.alias == join.rightAlias }
                        ?: return@mapIndexedNotNull null
                routeJoinEdge(
                        canvasTableLike(left),
                        join.leftColumn,
                        canvasTableLike(right),
                        join.rightColumn,
                        allTables = tablesLike,
                        laneIndex = index,
                    )
                    ?.let { edge -> ClickableJoinLine.Existing(join, edge) }
            }
        val relationships = suggestedRelationships(tablesLike, queryViewModel.joins)
        val suggestedLines =
            relationships
                .flatMap { relationship ->
                    relationship.joins
                        .filterNot { suggested ->
                            queryViewModel.joins.any { it.matchesJoin(suggested) }
                        }
                        .map { relationship to it }
                }
                .mapIndexedNotNull { index, (relationship, suggested) ->
                    val foreign =
                        queryViewModel.canvasTables.find { it.alias == suggested.leftAlias }
                            ?: return@mapIndexedNotNull null
                    val referenced =
                        queryViewModel.canvasTables.find { it.alias == suggested.rightAlias }
                            ?: return@mapIndexedNotNull null
                    routeJoinEdge(
                            canvasTableLike(foreign),
                            suggested.leftColumn,
                            canvasTableLike(referenced),
                            suggested.rightColumn,
                            allTables = tablesLike,
                            laneIndex = index,
                        )
                        ?.let { edge -> ClickableJoinLine.Suggested(relationship.joins, edge) }
                }
        return existingLines + suggestedLines
    }

    fun joinLineAt(offset: Offset): ClickableJoinLine? {
        if (gesture != null) return null

        val tablesLike = canvasTablesLike()
        val point = CanvasPoint(offset.x, offset.y)
        if (tablesLike.any { tableBounds(it).contains(point) }) return null

        val tolerance = JOIN_LINE_HIT_TOLERANCE * density.density
        val lines = clickableJoinLines()
        val existingHit =
            lines.filterIsInstance<ClickableJoinLine.Existing>().lastOrNull {
                routedEdgeContainsPoint(it.edge, offset.x, offset.y, tolerance)
            }
        if (existingHit != null) return existingHit

        return lines.filterIsInstance<ClickableJoinLine.Suggested>().lastOrNull {
            routedEdgeContainsPoint(it.edge, offset.x, offset.y, tolerance)
        }
    }

    fun handleJoinLineClick(offset: Offset): Boolean {
        val suggestedHit =
            when (val hit = joinLineAt(offset)) {
                is ClickableJoinLine.Existing -> {
                    queryViewModel.removeJoin(hit.join)
                    return true
                }
                is ClickableJoinLine.Suggested -> hit
                null -> return false
            }

        suggestedHit.joins.forEach(queryViewModel::addJoin)
        return true
    }

    val currentJoinLineClickHandler by
        rememberUpdatedState<(Offset) -> Boolean>(::handleJoinLineClick)
    val currentJoinLineHoverHandler by
        rememberUpdatedState<(Offset) -> Boolean> { offset -> joinLineAt(offset) != null }

    Box(modifier = modifier.fillMaxSize().background(SafeDbTheme.colors.workspaceCanvas)) {
        Box(
            modifier =
                Modifier.fillMaxSize()
                    .verticalScroll(verticalScroll)
                    .horizontalScroll(horizontalScroll)
        ) {
            Box(
                modifier =
                    Modifier.size(CANVAS_MIN_WIDTH_DP.dp, CANVAS_MIN_HEIGHT_DP.dp)
                        .pointerHoverIcon(
                            if (joinLineHovered) PointerIcon.Hand else PointerIcon.Default
                        )
                        .onPointerEvent(PointerEventType.Move) { event ->
                            event.changes.firstOrNull()?.position?.let { offset ->
                                joinLineHovered = currentJoinLineHoverHandler(offset)
                            }
                        }
                        .onPointerEvent(PointerEventType.Exit) { joinLineHovered = false }
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                val up = waitForUpOrCancellation()
                                if (up != null && currentJoinLineClickHandler(up.position)) {
                                    up.consume()
                                }
                            }
                        }
            ) {
                val joinColor = SafeDbTheme.colors.actionPrimary
                val haloColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
                Canvas(modifier = Modifier.fillMaxSize().zIndex(1f)) {
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
                                JoinPortVisibility.HiddenAbove ->
                                    drawLine(
                                        color = joinColor.copy(alpha = alpha),
                                        start = Offset(point.x, point.y - markerLength),
                                        end = Offset(point.x, point.y - 1f),
                                        strokeWidth = 2f,
                                        cap = StrokeCap.Round,
                                    )
                                JoinPortVisibility.HiddenBelow ->
                                    drawLine(
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
                        val path =
                            Path().apply {
                                moveTo(points.first().x, points.first().y)
                                for (point in points.drop(1)) {
                                    lineTo(point.x, point.y)
                                }
                            }
                        val pathEffect =
                            if (dashed) PathEffect.dashPathEffect(floatArrayOf(8f, 7f)) else null
                        drawPath(
                            path = path,
                            color = haloColor,
                            style =
                                Stroke(width = 5f, cap = StrokeCap.Round, pathEffect = pathEffect),
                        )
                        drawPath(
                            path = path,
                            color = joinColor.copy(alpha = alpha),
                            style =
                                Stroke(width = 2f, cap = StrokeCap.Round, pathEffect = pathEffect),
                        )
                        drawPort(edge.sourcePort)
                        drawPort(edge.targetPort)
                    }

                    val tablesLike = canvasTablesLike()
                    suggestedRelationships(tablesLike, queryViewModel.joins)
                        .flatMap { relationship ->
                            relationship.joins.filterNot { suggested ->
                                queryViewModel.joins.any { it.matchesJoin(suggested) }
                            }
                        }
                        .forEachIndexed { index, suggested ->
                            val foreign =
                                queryViewModel.canvasTables.find { it.alias == suggested.leftAlias }
                                    ?: return@forEachIndexed
                            val referenced =
                                queryViewModel.canvasTables.find {
                                    it.alias == suggested.rightAlias
                                } ?: return@forEachIndexed
                            val edge =
                                routeJoinEdge(
                                    canvasTableLike(foreign),
                                    suggested.leftColumn,
                                    canvasTableLike(referenced),
                                    suggested.rightColumn,
                                    allTables = tablesLike,
                                    laneIndex = index,
                                )
                            if (edge != null) {
                                drawRoutedEdge(
                                    edge,
                                    alpha = 0.5f,
                                    dashed = true,
                                    endpointRadius = 3f,
                                )
                            }
                        }

                    queryViewModel.joins.forEachIndexed { index, join ->
                        val left =
                            queryViewModel.canvasTables.find { it.alias == join.leftAlias }
                                ?: return@forEachIndexed
                        val right =
                            queryViewModel.canvasTables.find { it.alias == join.rightAlias }
                                ?: return@forEachIndexed
                        val edge =
                            routeJoinEdge(
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
                        val source =
                            queryViewModel.canvasTables.find { it.alias == state.sourceAlias }
                        if (source != null) {
                            val like = canvasTableLike(source)
                            val port = columnJoinPort(like, state.sourceColumn, JoinPortSide.Right)
                            val start = port?.point
                            drawLine(
                                color = joinColor.copy(alpha = 0.6f),
                                start =
                                    Offset(
                                        x = start?.x ?: tableRightX(like),
                                        y = start?.y ?: columnY(like, state.sourceColumn),
                                    ),
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
                        onStartDrag = { gesture = CanvasGesture.TableMove(canvasTable.alias) },
                        onDragTable = { delta ->
                            val active = gesture as? CanvasGesture.TableMove
                            if (active?.alias == canvasTable.alias) {
                                val current =
                                    queryViewModel.canvasTables.find {
                                        it.alias == canvasTable.alias
                                    }
                                if (current != null) {
                                    queryViewModel.moveTable(
                                        canvasTable.alias,
                                        current.x + delta.x,
                                        current.y + delta.y,
                                    )
                                }
                            }
                        },
                        onStartJoin = { column -> startJoin(canvasTable, column) },
                        onDragJoin = { delta ->
                            val active = gesture as? CanvasGesture.JoinDrag
                            if (active?.state?.sourceAlias == canvasTable.alias) {
                                gesture =
                                    CanvasGesture.JoinDrag(
                                        active.state.copy(
                                            mouseX = active.state.mouseX + delta.x,
                                            mouseY = active.state.mouseY + delta.y,
                                        )
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
                        onStartResize = { gesture = CanvasGesture.TableResize(canvasTable.alias) },
                        onResizeTable = { delta ->
                            val active = gesture as? CanvasGesture.TableResize
                            if (active?.alias == canvasTable.alias) {
                                val current =
                                    queryViewModel.canvasTables.find {
                                        it.alias == canvasTable.alias
                                    }
                                if (current != null) {
                                    queryViewModel.resizeTable(
                                        canvasTable.alias,
                                        current.width + delta.x,
                                        current.height + delta.y,
                                    )
                                }
                            }
                        },
                        onEndGesture = { endGesture() },
                        modifier =
                            Modifier.offset {
                                IntOffset(
                                    canvasTable.x.roundToInt(),
                                    canvasDisplayY(canvasTable.y, contentTopInsetPx).roundToInt(),
                                )
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
                modifier =
                    Modifier.align(Alignment.BottomStart)
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

internal fun canvasDisplayY(tableY: Float, contentTopInsetPx: Float): Float =
    tableY + contentTopInsetPx
