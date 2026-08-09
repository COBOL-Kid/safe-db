package com.safedb.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.v2.ScrollbarAdapter
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.safedb.canvas.CanvasPoint
import com.safedb.canvas.CanvasTableLike
import com.safedb.canvas.ColumnJoinPort
import com.safedb.canvas.JOIN_LINE_HIT_TOLERANCE
import com.safedb.canvas.JoinPortSide
import com.safedb.canvas.JoinPortVisibility
import com.safedb.canvas.RoutedJoinEdge
import com.safedb.canvas.columnJoinPort
import com.safedb.canvas.columnY
import com.safedb.canvas.indexedJoinTargetAt
import com.safedb.canvas.routeJoinEdge
import com.safedb.canvas.routedEdgeContainsPoint
import com.safedb.canvas.suggestedRelationships
import com.safedb.canvas.tableBounds
import com.safedb.canvas.tableRightX
import com.safedb.model.JoinSpec
import com.safedb.ui.components.CanvasZoomControls
import com.safedb.ui.theme.SafeDbTheme
import com.safedb.viewmodel.CANVAS_MAX_ZOOM
import com.safedb.viewmodel.CANVAS_MIN_ZOOM
import com.safedb.viewmodel.CANVAS_ZOOM_STEP
import com.safedb.viewmodel.CanvasAxisScrollState
import com.safedb.viewmodel.CanvasTable
import com.safedb.viewmodel.QueryViewModel
import com.safedb.viewmodel.canvasAxisScrollState
import com.safedb.viewmodel.canvasConstrainedPan
import com.safedb.viewmodel.canvasPanForScrollEvent
import com.safedb.viewmodel.matchesJoin
import kotlin.math.roundToInt

