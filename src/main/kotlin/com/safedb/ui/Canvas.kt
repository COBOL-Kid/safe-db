package com.safedb.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
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
import com.safedb.ui.components.CanvasZoomControls
import com.safedb.ui.theme.SafeDbTheme
import com.safedb.viewmodel.BUILDER_CANVAS_MAX_ZOOM
import com.safedb.viewmodel.BUILDER_CANVAS_MIN_ZOOM
import com.safedb.viewmodel.BUILDER_CANVAS_ZOOM_STEP
import com.safedb.viewmodel.CanvasTable
import com.safedb.viewmodel.QueryViewModel
import com.safedb.viewmodel.SchemaMapAxisScrollState
import com.safedb.viewmodel.matchesJoin
import com.safedb.viewmodel.schemaMapAxisScrollState
import com.safedb.viewmodel.schemaMapConstrainedPan
import com.safedb.viewmodel.schemaMapPanForScrollEvent
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
    var viewport by remember { mutableStateOf(IntSize.Zero) }
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

    val routedEdges = clickableJoinLines().map(ClickableJoinLine::edge)
    val contentBounds = builderCanvasContentBounds(canvasTablesLike(), routedEdges)
    val canvasPaddingPx = with(density) { 36.dp.toPx() }
    val minimumWidthPx = with(density) { CANVAS_MIN_WIDTH_DP.dp.toPx() }
    val minimumHeightPx = with(density) { CANVAS_MIN_HEIGHT_DP.dp.toPx() }
    val canvasWidthPx = maxOf(minimumWidthPx, contentBounds.right + canvasPaddingPx)
    val canvasHeightPx = maxOf(minimumHeightPx, contentBounds.bottom + canvasPaddingPx)
    val viewportSize =
        Size(
            viewport.width.toFloat(),
            (viewport.height.toFloat() - contentTopInsetPx).coerceAtLeast(0f),
        )
    val viewportState = queryViewModel.canvasViewport
    val horizontalScroll =
        schemaMapAxisScrollState(
            contentStart = 0f,
            contentEnd = canvasWidthPx,
            viewportSize = viewportSize.width,
            zoom = viewportState.zoom,
            pan = viewportState.pan.x,
            padding = 0f,
        )
    val verticalScroll =
        schemaMapAxisScrollState(
            contentStart = 0f,
            contentEnd = canvasHeightPx,
            viewportSize = viewportSize.height,
            zoom = viewportState.zoom,
            pan = viewportState.pan.y,
            padding = 0f,
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
                schemaMapConstrainedPan(viewportState.pan, horizontalScroll, verticalScroll)
            if (constrained != viewportState.pan) viewportState.updatePan(constrained)
        }
    }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .clipToBounds()
                .background(SafeDbTheme.colors.workspaceCanvas)
                .onSizeChanged { viewport = it }
                .onPointerEvent(PointerEventType.Scroll) { event ->
                    event.changes.firstOrNull()?.let { change ->
                        if (
                            !change.isConsumed &&
                                (event.keyboardModifiers.isCtrlPressed ||
                                    event.keyboardModifiers.isMetaPressed)
                        ) {
                            val delta =
                                if (change.scrollDelta.y < 0f) BUILDER_CANVAS_ZOOM_STEP
                                else -BUILDER_CANVAS_ZOOM_STEP
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
                                schemaMapPanForScrollEvent(
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
                .pointerInput(
                    canvasWidthPx,
                    canvasHeightPx,
                    viewportSize,
                    viewportState.zoom,
                ) {
                    detectDragGestures { change, dragAmount ->
                        if (!change.isConsumed) {
                            change.consume()
                            viewportState.updatePan(
                                schemaMapConstrainedPan(
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
                        translationX = viewportState.pan.x
                        translationY = canvasDisplayY(viewportState.pan.y, contentTopInsetPx)
                        transformOrigin = TransformOrigin(0f, 0f)
                    }
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
                        style = Stroke(width = 5f, cap = StrokeCap.Round, pathEffect = pathEffect),
                    )
                    drawPath(
                        path = path,
                        color = joinColor.copy(alpha = alpha),
                        style = Stroke(width = 2f, cap = StrokeCap.Round, pathEffect = pathEffect),
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
                            queryViewModel.canvasTables.find { it.alias == suggested.rightAlias }
                                ?: return@forEachIndexed
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
                    val source = queryViewModel.canvasTables.find { it.alias == state.sourceAlias }
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
                                queryViewModel.canvasTables.find { it.alias == canvasTable.alias }
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
                                queryViewModel.canvasTables.find { it.alias == canvasTable.alias }
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
                                canvasTable.y.roundToInt(),
                            )
                        },
                )
            }
        }

        HorizontalScrollbar(
            adapter = horizontalScrollbarAdapter,
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(end = 8.dp),
        )
        VerticalScrollbar(
            adapter = verticalScrollbarAdapter,
            modifier =
                Modifier.align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .padding(top = contentTopInset, bottom = 8.dp),
        )

        CanvasZoomControls(
            zoom = viewportState.zoom,
            minZoom = BUILDER_CANVAS_MIN_ZOOM,
            maxZoom = BUILDER_CANVAS_MAX_ZOOM,
            fitDescription = "Fit query to screen",
            resetDescription = "Reset view",
            onZoomOut = {
                viewportState.setZoom(
                    viewportState.zoom - BUILDER_CANVAS_ZOOM_STEP,
                    Offset(viewport.width / 2f, viewportSize.height / 2f),
                )
            },
            onZoomIn = {
                viewportState.setZoom(
                    viewportState.zoom + BUILDER_CANVAS_ZOOM_STEP,
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
            modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
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

private class BuilderCanvasScrollbarAdapter(
    private val state: () -> SchemaMapAxisScrollState,
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
