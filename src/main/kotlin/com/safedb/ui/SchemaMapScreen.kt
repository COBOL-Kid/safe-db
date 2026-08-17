package com.safedb.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.safedb.SchemaSelectionIntent
import com.safedb.model.ConnectionDef
import com.safedb.model.IndexInfo
import com.safedb.schema.MAP_CARD_FOOTER
import com.safedb.schema.MAP_CARD_HEADER
import com.safedb.schema.MAP_CARD_ROW
import com.safedb.schema.SCHEMA_MAP_RENDER_PADDING_DP
import com.safedb.schema.SCHEMA_MAP_SCROLLBAR_CORNER
import com.safedb.schema.SchemaMapCardinality
import com.safedb.schema.SchemaMapColumnMarker
import com.safedb.schema.SchemaMapColumnMarkerKind
import com.safedb.schema.SchemaMapGraph
import com.safedb.schema.SchemaMapNode
import com.safedb.schema.SchemaMapOptionality
import com.safedb.schema.SchemaMapPoint
import com.safedb.schema.SchemaMapRelationship
import com.safedb.schema.SchemaMapRelationshipGeometry
import com.safedb.schema.SchemaMapSearchResult
import com.safedb.schema.SchemaMapSize
import com.safedb.schema.buildSchemaMapGraph
import com.safedb.schema.layoutSchemaMap
import com.safedb.schema.orZero
import com.safedb.schema.schemaMapContentBounds
import com.safedb.schema.schemaMapNodeBounds
import com.safedb.schema.schemaMapRelationshipGeometries
import com.safedb.schema.searchSchemaMap
import com.safedb.schema.toPxOffset
import com.safedb.schema.translated
import com.safedb.ui.components.BannerKind
import com.safedb.ui.components.CanvasZoomControls
import com.safedb.ui.components.MessageBanner
import com.safedb.ui.components.SecondaryButton
import com.safedb.ui.theme.DataMono
import com.safedb.ui.theme.SafeDbTheme
import com.safedb.viewmodel.CANVAS_MAX_ZOOM
import com.safedb.viewmodel.CANVAS_MIN_ZOOM
import com.safedb.viewmodel.CANVAS_ZOOM_STEP
import com.safedb.viewmodel.CanvasScrollbarAdapter
import com.safedb.viewmodel.SchemaMapViewModel
import com.safedb.viewmodel.SchemaViewModel
import com.safedb.viewmodel.canvasAxisScrollState
import com.safedb.viewmodel.canvasConstrainedPan
import com.safedb.viewmodel.canvasPanForScrollEvent