private const val CANVAS_MIN_WIDTH_DP = 2400
private const val CANVAS_MIN_HEIGHT_DP = 1800
private const val CANVAS_FOOTER_HEIGHT_DP = 62

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
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var horizontalScrollbarBounds by remember { mutableStateOf<Rect?>(null) }
    var verticalScrollbarBounds by remember { mutableStateOf<Rect?>(null) }
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
                y = table.y,
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

    fun routedJoinLines(): List<ClickableJoinLine> {
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

    val joinLines = routedJoinLines()

    fun joinLineAt(offset: Offset): ClickableJoinLine? {
        if (gesture != null) return null

        val tablesLike = canvasTablesLike()
        val point = CanvasPoint(offset.x, offset.y)
        if (tablesLike.any { tableBounds(it).contains(point) }) return null

        val tolerance = JOIN_LINE_HIT_TOLERANCE * density.density
        val existingHit =
            joinLines.filterIsInstance<ClickableJoinLine.Existing>().lastOrNull {
                routedEdgeContainsPoint(it.edge, offset.x, offset.y, tolerance)
            }
        if (existingHit != null) return existingHit

        return joinLines.filterIsInstance<ClickableJoinLine.Suggested>().lastOrNull {
            routedEdgeContainsPoint(it.edge, offset.x, offset.y, tolerance)
        }
    }

    // Hit testing must go through a lambda literal updated on every recomposition. A function
    // reference (::fun) here gets memoized by the Compose compiler with the scope captured at
    // first composition, so it hit-tests against stale join lines once tables move.
    val currentJoinLineHitTester by
        rememberUpdatedState<(Offset) -> ClickableJoinLine?> { offset -> joinLineAt(offset) }
    val currentCanvasTableHitHandler by
        rememberUpdatedState<(CanvasPoint) -> Boolean> { point ->
            canvasTablesLike().any { tableBounds(it).contains(point) }
        }
    val currentScrollbarHitTester by
        rememberUpdatedState<(Offset) -> Boolean> { position ->
            horizontalScrollbarBounds?.contains(position) == true ||
                verticalScrollbarBounds?.contains(position) == true
        }

    val routedEdges = joinLines.map(ClickableJoinLine::edge)
    val contentBounds = builderCanvasContentBounds(canvasTablesLike(), routedEdges)
    val canvasPaddingPx = with(density) { 36.dp.toPx() }
    val minimumWidthPx = with(density) { CANVAS_MIN_WIDTH_DP.dp.toPx() }
    val minimumHeightPx = with(density) { CANVAS_MIN_HEIGHT_DP.dp.toPx() }
    val workspaceBounds =
        builderCanvasWorkspaceBounds(
            contentBounds = contentBounds,
            minimumSize = Size(minimumWidthPx, minimumHeightPx),
            padding = canvasPaddingPx,
        )
    val renderOrigin = workspaceBounds.topLeft
    val canvasWidthPx = workspaceBounds.width
    val canvasHeightPx = workspaceBounds.height
    val viewportSize =
        Size(
            viewport.width.toFloat(),
            (viewport.height.toFloat() - contentTopInsetPx).coerceAtLeast(0f),
        )
    val viewportState = queryViewModel.canvasViewport
    val horizontalScroll =
        canvasAxisScrollState(
            contentStart = workspaceBounds.left,
            contentEnd = workspaceBounds.right,
            viewportSize = viewportSize.width,
            zoom = viewportState.zoom,
            pan = viewportState.pan.x,
            padding = viewportSize.width / 2f,
        )
    val verticalScroll =
        canvasAxisScrollState(
            contentStart = workspaceBounds.top,
            contentEnd = workspaceBounds.bottom,
            viewportSize = viewportSize.height,
            zoom = viewportState.zoom,
            pan = viewportState.pan.y,
            padding = viewportSize.height / 2f,
        )
    val currentHorizontalScroll = rememberUpdatedState(horizontalScroll)
    val currentVerticalScroll = rememberUpdatedState(verticalScroll)
    val horizontalScrollbarAdapter =
        remember(viewportState) {
            BuilderCanvasScrollbarAdapter(
                state = { currentHorizontalScroll.value },
                onScrollTo = { target ->
                    val axis = currentHorizontalScroll.value
                    viewportState.updatePan(
                        Offset(axis.panForScrollOffset(target), viewportState.pan.y)
                    )
                },
            )
        }
    val verticalScrollbarAdapter =
        remember(viewportState) {
            BuilderCanvasScrollbarAdapter(
                state = { currentVerticalScroll.value },
                onScrollTo = { target ->
                    val axis = currentVerticalScroll.value
                    viewportState.updatePan(
                        Offset(viewportState.pan.x, axis.panForScrollOffset(target))
                    )
                },
            )
        }

    LaunchedEffect(horizontalScroll, verticalScroll, viewport) {
        if (viewport.width > 0 && viewport.height > 0) {
            val constrained =
                canvasConstrainedPan(viewportState.pan, horizontalScroll, verticalScroll)
            if (constrained != viewportState.pan) viewportState.updatePan(constrained)
        }
    }

    Column(modifier = modifier.fillMaxSize().background(SafeDbTheme.colors.workspaceCanvas)) {
        Box(
            modifier =
                Modifier.weight(1f)
                    .fillMaxWidth()
                    .clipToBounds()
                    .onSizeChanged { viewport = it }
                    .pointerHoverIcon(
                        if (joinLineHovered) PointerIcon.Hand else PointerIcon.Default
                    )
                    .onPointerEvent(PointerEventType.Move) { event ->
                        event.changes.firstOrNull()?.position?.let { position ->
                            val point =
                                canvasPointForViewportPosition(
                                    position = position,
                                    zoom = viewportState.zoom,
                                    pan = viewportState.pan,
                                    contentTopInset = contentTopInsetPx,
                                )
                            joinLineHovered =
                                currentJoinLineHitTester(Offset(point.x, point.y)) != null
                        }
                    }
                    .onPointerEvent(PointerEventType.Exit) { joinLineHovered = false }
                    .onPointerEvent(PointerEventType.Scroll) { event ->
                        event.changes.firstOrNull()?.let { change ->
                            if (
                                !change.isConsumed &&
                                    (event.keyboardModifiers.isCtrlPressed ||
                                        event.keyboardModifiers.isMetaPressed)
                            ) {
                                val delta =
                                    if (change.scrollDelta.y < 0f) CANVAS_ZOOM_STEP
                                    else -CANVAS_ZOOM_STEP
                                viewportState.setZoom(
                                    viewportState.zoom + delta,
                                    Offset(
                                        change.position.x,
                                        (change.position.y - contentTopInsetPx).coerceAtLeast(0f),
                                    ),
                                )
                                change.consume()
                            } else {
                                val target =
                                    canvasPanForScrollEvent(
                                        horizontal = horizontalScroll,
                                        vertical = verticalScroll,
                                        delta = change.scrollDelta,
                                        shiftPressed = event.keyboardModifiers.isShiftPressed,
                                        consumed = change.isConsumed,
                                    )
                                if (target != null && target != viewportState.pan) {
                                    viewportState.updatePan(target)
                                    change.consume()
                                }
                            }
                        }
                    }
                    .pointerInput(contentTopInsetPx, viewportState.zoom) {
                        awaitEachGesture {
                            val down =
                                awaitFirstDown(
                                    requireUnconsumed = false,
                                    pass = PointerEventPass.Initial,
                                )
                            if (
                                !currentEvent.buttons.isPrimaryPressed ||
                                    currentScrollbarHitTester(down.position)
                            ) {
                                return@awaitEachGesture
                            }
                            val worldPosition =
                                canvasPointForViewportPosition(
                                    position = down.position,
                                    zoom = viewportState.zoom,
                                    pan = viewportState.pan,
                                    contentTopInset = contentTopInsetPx,
                                )
                            val pressedJoinLine =
                                currentJoinLineHitTester(Offset(worldPosition.x, worldPosition.y))
                            if (pressedJoinLine != null) {
                                down.consume()
                                var released = false
                                while (!released) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val change =
                                        event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (change.changedToUpIgnoreConsumed() || !change.pressed) {
                                        released = true
                                    }
                                    event.changes.forEach { it.consume() }
                                }
                                // Act on the line captured at press time. Re-hit-testing on
                                // release misses when the cursor drifts off the thin line or
                                // when the routed edges changed between press and release.
                                when (pressedJoinLine) {
                                    is ClickableJoinLine.Existing ->
                                        queryViewModel.removeJoin(pressedJoinLine.join)
                                    is ClickableJoinLine.Suggested ->
                                        pressedJoinLine.joins.forEach(queryViewModel::addJoin)
                                }
                            }
                        }
                    }
                    .pointerInput(
                        canvasWidthPx,
                        canvasHeightPx,
                        viewportSize,
                        viewportState.zoom,
                    ) {
                        var panEnabled = false
                        detectDragGestures(
                            onDragStart = { position ->
                                val point =
                                    canvasPointForViewportPosition(
                                        position = position,
                                        zoom = viewportState.zoom,
                                        pan = viewportState.pan,
                                        contentTopInset = contentTopInsetPx,
                                    )
                                panEnabled =
                                    currentJoinLineHitTester(Offset(point.x, point.y)) == null &&
                                        !currentCanvasTableHitHandler(point)
                            },
                            onDragEnd = { panEnabled = false },
                            onDragCancel = { panEnabled = false },
                        ) { change, dragAmount ->
                            if (panEnabled) {
                                change.consume()
                                viewportState.updatePan(
                                    canvasConstrainedPan(
                                        viewportState.pan + dragAmount,
                                        horizontalScroll,
                                        verticalScroll,
                                    )
                                )
                            }
                        }
                    }
        ) {
            Box(
                modifier =
                    Modifier.wrapContentSize(Alignment.TopStart, unbounded = true)
                        .requiredSize(
                            (canvasWidthPx / density.density).dp,
                            (canvasHeightPx / density.density).dp,
                        )
                        .graphicsLayer {
                            scaleX = viewportState.zoom
                            scaleY = viewportState.zoom
                            translationX = viewportState.pan.x + renderOrigin.x * viewportState.zoom
                            translationY =
                                canvasDisplayY(
                                    viewportState.pan.y + renderOrigin.y * viewportState.zoom,
                                    contentTopInsetPx,
                                )
                            transformOrigin = TransformOrigin(0f, 0f)
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

                    joinLines.filterIsInstance<ClickableJoinLine.Suggested>().forEach { line ->
                        drawRoutedEdge(
                            line.edge.translated(-renderOrigin.x, -renderOrigin.y),
                            alpha = 0.5f,
                            dashed = true,
                            endpointRadius = 3f,
                        )
                    }

                    joinLines.filterIsInstance<ClickableJoinLine.Existing>().forEach { line ->
                        drawRoutedEdge(
                            line.edge.translated(-renderOrigin.x, -renderOrigin.y),
                            alpha = 1f,
                            dashed = false,
                            endpointRadius = 4f,
                        )
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
                                        x = (start?.x ?: tableRightX(like)) - renderOrigin.x,
                                        y =
                                            (start?.y ?: columnY(like, state.sourceColumn)) -
                                                renderOrigin.y,
                                    ),
                                end =
                                    Offset(
                                        state.mouseX - renderOrigin.x,
                                        state.mouseY - renderOrigin.y,
                                    ),
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
                                    (canvasTable.x - renderOrigin.x).roundToInt(),
                                    (canvasTable.y - renderOrigin.y).roundToInt(),
                                )
                            },
                    )
                }
            }

            HorizontalScrollbar(
                adapter = horizontalScrollbarAdapter,
                modifier =
                    Modifier.align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(end = 8.dp)
                        .onGloballyPositioned { horizontalScrollbarBounds = it.boundsInParent() },
            )
            VerticalScrollbar(
                adapter = verticalScrollbarAdapter,
                modifier =
                    Modifier.align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .padding(top = contentTopInset, bottom = 8.dp)
                        .onGloballyPositioned { verticalScrollbarBounds = it.boundsInParent() },
            )
        }

        Box(
            modifier =
                Modifier.fillMaxWidth()
                    .height(CANVAS_FOOTER_HEIGHT_DP.dp)
                    .background(SafeDbTheme.colors.workspaceCanvas)
        ) {
            CanvasZoomControls(
                zoom = viewportState.zoom,
                minZoom = CANVAS_MIN_ZOOM,
                maxZoom = CANVAS_MAX_ZOOM,
                fitDescription = "Fit query to screen",
                resetDescription = "Reset view",
                onZoomOut = {
                    viewportState.setZoom(
                        viewportState.zoom - CANVAS_ZOOM_STEP,
                        Offset(viewport.width / 2f, viewportSize.height / 2f),
                    )
                },
                onZoomIn = {
                    viewportState.setZoom(
                        viewportState.zoom + CANVAS_ZOOM_STEP,
                        Offset(viewport.width / 2f, viewportSize.height / 2f),
                    )
                },
                onFit = {
                    viewportState.fit(
                        contentBounds = contentBounds,
                        viewport = viewportSize,
                    )
                },
                onReset = viewportState::reset,
                modifier = Modifier.align(Alignment.CenterEnd).padding(horizontal = 12.dp),
            )

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
                        Modifier.align(Alignment.CenterStart)
                            .padding(horizontal = 12.dp)
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
}

