package com.safedb.ui

import androidx.compose.foundation.Canvas as DrawCanvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.safedb.SchemaSelectionIntent
import com.safedb.explore.ExploreRecipe
import com.safedb.model.ConnectionDef
import com.safedb.model.Outcome
import com.safedb.model.SavedQuery
import com.safedb.model.Settings
import com.safedb.query.LARGE_LIMIT_WARNING_THRESHOLD
import com.safedb.query.RiskGateState
import com.safedb.ui.components.BannerKind
import com.safedb.ui.components.ConfirmDialog
import com.safedb.ui.components.MessageBanner
import com.safedb.ui.components.PrimaryButton
import com.safedb.ui.components.PromptDialog
import com.safedb.ui.components.SecondaryButton
import com.safedb.ui.theme.ChipShape
import com.safedb.ui.theme.SafeDbTheme
import com.safedb.ui.theme.ScreenHeaderHorizontalPadding
import com.safedb.ui.theme.ToolbarHeaderVerticalPadding
import com.safedb.viewmodel.QueryViewModel
import com.safedb.viewmodel.RecipesViewModel
import com.safedb.viewmodel.SavedQueriesViewModel
import com.safedb.viewmodel.SchemaViewModel
import java.time.Instant
import java.util.UUID

@Composable
internal fun BuilderScreen(
    connection: ConnectionDef?,
    connections: List<ConnectionDef>,
    queryViewModel: QueryViewModel,
    savedQueriesViewModel: SavedQueriesViewModel,
    recipesViewModel: RecipesViewModel,
    schemaViewModel: SchemaViewModel,
    schemaSelection: SchemaSelectionIntent,
    schemaHistoryError: String?,
    settings: Settings,
    sqlBusy: Boolean,
    onConnectionSelected: (ConnectionDef) -> Unit,
    onSchemaSelected: (String) -> Unit,
    onUnavailableSchemaSelection: (SchemaSelectionIntent) -> Unit,
    onDismissSchemaHistoryError: () -> Unit,
    onOpenExplore: () -> Unit,
    onOpenSettings: () -> Unit,
    onApplyRecipe: (ExploreRecipe, ConnectionDef) -> Unit,
    recipeApplyNotice: String? = null,
    onDismissRecipeApplyNotice: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var showSavePrompt by remember { mutableStateOf(false) }
    var saveQueryName by remember { mutableStateOf("") }
    var resultsHeight by remember { mutableFloatStateOf(240f) }
    var resultsPaneMode by remember { mutableStateOf(ResultsPaneMode.Normal) }
    var resizing by remember { mutableStateOf(false) }
    var filterControlsHeightPx by remember { mutableIntStateOf(0) }
    var optionControlsHeightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val limitChoices = BUILDER_LIMIT_CHOICES
    val schema = schemaViewModel.schema
    val preliminaryRisk =
        remember(queryViewModel.spec, schema, settings, connection?.dialect) {
            queryViewModel.evaluatePreliminaryRisk(schema, settings, connection?.dialect)
        }
    val preliminaryEvaluation = (preliminaryRisk as? Outcome.Ok)?.value
    val riskValidationError = (preliminaryRisk as? Outcome.Err)?.message
    val currentSample = queryViewModel.currentSample(connection?.id)
    val finalRiskEvaluation = queryViewModel.riskEvaluationFor(connection?.id)
    val riskDecision = finalRiskEvaluation?.decision
    val pendingConfirmation =
        queryViewModel.pendingConfirmation?.takeIf {
            it.confirmation.connectionId == connection?.id
        }
    // One effective availability predicate for the Run button and the adjacent risk copy, so the
    // copy never claims Run is enabled while the button is disabled (e.g. SQL editor busy).
    // A SQL editor run holds the same app-wide slot: one live query at a time.
    val runAvailable =
        queryViewModel.canRun &&
            riskValidationError == null &&
            connection != null &&
            schema != null &&
            schemaViewModel.loadedConnectionId == connection.id &&
            !queryViewModel.running &&
            !sqlBusy

    LaunchedEffect(connection?.id, schemaSelection) {
        val connectionId = connection?.id
        if (connectionId == null) {
            schemaViewModel.clear()
        } else {
            schemaViewModel.load(
                connectionId,
                selection = schemaSelection,
                onUnavailableSelection = onUnavailableSchemaSelection,
            )
        }
    }

    val visibleQueryError = queryViewModel.error
    val savedQueryError by savedQueriesViewModel.error.collectAsState()
    val showLargeLimitGuidance =
        connection != null &&
            queryViewModel.canvasTables.isNotEmpty() &&
            queryViewModel.limit > LARGE_LIMIT_WARNING_THRESHOLD

    if (pendingConfirmation != null) {
        val copy = queryConfirmationDialogCopy(pendingConfirmation)
        ConfirmDialog(
            open = true,
            title = copy.title,
            message = copy.message,
            confirmLabel = copy.confirmLabel,
            onConfirm = {
                if (sqlBusy) return@ConfirmDialog
                val connectionId = connection?.id
                if (connectionId == null) {
                    queryViewModel.dismissError()
                } else {
                    queryViewModel.confirmPendingExecution(connectionId)
                }
            },
            onCancel = queryViewModel::dismissError,
        )
    }

    PromptDialog(
        open = showSavePrompt,
        title = "Save query",
        message = "Choose a name for this query.",
        value = saveQueryName,
        onValueChange = { saveQueryName = it },
        placeholder = "Query name",
        onConfirm = {
            val activeConnection = connection ?: return@PromptDialog
            if (saveQueryName.isBlank()) return@PromptDialog
            savedQueriesViewModel.save(
                SavedQuery(
                    id = UUID.randomUUID().toString(),
                    name = saveQueryName.trim(),
                    connectionId = activeConnection.id,
                    spec = queryViewModel.spec,
                    createdAt = Instant.now().epochSecond.toString(),
                )
            ) {
                showSavePrompt = false
            }
        },
        onCancel = { showSavePrompt = false },
    )

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .then(
                    if (resizing) {
                        Modifier.pointerInput(Unit) {
                            detectDragGestures(
                                onDragEnd = { resizing = false },
                                onDragCancel = { resizing = false },
                                onDrag = { change, _ ->
                                    resultsHeight =
                                        (resultsHeight - change.position.y).coerceIn(100f, 600f)
                                },
                            )
                        }
                    } else {
                        Modifier
                    }
                )
    ) {
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .background(SafeDbTheme.colors.workspaceHeader)
                    .padding(
                        horizontal = ScreenHeaderHorizontalPadding,
                        vertical = ToolbarHeaderVerticalPadding,
                    ),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (connection != null) {
                // Weighted so the risk/join hint wraps instead of starving the run controls.
                Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                    Text(connection.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${dialectLabel(connection.dialect)} · ${connection.database}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        riskGateIndicatorText(settings.queryRiskGate),
                        style = MaterialTheme.typography.labelSmall,
                        color = SafeDbTheme.colors.actionPrimary,
                        modifier = Modifier.clickable(onClick = onOpenSettings),
                    )
                    if (queryViewModel.canvasTables.isNotEmpty()) {
                        Text(
                            queryRiskIndicatorText(
                                preliminaryEvaluation?.staticAssessment,
                                finalRiskEvaluation,
                                queryViewModel.running,
                                settings.queryRiskGate,
                                riskValidationError,
                                runAvailable = runAvailable,
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color =
                                when (riskDecision?.state) {
                                    RiskGateState.Blocked -> MaterialTheme.colorScheme.error
                                    RiskGateState.ConfirmationRequired ->
                                        MaterialTheme.colorScheme.tertiary
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                        )
                    }
                }
            } else {
                Text(
                    "Query Builder",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f).padding(end = 16.dp),
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BuilderRecipeButton(
                    recipesViewModel = recipesViewModel,
                    connections = connections,
                    activeConnection = connection,
                    currentSample = connection?.id?.let(queryViewModel::currentSample)?.result,
                    currentSpec = queryViewModel.spec,
                    onApply = onApplyRecipe,
                )
                if (queryViewModel.canvasTables.isNotEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "Limit",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        for (choice in limitChoices) {
                            LimitChoiceChip(
                                label = "%,d".format(choice),
                                selected = queryViewModel.limit == choice,
                                onClick = { queryViewModel.setLimit(choice) },
                            )
                        }
                    }
                    TextButton(
                        onClick = {
                            saveQueryName =
                                "Query on " +
                                    queryViewModel.canvasTables.joinToString(", ") {
                                        it.tableInfo.name
                                    }
                            showSavePrompt = true
                        }
                    ) {
                        Text("Save")
                    }
                    TextButton(onClick = { queryViewModel.clear() }) { Text("Clear") }
                }
                PrimaryButton(
                    onClick = {
                        val connectionId = connection?.id
                        if (
                            connectionId != null &&
                                schemaViewModel.schema != null &&
                                schemaViewModel.loadedConnectionId == connectionId
                        ) {
                            queryViewModel.run(connectionId)
                        }
                    },
                    enabled = runAvailable,
                ) {
                    if (queryViewModel.running) {
                        CircularProgressIndicator(
                            modifier = Modifier.width(16.dp).height(16.dp),
                            strokeWidth = 2.dp,
                            color = LocalContentColor.current,
                        )
                    } else {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.width(18.dp).height(18.dp),
                        )
                    }
                    Text("Run", modifier = Modifier.padding(start = 6.dp))
                }
            }
        }

        if (showLargeLimitGuidance) {
            MessageBanner(
                text =
                    "Large result limit. Higher limits are useful for reporting, but filters, selected columns, and indexed predicates make queries faster and easier to reuse.",
                kind = BannerKind.INFO,
            )
        }

        if (riskDecision?.state == RiskGateState.Blocked) {
            MessageBanner(
                text = riskDecision.reasons.take(3).joinToString(" ") { it.message },
                kind = BannerKind.ERROR,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
        }

        planSafeguardBannerText(finalRiskEvaluation)?.let { message ->
            MessageBanner(
                text = message,
                kind =
                    if (riskDecision?.state == RiskGateState.ConfirmationRequired) {
                        BannerKind.WARNING
                    } else {
                        BannerKind.INFO
                    },
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
        }

        savedQueryError?.let { error ->
            MessageBanner(
                text = error,
                kind = BannerKind.ERROR,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
        }

        schemaHistoryError?.let { error ->
            MessageBanner(
                text = "Could not remember the selected schema: $error",
                kind = BannerKind.WARNING,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            ) {
                SecondaryButton(onClick = onDismissSchemaHistoryError) { Text("Dismiss") }
            }
        }

        if (connection == null) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(modifier = Modifier.fillMaxSize()) {
                Surface(
                    modifier = Modifier.width(288.dp).fillMaxHeight(),
                    color = SafeDbTheme.colors.workspacePanel,
                    tonalElevation = 0.dp,
                ) {
                    SchemaBrowser(
                        schemaViewModel = schemaViewModel,
                        connection = null,
                        connections = connections,
                        onConnectionSelected = onConnectionSelected,
                        onSchemaSelected = onSchemaSelected,
                    )
                }
                Box(
                    modifier =
                        Modifier.width(1.dp)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.outline)
                )
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "Choose a connection to start building queries.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(modifier = Modifier.fillMaxSize()) {
                Surface(
                    modifier = Modifier.width(288.dp).fillMaxHeight(),
                    color = SafeDbTheme.colors.workspacePanel,
                    tonalElevation = 0.dp,
                ) {
                    SchemaBrowser(
                        schemaViewModel = schemaViewModel,
                        connection = connection,
                        connections = connections,
                        onConnectionSelected = onConnectionSelected,
                        onAddTable = { queryViewModel.addTable(it) },
                        onSchemaSelected = onSchemaSelected,
                    )
                }
                Box(
                    modifier =
                        Modifier.width(1.dp)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.outline)
                )

                BoxWithConstraints(
                    modifier = Modifier.fillMaxSize().background(SafeDbTheme.colors.workspaceCanvas)
                ) {
                    val maxWorkspaceHeight = maxHeight.value.coerceAtLeast(ResultsPaneMinHeight)
                    val maxNormalHeight =
                        maxWorkspaceHeight
                            .coerceAtMost(ResultsPaneMaxHeight)
                            .coerceAtLeast(ResultsPaneMinHeight)
                    val resultsPaneHeight =
                        when (resultsPaneMode) {
                            ResultsPaneMode.Normal ->
                                resultsHeight.coerceIn(ResultsPaneMinHeight, maxNormalHeight)
                            ResultsPaneMode.Maximized -> maxWorkspaceHeight
                        }
                    val resultsMaximized = resultsPaneMode == ResultsPaneMode.Maximized

                    Column(modifier = Modifier.fillMaxSize()) {
                        if (!resultsMaximized) {
                            queryViewModel.hydrationWarning?.let { warning ->
                                MessageBanner(text = warning, kind = BannerKind.WARNING) {
                                    TextButton(
                                        onClick = { queryViewModel.dismissHydrationWarning() }
                                    ) {
                                        Text("Dismiss")
                                    }
                                }
                            }
                            recipeApplyNotice?.let { notice ->
                                MessageBanner(text = notice, kind = BannerKind.WARNING) {
                                    TextButton(onClick = onDismissRecipeApplyNotice) {
                                        Text("Dismiss")
                                    }
                                }
                            }

                            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                if (queryViewModel.canvasTables.isEmpty()) {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            "Click + next to a table in the sidebar to add it.",
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                } else {
                                    Canvas(
                                        queryViewModel = queryViewModel,
                                        contentTopInset =
                                            queryControlsCanvasInset(
                                                with(density) {
                                                    queryControlsHeightPx(
                                                            filterCount =
                                                                queryViewModel.filterCount,
                                                            filterHeightPx = filterControlsHeightPx,
                                                            optionHeightPx = optionControlsHeightPx,
                                                        )
                                                        .toDp()
                                                }
                                            ),
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                    val tableNamesByAlias =
                                        queryViewModel.canvasTables.associate {
                                            it.alias to it.tableInfo.name
                                        }
                                    // Keep controls as separate aligned siblings (no full-width
                                    // empty parent) so dashed join-line clicks reach the canvas.
                                    if (queryViewModel.filterCount > 0) {
                                        FilterGroupCard(
                                            queryViewModel = queryViewModel,
                                            group = queryViewModel.filters,
                                            path = emptyList(),
                                            depth = 0,
                                            modifier =
                                                Modifier.align(Alignment.TopStart)
                                                    .padding(
                                                        start = 16.dp,
                                                        top = QueryControlsVerticalPadding,
                                                        end = 280.dp,
                                                    )
                                                    .fillMaxWidth()
                                                    .heightIn(max = QueryControlsMaxHeight)
                                                    .onSizeChanged {
                                                        filterControlsHeightPx = it.height
                                                    }
                                                    .verticalScroll(rememberScrollState())
                                                    .horizontalScroll(rememberScrollState()),
                                        )
                                    }
                                    Column(
                                        modifier =
                                            Modifier.align(Alignment.TopEnd)
                                                .padding(
                                                    top = QueryControlsVerticalPadding,
                                                    end = 16.dp,
                                                )
                                                .widthIn(min = 208.dp, max = 256.dp)
                                                .onSizeChanged {
                                                    optionControlsHeightPx = it.height
                                                },
                                        verticalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        QueryOptionsCard(
                                            distinct = queryViewModel.distinct,
                                            onDistinctChange = queryViewModel::setDistinct,
                                            groups = queryViewModel.groups,
                                            sorts = queryViewModel.sorts,
                                            distinctSortConflicts =
                                                queryViewModel.distinctSortConflicts,
                                            onSelectDistinctSortColumns =
                                                queryViewModel::selectDistinctSortColumns,
                                            onRemoveDistinctSortConflicts =
                                                queryViewModel::removeDistinctSortConflicts,
                                            onMoveGroup = queryViewModel::moveGroup,
                                            onMoveSort = queryViewModel::moveSort,
                                            tableNamesByAlias = tableNamesByAlias,
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                        if (queryViewModel.joins.isNotEmpty()) {
                                            JoinItems(
                                                joins = queryViewModel.joins,
                                                tableNamesByAlias = tableNamesByAlias,
                                                onRemove = queryViewModel::removeJoin,
                                                modifier = Modifier.fillMaxWidth(),
                                            )
                                        }
                                    }
                                }
                            }

                            visibleQueryError?.let { error ->
                                MessageBanner(text = error, kind = BannerKind.ERROR)
                            }
                        }

                        currentSample?.let { sample ->
                            Column(
                                modifier = Modifier.fillMaxWidth().height(resultsPaneHeight.dp)
                            ) {
                                ResultsPaneResizeBar(
                                    mode = resultsPaneMode,
                                    resizing = resizing,
                                    onToggleMode = {
                                        val nextState =
                                            toggleResultsPane(resultsPaneMode, resultsHeight)
                                        resultsPaneMode = nextState.mode
                                        resultsHeight = nextState.height
                                    },
                                    onDragStart = {
                                        resizing = true
                                        resultsHeight =
                                            when (resultsPaneMode) {
                                                ResultsPaneMode.Normal -> resultsHeight
                                                ResultsPaneMode.Maximized -> maxNormalHeight
                                            }.coerceIn(ResultsPaneMinHeight, maxNormalHeight)
                                        resultsPaneMode = ResultsPaneMode.Normal
                                    },
                                    onDragEnd = { resizing = false },
                                    onDrag = { dragY ->
                                        resultsHeight =
                                            (resultsHeight - dragY).coerceIn(
                                                ResultsPaneMinHeight,
                                                maxNormalHeight,
                                            )
                                    },
                                )
                                ResultsTable(
                                    result = sample.result,
                                    tables = sample.spec.tables,
                                    modifier = Modifier.fillMaxSize(),
                                ) {
                                    PrimaryButton(onClick = onOpenExplore) { Text("Explore") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultsPaneResizeBar(
    mode: ResultsPaneMode,
    resizing: Boolean,
    onToggleMode: () -> Unit,
    onDragStart: () -> Unit,
    onDragEnd: () -> Unit,
    onDrag: (Float) -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val c = SafeDbTheme.colors
    val active = hovered || resizing
    val label = if (mode == ResultsPaneMode.Maximized) "Minimize results" else "Maximize results"
    val barColor =
        if (active) {
            c.accentContainer.copy(alpha = 0.85f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        }
    val gripColor =
        if (active) c.actionPrimary
        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.58f)
    val pillColor = if (active) c.actionPrimary else MaterialTheme.colorScheme.surfaceContainerHigh
    val pillContent = if (active) c.onActionPrimary else MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier =
            Modifier.fillMaxWidth()
                .height(ResultsPaneHandleHeight.dp)
                .background(barColor)
                .hoverable(interactionSource)
                .pointerHoverIcon(PointerIcon.Hand)
                .semantics { contentDescription = "Resize results panel" }
                .pointerInput(mode) {
                    detectDragGestures(
                        onDragStart = { onDragStart() },
                        onDragEnd = { onDragEnd() },
                        onDragCancel = { onDragEnd() },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            onDrag(dragAmount.y)
                        },
                    )
                },
        contentAlignment = Alignment.Center,
    ) {
        DrawCanvas(
            modifier =
                Modifier.fillMaxWidth()
                    .height(ResultsPaneHandleHeight.dp)
                    .padding(horizontal = 18.dp)
        ) {
            val strokeWidth = 1.5.dp.toPx()
            val y = size.height / 2f
            val centerGap = 34.dp.toPx()
            val lineInset = 2.dp.toPx()
            drawLine(
                color = gripColor,
                start = androidx.compose.ui.geometry.Offset(lineInset, y),
                end = androidx.compose.ui.geometry.Offset(size.width / 2f - centerGap, y),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = gripColor,
                start = androidx.compose.ui.geometry.Offset(size.width / 2f + centerGap, y),
                end = androidx.compose.ui.geometry.Offset(size.width - lineInset, y),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )
        }
        Box(
            modifier =
                Modifier.clip(ChipShape)
                    .background(pillColor)
                    .clickable(onClick = onToggleMode)
                    .semantics { contentDescription = label }
                    .padding(horizontal = 12.dp, vertical = 1.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (mode == ResultsPaneMode.Maximized) {
                    Icons.Default.KeyboardArrowDown
                } else {
                    Icons.Default.KeyboardArrowUp
                },
                contentDescription = label,
                tint = pillContent,
                modifier = Modifier.width(18.dp).height(18.dp),
            )
        }
    }
}

internal const val ResultsPaneMinHeight = 128f
private const val ResultsPaneMaxHeight = 640f
private const val ResultsPaneHandleHeight = 18f