@Composable
internal fun SchemaMapScreen(
    connection: ConnectionDef?,
    connections: List<ConnectionDef>,
    mapViewModel: SchemaMapViewModel,
    schemaViewModel: SchemaViewModel,
    schemaSelection: SchemaSelectionIntent,
    schemaHistoryError: String?,
    onConnectionSelected: (ConnectionDef) -> Unit,
    onSchemaSelected: (String) -> Unit,
    onUnavailableSchemaSelection: (SchemaSelectionIntent) -> Unit,
    onDismissSchemaHistoryError: () -> Unit,
    onRetry: () -> Unit,
    onOpenConnections: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var retryGeneration by remember { mutableIntStateOf(0) }
    val fallbackWarning =
        rememberSchemaLoad(
            connection = connection,
            schemaViewModel = schemaViewModel,
            schemaSelection = schemaSelection,
            onSchemaSelected = onSchemaSelected,
            onUnavailableSchemaSelection = onUnavailableSchemaSelection,
            retryKey = retryGeneration,
            onNoConnection = { mapViewModel.activate(null, null) },
        )

    LaunchedEffect(connection?.id, schemaViewModel.selectedSchema) {
        mapViewModel.activate(connection?.id, schemaViewModel.selectedSchema)
    }

    val selectedSchema = schemaViewModel.selectedSchema
    val schema = schemaViewModel.schema
    val graph =
        remember(schema, selectedSchema) {
            if (schema == null || selectedSchema == null) null
            else buildSchemaMapGraph(schema, selectedSchema)
        }

    Column(modifier = modifier.fillMaxSize().background(SafeDbTheme.colors.workspaceBackground)) {
        val tableCount = graph?.nodes?.count { !it.isExternal } ?: 0
        val relationshipCount = graph?.relationships?.size ?: 0
        WorkspaceScreenHeader(
            icon = Icons.Default.Hub,
            title = "Map",
            subtitle =
                if (connection == null) {
                    "A clean view of tables, keys, indexes, and relationships"
                } else {
                    buildString {
                        append(connection.database)
                        if (selectedSchema != null) append(" · $selectedSchema")
                        if (tableCount > 0)
                            append(" · $tableCount tables · $relationshipCount relationships")
                    }
                },
            connection = connection,
            connections = connections,
            selectedSchema = selectedSchema,
            schemaOptions = schemaViewModel.schemaOptions,
            contentSpacing = 10.dp,
            onConnectionSelected = onConnectionSelected,
            onSchemaSelected = { selected ->
                fallbackWarning.value = null
                schemaViewModel.selectSchema(selected)
                onSchemaSelected(selected)
            },
            bottomContent = {
                SchemaMapSearchField(
                    query = mapViewModel.query,
                    enabled = connection != null && selectedSchema != null,
                    onQueryChange = { mapViewModel.query = it },
                )
            },
        )

        fallbackWarning.value?.let { MessageBanner(it, BannerKind.WARNING) }
        SchemaHistoryErrorBanner(schemaHistoryError, onDismiss = onDismissSchemaHistoryError)

        when {
            connection == null ->
                SchemaMapEmptyState(
                    title = "Choose a database to map",
                    message =
                        "Select a saved connection to see its tables, keys, indexes, and relationships.",
                    action = "Open Connections",
                    onAction = onOpenConnections,
                )
            schemaViewModel.loading ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Text(
                            "Loading schema…",
                            modifier = Modifier.padding(top = 12.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            schemaViewModel.error != null ->
                SchemaMapEmptyState(
                    title = "Could not load this schema",
                    message = schemaViewModel.error.orEmpty(),
                    action = "Retry",
                    onAction = {
                        onRetry()
                        retryGeneration += 1
                    },
                )
            selectedSchema == null ->
                SchemaMapEmptyState(
                    title = "No schema selected",
                    message =
                        if (schemaViewModel.schemaOptions.isEmpty()) {
                            "No schemas containing visible tables were found."
                        } else {
                            "Choose a schema from the header to build its map."
                        },
                )
            graph == null || graph.nodes.none { !it.isExternal } ->
                SchemaMapEmptyState(
                    title = "No tables to map",
                    message = "This schema has no visible tables.",
                )
            else ->
                SchemaMapCanvas(
                    connectionId = connection.id,
                    selectedSchema = selectedSchema,
                    graph = graph,
                    viewModel = mapViewModel,
                )
        }
    }
}

@Composable
private fun SchemaMapSearchField(query: String, enabled: Boolean, onQueryChange: (String) -> Unit) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .height(38.dp)
                .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.small)
                .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            Icons.Default.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(17.dp),
        )
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            enabled = enabled,
            modifier = Modifier.weight(1f),
            singleLine = true,
            textStyle =
                MaterialTheme.typography.bodySmall.copy(
                    color = MaterialTheme.colorScheme.onSurface
                ),
            decorationBox = { inner ->
                Box {
                    if (query.isEmpty()) {
                        Text(
                            "Search tables, columns, indexes, and foreign keys…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    inner()
                }
            },
        )
        if (query.isNotEmpty()) {
            Text(
                "Clear",
                style = MaterialTheme.typography.labelSmall,
                color = SafeDbTheme.colors.actionPrimary,
                modifier = Modifier.clickable { onQueryChange("") }.padding(4.dp),
            )
        }
    }
}