internal fun canvasDisplayY(tableY: Float, contentTopInsetPx: Float): Float =
    tableY + contentTopInsetPx

internal fun canvasPointForViewportPosition(
    position: Offset,
    zoom: Float,
    pan: Offset,
    contentTopInset: Float,
): CanvasPoint {
    val safeZoom = zoom.coerceAtLeast(CANVAS_MIN_ZOOM)
    return CanvasPoint(
        x = (position.x - pan.x) / safeZoom,
        y = (position.y - pan.y - contentTopInset) / safeZoom,
    )
}

internal fun builderCanvasContentBounds(
    tables: List<CanvasTableLike>,
    edges: List<RoutedJoinEdge>,
): Rect {
    val tableRects = tables.map(::tableBounds)
    val edgePoints = edges.flatMap(RoutedJoinEdge::points)
    val left =
        minOf(
            tableRects.minOfOrNull { it.left } ?: 0f,
            edgePoints.minOfOrNull { it.x } ?: Float.POSITIVE_INFINITY,
        )
    val top =
        minOf(
            tableRects.minOfOrNull { it.top } ?: 0f,
            edgePoints.minOfOrNull { it.y } ?: Float.POSITIVE_INFINITY,
        )
    val right =
        maxOf(
            tableRects.maxOfOrNull { it.right } ?: 1f,
            edgePoints.maxOfOrNull { it.x } ?: Float.NEGATIVE_INFINITY,
        )
    val bottom =
        maxOf(
            tableRects.maxOfOrNull { it.bottom } ?: 1f,
            edgePoints.maxOfOrNull { it.y } ?: Float.NEGATIVE_INFINITY,
        )
    return Rect(left, top, right, bottom)
}