@Composable
private fun SchemaMapEmptyState(
    title: String,
    message: String,
    action: String? = null,
    onAction: () -> Unit = {},
) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Default.Hub,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (action != null) {
                SecondaryButton(onClick = onAction, modifier = Modifier.padding(top = 4.dp)) {
                    Text(action)
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalComposeUiApi::class)
private fun SchemaMapCanvas(
    connectionId: String,
    selectedSchema: String,
    graph: SchemaMapGraph,
    viewModel: SchemaMapViewModel,
) {
    val density = LocalDensity.current
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val contextReady = viewModel.isActive(connectionId, selectedSchema)
    if (!contextReady) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        }
        return
    }
    val nodeSizes =
        graph.nodes.associate { node ->
            node.id to schemaMapNodeSize(node, viewModel.isExpanded(node.id))
        }
    val basePositions = remember(graph, nodeSizes) { layoutSchemaMap(graph, nodeSizes) }
    val baseRelationshipGeometry =
        remember(graph, basePositions, nodeSizes) {
            schemaMapRelationshipGeometries(graph, basePositions, nodeSizes)
        }
    val baseContentBounds =
        schemaMapContentBounds(
            graph,
            basePositions,
            nodeSizes,
            density.density,
            baseRelationshipGeometry,
        )
    val positions =
        graph.nodes.associate { node ->
            val base = basePositions.getValue(node.id)
            val offset = viewModel.offsetFor(node.id)
            node.id to SchemaMapPoint(base.x + offset.x, base.y + offset.y)
        }
    val relationshipGeometry =
        remember(graph, positions, nodeSizes) {
            schemaMapRelationshipGeometries(graph, positions, nodeSizes)
        }
    val search = remember(graph, viewModel.query) { searchSchemaMap(graph, viewModel.query) }
    val contentBounds =
        schemaMapContentBounds(
            graph,
            positions,
            nodeSizes,
            density.density,
            relationshipGeometry,
        )
    val viewportSize = Size(viewport.width.toFloat(), viewport.height.toFloat())
    val horizontalScroll =
        canvasAxisScrollState(
            contentStart = contentBounds.left,
            contentEnd = contentBounds.right,
            viewportSize = viewportSize.width,
            zoom = viewModel.zoom,
            pan = viewModel.pan.x,
        )
    val verticalScroll =
        canvasAxisScrollState(
            contentStart = contentBounds.top,
            contentEnd = contentBounds.bottom,
            viewportSize = viewportSize.height,
            zoom = viewModel.zoom,
            pan = viewModel.pan.y,
        )
    val currentHorizontalScroll = rememberUpdatedState(horizontalScroll)
    val currentVerticalScroll = rememberUpdatedState(verticalScroll)
    val horizontalScrollbarAdapter =
        remember(viewModel) {
            CanvasScrollbarAdapter(
                state = { currentHorizontalScroll.value },
                onScrollTo = { target ->
                    val axis = currentHorizontalScroll.value
                    viewModel.updatePan(Offset(axis.panForScrollOffset(target), viewModel.pan.y))
                },
            )
        }
    val verticalScrollbarAdapter =
        remember(viewModel) {
            CanvasScrollbarAdapter(
                state = { currentVerticalScroll.value },
                onScrollTo = { target ->
                    val axis = currentVerticalScroll.value
                    viewModel.updatePan(Offset(viewModel.pan.x, axis.panForScrollOffset(target)))
                },
            )
        }
    val renderPaddingPx = SCHEMA_MAP_RENDER_PADDING_DP * density.density
    val renderOrigin =
        Offset(contentBounds.left - renderPaddingPx, contentBounds.top - renderPaddingPx)
    val renderOriginDp =
        SchemaMapPoint(renderOrigin.x / density.density, renderOrigin.y / density.density)
    val renderPositions = positions.mapValues { (_, point) ->
        point.translated(-renderOriginDp.x, -renderOriginDp.y)
    }
    val renderRelationshipGeometry = relationshipGeometry.mapValues { (_, geometry) ->
        geometry.translated(-renderOriginDp.x, -renderOriginDp.y)
    }
    val renderWidth =
        ((contentBounds.width + renderPaddingPx * 2f) / density.density).coerceAtLeast(1f)
    val renderHeight =
        ((contentBounds.height + renderPaddingPx * 2f) / density.density).coerceAtLeast(1f)

    val viewportReady = viewport.width > 0 && viewport.height > 0
    LaunchedEffect(connectionId, selectedSchema, viewportReady) {
        if (viewportReady && viewModel.consumeInitialFitRequest(connectionId, selectedSchema)) {
            viewModel.fit(contentBounds, viewportSize)
        }
    }

    LaunchedEffect(horizontalScroll, verticalScroll, viewport) {
        if (viewport.width > 0 && viewport.height > 0) {
            val constrained = canvasConstrainedPan(viewModel.pan, horizontalScroll, verticalScroll)
            if (constrained != viewModel.pan) viewModel.updatePan(constrained)
        }
    }

    Box(
        modifier =
            Modifier.fillMaxSize()
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
                                if (change.scrollDelta.y < 0f) CANVAS_ZOOM_STEP
                                else -CANVAS_ZOOM_STEP
                            viewModel.setZoom(viewModel.zoom + delta, change.position)
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
                            if (target != null && target != viewModel.pan) {
                                viewModel.updatePan(target)
                                change.consume()
                            }
                        }
                    }
                }
                .pointerInput(
                    connectionId,
                    selectedSchema,
                    contentBounds,
                    viewportSize,
                    viewModel.zoom,
                ) {
                    detectDragGestures { change, dragAmount ->
                        if (!change.isConsumed) {
                            change.consume()
                            viewModel.updatePan(
                                canvasConstrainedPan(
                                    viewModel.pan + dragAmount,
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
                    .requiredSize(renderWidth.dp, renderHeight.dp)
                    .graphicsLayer {
                        scaleX = viewModel.zoom
                        scaleY = viewModel.zoom
                        translationX = viewModel.pan.x + renderOrigin.x * viewModel.zoom
                        translationY = viewModel.pan.y + renderOrigin.y * viewModel.zoom
                        transformOrigin = TransformOrigin(0f, 0f)
                    }
        ) {
            SchemaRelationships(
                graph = graph,
                geometries = renderRelationshipGeometry,
                search = search,
                searchActive = viewModel.query.isNotBlank(),
            )

            graph.relationships.forEach { relationship ->
                val geometry = renderRelationshipGeometry.getValue(relationship.id)
                val midpoint = geometry.anchor
                val targetSize = 24f / viewModel.zoom
                RelationshipTooltipAnchor(
                    relationship = relationship,
                    targetSize = targetSize,
                    dotSize = 5f / viewModel.zoom,
                    modifier =
                        Modifier.offset {
                                IntOffset(
                                    with(density) { (midpoint.x - targetSize / 2f).dp.roundToPx() },
                                    with(density) { (midpoint.y - targetSize / 2f).dp.roundToPx() },
                                )
                            }
                            .zIndex(2f),
                )
            }

            graph.nodes.forEach { node ->
                val point = renderPositions.getValue(node.id)
                val size = nodeSizes.getValue(node.id)
                val highlighted = viewModel.query.isBlank() || node.id in search.nodeIds
                if (node.isExternal) {
                    ExternalSchemaNode(
                        node = node,
                        highlighted = highlighted,
                        modifier =
                            Modifier.offset {
                                    IntOffset(
                                        with(density) { point.x.dp.roundToPx() },
                                        with(density) { point.y.dp.roundToPx() },
                                    )
                                }
                                .width(size.width.dp)
                                .height(size.height.dp)
                                .zIndex(if (highlighted) 3f else 1f),
                    )
                } else {
                    SchemaTableNode(
                        node = node,
                        expanded = viewModel.isExpanded(node.id),
                        highlighted = highlighted,
                        matchingColumns = search.columnsByNode[node.id].orEmpty().toSet(),
                        onToggleExpanded = { viewModel.toggleExpanded(node.id) },
                        onMove = { screenDelta ->
                            viewModel.moveNode(
                                node.id,
                                Offset(
                                    screenDelta.x / density.density,
                                    screenDelta.y / density.density,
                                ),
                            )
                        },
                        modifier =
                            Modifier.offset {
                                    IntOffset(
                                        with(density) { point.x.dp.roundToPx() },
                                        with(density) { point.y.dp.roundToPx() },
                                    )
                                }
                                .width(size.width.dp)
                                .height(size.height.dp)
                                .zIndex(if (highlighted) 4f else 2f),
                    )
                }
            }
        }

        HorizontalScrollbar(
            adapter = horizontalScrollbarAdapter,
            modifier =
                Modifier.align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(end = SCHEMA_MAP_SCROLLBAR_CORNER.dp)
                    .zIndex(10f),
        )
        VerticalScrollbar(
            adapter = verticalScrollbarAdapter,
            modifier =
                Modifier.align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .padding(bottom = SCHEMA_MAP_SCROLLBAR_CORNER.dp)
                    .zIndex(10f),
        )

        CanvasZoomControls(
            zoom = viewModel.zoom,
            minZoom = CANVAS_MIN_ZOOM,
            maxZoom = CANVAS_MAX_ZOOM,
            fitDescription = "Fit map to screen",
            resetDescription = "Reset layout",
            onZoomOut = {
                viewModel.setZoom(
                    viewModel.zoom - CANVAS_ZOOM_STEP,
                    Offset(viewport.width / 2f, viewport.height / 2f),
                )
            },
            onZoomIn = {
                viewModel.setZoom(
                    viewModel.zoom + CANVAS_ZOOM_STEP,
                    Offset(viewport.width / 2f, viewport.height / 2f),
                )
            },
            onFit = { viewModel.fit(contentBounds, viewportSize) },
            onReset = {
                viewModel.resetLayout()
                viewModel.fit(baseContentBounds, viewportSize)
            },
            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
        )

        if (viewModel.query.isNotBlank()) {
            SchemaMapSearchResults(
                graph = graph,
                result = search,
                onFocus = { nodeId ->
                    val bounds = schemaMapNodeBounds(nodeId, positions, nodeSizes, density.density)
                    viewModel.focus(bounds, viewportSize)
                },
                modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
            )
        }

        SchemaMapLegend(modifier = Modifier.align(Alignment.BottomStart).padding(12.dp))
    }
}

private fun schemaMapNodeSize(node: SchemaMapNode, expanded: Boolean): SchemaMapSize {
    if (node.isExternal) return SchemaMapSize(224f, 94f)
    val keyRows = node.columns.count { it.isKeyRelated }.coerceAtLeast(1)
    val columnRows = if (expanded) node.columns.size.coerceAtLeast(1) else keyRows
    val indexRows = if (expanded) node.table?.indexes?.size.orZero().coerceAtMost(4) else 0
    val height =
        (MAP_CARD_HEADER +
                columnRows.coerceAtMost(9) * MAP_CARD_ROW +
                MAP_CARD_FOOTER +
                if (indexRows > 0) 30f + indexRows * 24f else 0f)
            .coerceIn(128f, 430f)
    return SchemaMapSize(282f, height)
}

@Composable
private fun SchemaRelationships(
    graph: SchemaMapGraph,
    geometries: Map<String, SchemaMapRelationshipGeometry>,
    search: SchemaMapSearchResult,
    searchActive: Boolean,
) {
    val relationColor = SafeDbTheme.colors.actionPrimary
    val mutedColor = MaterialTheme.colorScheme.outline
    Canvas(Modifier.fillMaxSize().zIndex(0f)) {
        val drawScope = this
        graph.relationships.forEach { relationship ->
            val geometry = geometries[relationship.id] ?: return@forEach
            val sourcePoint = geometry.source.toPxOffset(this)
            val targetPoint = geometry.target.toPxOffset(this)
            val selected =
                !searchActive ||
                    relationship.id in search.relationshipIds ||
                    relationship.sourceNodeId in search.nodeIds ||
                    relationship.targetNodeId in search.nodeIds
            val color = if (selected) relationColor else mutedColor
            val alpha = if (selected) 0.82f else 0.16f
            val path =
                Path().apply {
                    moveTo(sourcePoint.x, sourcePoint.y)
                    geometry.bends.forEach { bend ->
                        val point = bend.toPxOffset(drawScope)
                        lineTo(point.x, point.y)
                    }
                    lineTo(targetPoint.x, targetPoint.y)
                }
            drawPath(
                path,
                color.copy(alpha = alpha),
                style =
                    androidx.compose.ui.graphics.drawscope.Stroke(
                        2.dp.toPx(),
                        cap = StrokeCap.Round,
                    ),
            )
            drawSourceMarker(
                point = sourcePoint,
                towardRight = geometry.sourceTowardRight,
                cardinality = relationship.cardinality,
                color = color.copy(alpha = alpha),
            )
            drawTargetMarker(
                point = targetPoint,
                towardRight = geometry.targetTowardRight,
                optionality = relationship.optionality,
                color = color.copy(alpha = alpha),
            )
        }
    }
}

private fun DrawScope.drawSourceMarker(
    point: Offset,
    towardRight: Boolean,
    cardinality: SchemaMapCardinality,
    color: Color,
) {
    val direction = if (towardRight) 1f else -1f
    val near = point.x + direction * 7.dp.toPx()
    drawCircle(color, radius = 3.dp.toPx(), center = Offset(near, point.y))
    val markerX = point.x + direction * 14.dp.toPx()
    when (cardinality) {
        SchemaMapCardinality.ManyToOne -> {
            val tipX = point.x + direction * 23.dp.toPx()
            drawLine(
                color,
                Offset(markerX, point.y),
                Offset(tipX, point.y - 7.dp.toPx()),
                1.6.dp.toPx(),
            )
            drawLine(color, Offset(markerX, point.y), Offset(tipX, point.y), 1.6.dp.toPx())
            drawLine(
                color,
                Offset(markerX, point.y),
                Offset(tipX, point.y + 7.dp.toPx()),
                1.6.dp.toPx(),
            )
        }
        SchemaMapCardinality.OneToOne ->
            drawLine(
                color,
                Offset(markerX, point.y - 7.dp.toPx()),
                Offset(markerX, point.y + 7.dp.toPx()),
                1.8.dp.toPx(),
            )
        SchemaMapCardinality.Unknown -> {
            val radius = 5.dp.toPx()
            val diamond =
                Path().apply {
                    moveTo(markerX, point.y - radius)
                    lineTo(markerX + radius, point.y)
                    lineTo(markerX, point.y + radius)
                    lineTo(markerX - radius, point.y)
                    close()
                }
            drawPath(
                diamond,
                color,
                style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()),
            )
        }
    }
}

private fun DrawScope.drawTargetMarker(
    point: Offset,
    towardRight: Boolean,
    optionality: SchemaMapOptionality,
    color: Color,
) {
    val direction = if (towardRight) 1f else -1f
    val barX = point.x + direction * 8.dp.toPx()
    drawLine(
        color,
        Offset(barX, point.y - 7.dp.toPx()),
        Offset(barX, point.y + 7.dp.toPx()),
        1.8.dp.toPx(),
    )
    when (optionality) {
        SchemaMapOptionality.Optional ->
            drawCircle(
                color,
                radius = 3.dp.toPx(),
                center = Offset(point.x + direction * 16.dp.toPx(), point.y),
                style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()),
            )
        SchemaMapOptionality.Required -> {
            val second = point.x + direction * 13.dp.toPx()
            drawLine(
                color,
                Offset(second, point.y - 7.dp.toPx()),
                Offset(second, point.y + 7.dp.toPx()),
                1.8.dp.toPx(),
            )
        }
        SchemaMapOptionality.Unknown -> Unit
    }
}