internal fun builderCanvasWorkspaceBounds(
    contentBounds: Rect,
    minimumSize: Size,
    padding: Float,
): Rect =
    Rect(
        left = minOf(0f, contentBounds.left - padding),
        top = minOf(0f, contentBounds.top - padding),
        right = maxOf(minimumSize.width, contentBounds.right + padding),
        bottom = maxOf(minimumSize.height, contentBounds.bottom + padding),
    )

private fun RoutedJoinEdge.translated(deltaX: Float, deltaY: Float): RoutedJoinEdge =
    copy(
        points = points.map { point -> CanvasPoint(point.x + deltaX, point.y + deltaY) },
        sourcePort =
            sourcePort.copy(
                point = CanvasPoint(sourcePort.point.x + deltaX, sourcePort.point.y + deltaY)
            ),
        targetPort =
            targetPort.copy(
                point = CanvasPoint(targetPort.point.x + deltaX, targetPort.point.y + deltaY)
            ),
    )

private class BuilderCanvasScrollbarAdapter(
    private val state: () -> CanvasAxisScrollState,
    private val onScrollTo: (Float) -> Unit,
) : ScrollbarAdapter {
    override val scrollOffset: Double
        get() = state().scrollOffset.toDouble()

    override val contentSize: Double
        get() = state().contentSize.toDouble()

    override val viewportSize: Double
        get() = state().viewportSize.toDouble()

    override suspend fun scrollTo(scrollOffset: Double) {
        onScrollTo(scrollOffset.toFloat())
    }
}