@Composable
private fun SchemaTableNode(
    node: SchemaMapNode,
    expanded: Boolean,
    highlighted: Boolean,
    matchingColumns: Set<String>,
    onToggleExpanded: () -> Unit,
    onMove: (Offset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val table = requireNotNull(node.table)
    val visibleColumns = if (expanded) node.columns else node.columns.filter { it.isKeyRelated }
    val displayColumns = visibleColumns.ifEmpty { node.columns.take(1) }
    Surface(
        modifier = modifier.graphicsLayer { alpha = if (highlighted) 1f else 0.3f },
        shape = MaterialTheme.shapes.small,
        border =
            androidx.compose.foundation.BorderStroke(
                if (highlighted) 1.2.dp else 1.dp,
                if (highlighted) MaterialTheme.colorScheme.outline
                else MaterialTheme.colorScheme.outlineVariant,
            ),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
    ) {
        Column {
            Row(
                modifier =
                    Modifier.fillMaxWidth()
                        .height(MAP_CARD_HEADER.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .pointerHoverIcon(PointerIcon.Hand)
                        .semantics { contentDescription = "Move ${table.name} table" }
                        .pointerInput(node.id) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                onMove(dragAmount)
                            }
                        }
                        .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.TableChart,
                    contentDescription = null,
                    tint = SafeDbTheme.colors.actionPrimary,
                    modifier = Modifier.size(17.dp),
                )
                Column(modifier = Modifier.weight(1f).padding(start = 7.dp)) {
                    Text(
                        table.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${table.columns.size} columns · ${table.indexes.size} indexes",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription =
                        if (expanded) "Show key columns only" else "Show all columns",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier =
                        Modifier.size(24.dp)
                            .clickable(role = Role.Button, onClick = onToggleExpanded)
                            .padding(3.dp),
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
            ) {
                if (!expanded && visibleColumns.isEmpty()) {
                    Text(
                        "No key or index columns reported",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                } else {
                    displayColumns.forEach { projected ->
                        val column = projected.column
                        val matched = matchingColumns.isEmpty() || column.name in matchingColumns
                        Row(
                            modifier =
                                Modifier.fillMaxWidth()
                                    .height(MAP_CARD_ROW.dp)
                                    .background(
                                        if (matchingColumns.isNotEmpty() && matched) {
                                            SafeDbTheme.colors.accentContainer.copy(alpha = 0.7f)
                                        } else {
                                            Color.Transparent
                                        }
                                    )
                                    .padding(horizontal = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                column.name,
                                style = DataMono.copy(fontWeight = FontWeight.Medium),
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                column.dataType,
                                style = DataMono,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.widthIn(max = 74.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            projected.markers.forEach { marker -> SchemaColumnMarker(marker) }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }

                if (expanded && table.indexes.isNotEmpty()) {
                    Text(
                        "INDEXES",
                        style = com.safedb.ui.theme.LabelMicro,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 10.dp, top = 8.dp, bottom = 4.dp),
                    )
                    table.indexes.forEach { index -> IndexDetailRow(index) }
                }
                if (!table.indexMetadata.isComplete || !table.foreignKeyMetadata.isComplete) {
                    MetadataCoverageRow(
                        indexesIncomplete = !table.indexMetadata.isComplete,
                        relationshipsIncomplete = !table.foreignKeyMetadata.isComplete,
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier =
                    Modifier.fillMaxWidth()
                        .height(MAP_CARD_FOOTER.dp)
                        .clickable(role = Role.Button, onClick = onToggleExpanded)
                        .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    if (expanded) "Keys first" else "Show all ${table.columns.size} columns",
                    style = MaterialTheme.typography.labelSmall,
                    color = SafeDbTheme.colors.actionPrimary,
                )
                Text(
                    "${table.foreignKeys.size} FK",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SchemaColumnMarker(marker: SchemaMapColumnMarker) {
    val (icon, tint, description) =
        when (marker.kind) {
            SchemaMapColumnMarkerKind.PrimaryKey ->
                Triple(Icons.Default.Key, SafeDbTheme.colors.actionPrimary, "Primary key")
            SchemaMapColumnMarkerKind.Unique ->
                Triple(Icons.Default.Verified, SafeDbTheme.colors.uq, "Unique")
            SchemaMapColumnMarkerKind.ForeignKey ->
                Triple(Icons.Default.Link, SafeDbTheme.colors.info, "Foreign key")
            SchemaMapColumnMarkerKind.Index ->
                Triple(Icons.Default.Storage, MaterialTheme.colorScheme.onSurfaceVariant, "Index")
        }
    Icon(
        icon,
        contentDescription = description,
        tint = tint,
        modifier = Modifier.padding(start = 3.dp).size(15.dp),
    )
}

@Composable
private fun IndexDetailRow(index: IndexInfo) {
    val type =
        when {
            index.isPrimary -> "Primary key"
            index.isUnique -> "Unique index"
            else -> "Index"
        }
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .height(24.dp)
                .semantics(mergeDescendants = true) {
                    role = Role.Image
                    contentDescription = "$type ${index.name}"
                }
                .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            when {
                index.isPrimary -> Icons.Default.Key
                index.isUnique -> Icons.Default.Verified
                else -> Icons.Default.Storage
            },
            contentDescription = null,
            tint =
                when {
                    index.isPrimary -> SafeDbTheme.colors.actionPrimary
                    index.isUnique -> SafeDbTheme.colors.uq
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            modifier = Modifier.size(14.dp),
        )
        Text(
            index.name,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(start = 6.dp).weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun MetadataCoverageRow(indexesIncomplete: Boolean, relationshipsIncomplete: Boolean) {
    val description = buildString {
        if (indexesIncomplete) append("Index metadata is unavailable or incomplete.")
        if (indexesIncomplete && relationshipsIncomplete) append(' ')
        if (relationshipsIncomplete) append("Relationship metadata is unavailable or incomplete.")
    }
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .semantics(mergeDescendants = true) {
                    role = Role.Image
                    contentDescription = description
                }
                .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.Info,
            contentDescription = null,
            tint = SafeDbTheme.colors.warning,
            modifier = Modifier.size(14.dp),
        )
        Text(
            "Some metadata unavailable",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

@Composable
private fun ExternalSchemaNode(
    node: SchemaMapNode,
    highlighted: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.graphicsLayer { alpha = if (highlighted) 1f else 0.3f },
        shape = MaterialTheme.shapes.small,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Hub,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    "External table",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
            Text(
                node.label,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                node.externalColumns.joinToString(", ").ifBlank {
                    "Referenced outside this schema"
                },
                style = DataMono,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun RelationshipTooltipAnchor(
    relationship: SchemaMapRelationship,
    targetSize: Float,
    dotSize: Float,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val focused by interactionSource.collectIsFocusedAsState()
    Box(modifier = modifier.size(targetSize.dp)) {
        SchemaMapTooltip(relationship.label) {
            Box(
                modifier =
                    Modifier.fillMaxSize()
                        .hoverable(interactionSource)
                        .semantics {
                            role = Role.Image
                            contentDescription = relationship.label
                        }
                        .focusable(interactionSource = interactionSource),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(if (hovered || focused) dotSize.dp * 1.8f else dotSize.dp)
                        .background(
                            SafeDbTheme.colors.actionPrimary.copy(
                                alpha = if (hovered || focused) 1f else 0.7f
                            ),
                            RoundedCornerShape(50),
                        )
                )
            }
        }
    }
}

@Composable
private fun SchemaMapSearchResults(
    graph: SchemaMapGraph,
    result: SchemaMapSearchResult,
    onFocus: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val matches = graph.nodes.filter { it.id in result.nodeIds }.take(6)
    Surface(
        modifier = modifier.width(244.dp),
        shape = MaterialTheme.shapes.small,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
    ) {
        Column(modifier = Modifier.padding(vertical = 5.dp)) {
            Text(
                if (matches.isEmpty()) "No matches" else "${result.nodeIds.size} matching tables",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            )
            matches.forEach { node ->
                Row(
                    modifier =
                        Modifier.fillMaxWidth()
                            .clickable { onFocus(node.id) }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (node.isExternal) Icons.Default.Hub else Icons.Default.TableChart,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = SafeDbTheme.colors.actionPrimary,
                    )
                    Column(modifier = Modifier.padding(start = 7.dp).weight(1f)) {
                        Text(node.label, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        result.columnsByNode[node.id]?.take(3)?.let { columns ->
                            Text(
                                columns.joinToString(", "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SchemaMapLegend(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LegendItem(Icons.Default.Key, "PK", SafeDbTheme.colors.actionPrimary)
            LegendItem(Icons.Default.Verified, "Unique", SafeDbTheme.colors.uq)
            LegendItem(Icons.Default.Link, "FK", SafeDbTheme.colors.info)
            LegendItem(Icons.Default.Storage, "Index", MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "Hover icons and lines for details",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LegendItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    color: Color,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(13.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(start = 3.dp),
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun SchemaMapTooltip(text: String, content: @Composable () -> Unit) {
    TooltipBox(
        positionProvider =
            TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = {
            PlainTooltip(modifier = Modifier.widthIn(max = 360.dp)) {
                Text(text, style = MaterialTheme.typography.bodySmall)
            }
        },
        state = rememberTooltipState(isPersistent = true),
        content = content,
    )
}
