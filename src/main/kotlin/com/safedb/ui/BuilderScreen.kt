package com.safedb.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas as DrawCanvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.safedb.model.ConnectionDef
import com.safedb.model.GroupSpec
import com.safedb.model.SavedQuery
import com.safedb.model.SortDirection
import com.safedb.model.SortSpec
import com.safedb.query.DEFAULT_LIMIT
import com.safedb.query.LARGE_LIMIT_WARNING_THRESHOLD
import com.safedb.query.MAX_LIMIT
import com.safedb.ui.components.BannerKind
import com.safedb.ui.components.MessageBanner
import com.safedb.ui.components.PrimaryButton
import com.safedb.ui.components.SecondaryButton
import com.safedb.ui.components.ToolbarTooltipIconButton
import com.safedb.ui.components.PromptDialog
import com.safedb.ui.theme.ChipShape
import com.safedb.ui.theme.SafeDbTheme
import com.safedb.viewmodel.QueryViewModel
import com.safedb.viewmodel.SavedQueriesViewModel
import com.safedb.viewmodel.RecipesViewModel
import com.safedb.explore.ExploreRecipe
import com.safedb.viewmodel.SchemaViewModel
import java.time.Instant
import java.util.UUID

internal val BUILDER_LIMIT_CHOICES = listOf(DEFAULT_LIMIT, LARGE_LIMIT_WARNING_THRESHOLD, MAX_LIMIT)
private val QueryControlsMaxHeight = 208.dp
private val QueryControlsVerticalPadding = 8.dp
private val QueryControlsTableGap = 16.dp
internal val QueryControlsCanvasInset =
    QueryControlsVerticalPadding + QueryControlsMaxHeight + QueryControlsTableGap

private data class CostGuardDialogCopy(
    val title: String,
    val message: String,
    val confirmLabel: String,
)

internal enum class ResultsPaneMode {
    Normal,
    Maximized,
}

internal data class ResultsPaneState(
    val mode: ResultsPaneMode,
    val height: Float,
)

internal fun toggleResultsPane(mode: ResultsPaneMode, height: Float): ResultsPaneState =
    if (mode == ResultsPaneMode.Maximized) {
        ResultsPaneState(ResultsPaneMode.Normal, ResultsPaneMinHeight)
    } else {
        ResultsPaneState(ResultsPaneMode.Maximized, height)
    }

internal fun groupingOrderLabels(
    groups: List<GroupSpec>,
    tableNamesByAlias: Map<String, String>,
): List<String> = groups.map { group ->
    "${tableNamesByAlias[group.tableAlias] ?: group.tableAlias}.${group.column}"
}

internal fun sortOrderLabels(
    sorts: List<SortSpec>,
    tableNamesByAlias: Map<String, String>,
): List<String> = sorts.map { sort ->
    val column = "${tableNamesByAlias[sort.tableAlias] ?: sort.tableAlias}.${sort.column}"
    "$column ${if (sort.direction == SortDirection.Asc) "ascending" else "descending"}"
}

internal fun queryOptionEmptyLabel(labels: List<String>): String? =
    "None".takeIf { labels.isEmpty() }

@Composable
private fun QueryOptionsCard(
    distinct: Boolean,
    onDistinctChange: (Boolean) -> Unit,
    groups: List<GroupSpec>,
    sorts: List<SortSpec>,
    onMoveGroup: (Int, Int) -> Unit,
    onMoveSort: (Int, Int) -> Unit,
    tableNamesByAlias: Map<String, String>,
    modifier: Modifier = Modifier,
) {
    val groupLabels = groupingOrderLabels(groups, tableNamesByAlias)
    val sortLabels = sorts.map { sort ->
        "${tableNamesByAlias[sort.tableAlias] ?: sort.tableAlias}.${sort.column}"
    }

    Surface(
        modifier = modifier.semantics {
            val groupingDescription = groupLabels
                .mapIndexed { index, label -> "${index + 1} $label" }
                .joinToString(prefix = "Grouping order: ")
            val sortingDescription = sortOrderLabels(sorts, tableNamesByAlias)
                .mapIndexed { index, label -> "${index + 1} $label" }
                .joinToString(prefix = "Sorting order: ")
            contentDescription = listOf(
                "Distinct rows: ${if (distinct) "on" else "off"}",
                groupingDescription.takeIf { groups.isNotEmpty() } ?: "Grouping order: none",
                sortingDescription.takeIf { sorts.isNotEmpty() } ?: "Sorting order: none",
            ).joinToString(". ")
        },
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .heightIn(max = QueryControlsMaxHeight)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Text(
                "Query options",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 5.dp)
                    .toggleable(
                        value = distinct,
                        role = Role.Checkbox,
                        onValueChange = onDistinctChange,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = distinct,
                    onCheckedChange = null,
                    modifier = Modifier.size(28.dp),
                )
                Text(
                    "Distinct rows",
                    style = MaterialTheme.typography.labelSmall,
                    color = SafeDbTheme.colors.actionPrimary,
                    modifier = Modifier.padding(start = 5.dp),
                )
            }
            QueryOrderSection(
                title = "Group by",
                labels = groupLabels,
                onMove = onMoveGroup,
                separated = true,
            )
            QueryOrderSection(
                title = "Sort by",
                labels = sortLabels,
                directions = sorts.map { it.direction },
                onMove = onMoveSort,
                separated = true,
            )
        }
    }
}

@Composable
@OptIn(ExperimentalComposeUiApi::class)
private fun QueryOrderSection(
    title: String,
    labels: List<String>,
    directions: List<SortDirection>? = null,
    onMove: (Int, Int) -> Unit,
    separated: Boolean = false,
) {
    var draggedIndex by remember { mutableStateOf<Int?>(null) }
    var hoveredIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val dragStepPx = with(LocalDensity.current) { 20.dp.toPx() }
    val currentLastIndex by rememberUpdatedState(labels.lastIndex)
    val currentOnMove by rememberUpdatedState(onMove)

    Text(
        title,
        style = MaterialTheme.typography.labelSmall,
        color = SafeDbTheme.colors.actionPrimary,
        modifier = Modifier.padding(top = if (separated) 5.dp else 4.dp),
    )
    queryOptionEmptyLabel(labels)?.let { emptyLabel ->
        Text(
            emptyLabel,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    labels.forEachIndexed { index, label ->
        val rowBackground = when (index) {
            draggedIndex -> SafeDbTheme.colors.accentContainer.copy(alpha = 0.7f)
            hoveredIndex -> MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.65f)
            else -> Color.Transparent
        }
        Row(
            modifier = Modifier
                .padding(top = 2.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(3.dp))
                .background(rowBackground)
                .onPointerEvent(PointerEventType.Enter) { hoveredIndex = index }
                .onPointerEvent(PointerEventType.Exit) {
                    if (hoveredIndex == index) hoveredIndex = null
                }
                .padding(horizontal = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(
                Icons.Default.DragHandle,
                contentDescription = "Drag to reorder $label",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(16.dp)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = {
                                draggedIndex = index
                                dragOffset = 0f
                            },
                            onDragEnd = {
                                draggedIndex = null
                                dragOffset = 0f
                            },
                            onDragCancel = {
                                draggedIndex = null
                                dragOffset = 0f
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                dragOffset += amount.y
                                var current = draggedIndex ?: return@detectDragGestures
                                while (dragOffset >= dragStepPx) {
                                    val target = queryOrderMoveTarget(current, 1, currentLastIndex) ?: break
                                    currentOnMove(current, target)
                                    current = target
                                    draggedIndex = current
                                    dragOffset -= dragStepPx
                                }
                                while (dragOffset <= -dragStepPx) {
                                    val target = queryOrderMoveTarget(current, -1, currentLastIndex) ?: break
                                    currentOnMove(current, target)
                                    current = target
                                    draggedIndex = current
                                    dragOffset += dragStepPx
                                }
                            },
                        )
                    },
            )
            Surface(
                modifier = Modifier.size(18.dp),
                shape = RoundedCornerShape(50),
                color = SafeDbTheme.colors.accentContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        "${index + 1}",
                        style = MaterialTheme.typography.labelSmall,
                        color = SafeDbTheme.colors.actionPrimary,
                    )
                }
            }
            directions?.get(index)?.let { direction ->
                Text(
                    if (direction == SortDirection.Asc) "↑" else "↓",
                    style = MaterialTheme.typography.labelMedium,
                    color = SafeDbTheme.colors.actionPrimary,
                )
            }
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            QueryOrderMoveAction(
                icon = Icons.Default.KeyboardArrowUp,
                contentDescription = "Move $label up in $title",
                targetIndex = queryOrderMoveTarget(index, -1, labels.lastIndex),
                onMove = { target -> onMove(index, target) },
            )
            QueryOrderMoveAction(
                icon = Icons.Default.KeyboardArrowDown,
                contentDescription = "Move $label down in $title",
                targetIndex = queryOrderMoveTarget(index, 1, labels.lastIndex),
                onMove = { target -> onMove(index, target) },
            )
        }
    }
}

internal fun queryOrderMoveTarget(index: Int, offset: Int, lastIndex: Int): Int? {
    if (index !in 0..lastIndex) return null
    return (index + offset).takeIf { it in 0..lastIndex }
}

@Composable
private fun QueryOrderMoveAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    targetIndex: Int?,
    onMove: (Int) -> Unit,
) {
    Box(
        modifier = Modifier
            .size(20.dp)
            .clickable(
                enabled = targetIndex != null,
                role = Role.Button,
                onClick = { targetIndex?.let(onMove) },
            )
            .pointerHoverIcon(if (targetIndex != null) PointerIcon.Hand else PointerIcon.Default),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription.takeIf { targetIndex != null },
            modifier = Modifier.size(14.dp),
            tint = if (targetIndex != null) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
            },
        )
    }
}

private fun costGuardDialogCopy(reason: String?): CostGuardDialogCopy {
    val normalized = reason.orEmpty()
    val highCost = normalized.contains("Estimated query cost exceeds threshold")
    return if (highCost) {
        CostGuardDialogCopy(
            title = "This query may scan more data than expected",
            message = "Safe DB estimated this query may be expensive. It will still be limited and stopped if it runs too long.",
            confirmLabel = "Run with safeguards",
        )
    } else {
        CostGuardDialogCopy(
            title = "Safe DB could not preview this query",
            message = "The database did not return a usable estimate. The query will still run with Safe DB protections: read-only access, a row limit, and a timeout.",
            confirmLabel = "Run with safeguards",
        )
    }
}

@Composable
private fun LimitChoiceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val c = SafeDbTheme.colors
    val background = when {
        selected -> MaterialTheme.colorScheme.surface
        hovered -> MaterialTheme.colorScheme.surfaceContainerLow
        else -> Color.Transparent
    }
    val content = if (selected) c.actionPrimary else MaterialTheme.colorScheme.onSurfaceVariant

    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = content,
        modifier = Modifier
            .clip(ChipShape)
            .background(background)
            .border(
                1.dp,
                if (selected) c.actionPrimary else Color.Transparent,
                ChipShape,
            )
            .hoverable(interactionSource)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp),
    )
}

@Composable
fun BuilderScreen(
    connection: ConnectionDef?,
    connections: List<ConnectionDef>,
    queryViewModel: QueryViewModel,
    savedQueriesViewModel: SavedQueriesViewModel,
    recipesViewModel: RecipesViewModel,
    schemaViewModel: SchemaViewModel,
    onOpenExplore: () -> Unit,
    onApplyRecipe: (ExploreRecipe, ConnectionDef) -> Unit,
    onCancelQueryRun: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showCostGuardConfirm by remember { mutableStateOf(false) }
    var showSavePrompt by remember { mutableStateOf(false) }
    var showWarningMuteConfirm by remember { mutableStateOf(false) }
    var saveQueryName by remember { mutableStateOf("") }
    var resultsHeight by remember { mutableFloatStateOf(240f) }
    var resultsPaneMode by remember { mutableStateOf(ResultsPaneMode.Normal) }
    var resizing by remember { mutableStateOf(false) }
    val limitChoices = BUILDER_LIMIT_CHOICES

    LaunchedEffect(queryViewModel.pendingCostGuard) {
        if (queryViewModel.pendingCostGuard) {
            showCostGuardConfirm = true
        }
    }

    LaunchedEffect(connection?.id) {
        val connectionId = connection?.id
        if (connectionId != null &&
            schemaViewModel.loadedConnectionId != connectionId &&
            !schemaViewModel.loading
        ) {
            schemaViewModel.load(connectionId)
        }
    }

    val costGuardCopy = costGuardDialogCopy(queryViewModel.pendingCostGuardReason)
    val visibleQueryError = if (queryViewModel.pendingCostGuard) null else queryViewModel.error
    val savedQueryError by savedQueriesViewModel.error.collectAsState()
    val showLargeLimitGuidance = connection != null &&
        queryViewModel.canvasTables.isNotEmpty() &&
        queryViewModel.limit > LARGE_LIMIT_WARNING_THRESHOLD

    if (showCostGuardConfirm) {
        AlertDialog(
            onDismissRequest = {
                showCostGuardConfirm = false
                queryViewModel.dismissError()
                onCancelQueryRun()
            },
            shape = RoundedCornerShape(4.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            title = { Text(costGuardCopy.title, style = MaterialTheme.typography.titleMedium) },
            text = { Text(costGuardCopy.message) },
            confirmButton = {
                PrimaryButton(
                    onClick = {
                        showCostGuardConfirm = false
                        queryViewModel.confirmPendingCostGuard()
                    },
                ) {
                    Text(costGuardCopy.confirmLabel)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showCostGuardConfirm = false
                        queryViewModel.dismissError()
                        onCancelQueryRun()
                    },
                ) {
                    Text("Cancel")
                }
            },
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
                ),
            ) {
                showSavePrompt = false
            }
        },
        onCancel = { showSavePrompt = false },
    )

    if (showWarningMuteConfirm) {
        AlertDialog(
            onDismissRequest = { showWarningMuteConfirm = false },
            shape = RoundedCornerShape(4.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            title = { Text("Mute cost warnings?", style = MaterialTheme.typography.titleMedium) },
            text = {
                Text(
                    "Safe DB can stop popping up cost warnings for this session. The safeguards stay on: read-only checks, row limits, and timeouts still apply.",
                )
            },
            confirmButton = {
                PrimaryButton(
                    onClick = {
                        queryViewModel.updateWarningPopupsDisabled(true)
                        showWarningMuteConfirm = false
                    },
                ) {
                    Text("Mute warnings")
                }
            },
            dismissButton = {
                SecondaryButton(onClick = { showWarningMuteConfirm = false }) {
                    Text("Keep warning me")
                }
            },
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .then(
                if (resizing) {
                    Modifier.pointerInput(Unit) {
                        detectDragGestures(
                            onDragEnd = { resizing = false },
                            onDragCancel = { resizing = false },
                            onDrag = { change, _ ->
                                resultsHeight = (resultsHeight - change.position.y).coerceIn(100f, 600f)
                            },
                        )
                    }
                } else {
                    Modifier
                },
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SafeDbTheme.colors.workspaceHeader)
                .padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (connection != null) {
                Column {
                    Text(connection.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${dialectLabel(connection.dialect)} · ${connection.database}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Text("Query Builder", style = MaterialTheme.typography.titleLarge)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
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
                            saveQueryName = "Query on " +
                                queryViewModel.canvasTables.joinToString(", ") { it.tableInfo.name }
                            showSavePrompt = true
                        },
                    ) {
                        Text("Save")
                    }
                    TextButton(onClick = { queryViewModel.clear() }) {
                        Text("Clear")
                    }
                }
                ToolbarTooltipIconButton(
                    label = if (queryViewModel.warningPopupsDisabled) "Warnings off" else "Warnings on",
                    icon = Icons.Default.WarningAmber,
                    onClick = {
                        if (queryViewModel.warningPopupsDisabled) {
                            queryViewModel.updateWarningPopupsDisabled(false)
                        } else {
                            showWarningMuteConfirm = true
                        }
                    },
                    highlighted = !queryViewModel.warningPopupsDisabled,
                )
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
                    enabled = queryViewModel.canRun &&
                        connection != null &&
                        schemaViewModel.schema != null &&
                        schemaViewModel.loadedConnectionId == connection.id &&
                        !queryViewModel.running,
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
                text = "Large result limit. Higher limits are useful for reporting, but filters, selected columns, and indexed predicates make queries faster and easier to reuse.",
                kind = BannerKind.INFO,
            )
        }

        savedQueryError?.let { error ->
            MessageBanner(
                text = error,
                kind = BannerKind.ERROR,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
        }

        if (connection == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Connect to a database to start building queries.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Row(modifier = Modifier.fillMaxSize()) {
                Surface(
                    modifier = Modifier
                        .width(288.dp)
                        .fillMaxHeight(),
                    color = SafeDbTheme.colors.workspacePanel,
                    tonalElevation = 0.dp,
                ) {
                    SchemaBrowser(
                        schemaViewModel = schemaViewModel,
                        onAddTable = { queryViewModel.addTable(it) },
                    )
                }
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.outline),
                )

                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(SafeDbTheme.colors.workspaceCanvas),
                ) {
                    val maxWorkspaceHeight = maxHeight.value.coerceAtLeast(ResultsPaneMinHeight)
                    val maxNormalHeight = maxWorkspaceHeight
                        .coerceAtMost(ResultsPaneMaxHeight)
                        .coerceAtLeast(ResultsPaneMinHeight)
                    val resultsPaneHeight = when (resultsPaneMode) {
                        ResultsPaneMode.Normal -> resultsHeight.coerceIn(ResultsPaneMinHeight, maxNormalHeight)
                        ResultsPaneMode.Maximized -> maxWorkspaceHeight
                    }
                    val resultsMaximized = resultsPaneMode == ResultsPaneMode.Maximized

                    Column(modifier = Modifier.fillMaxSize()) {
                        if (!resultsMaximized) {
                            queryViewModel.hydrationWarning?.let { warning ->
                                MessageBanner(
                                    text = warning,
                                    kind = BannerKind.WARNING,
                                ) {
                                    TextButton(onClick = { queryViewModel.dismissHydrationWarning() }) {
                                        Text("Dismiss")
                                    }
                                }
                            }

                            if (queryViewModel.joins.isNotEmpty()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    queryViewModel.joins.forEachIndexed { index, join ->
                                        val leftName = queryViewModel.canvasTables
                                            .find { it.alias == join.leftAlias }?.tableInfo?.name ?: join.leftAlias
                                        val rightName = queryViewModel.canvasTables
                                            .find { it.alias == join.rightAlias }?.tableInfo?.name ?: join.rightAlias
                                        Surface(
                                            color = SafeDbTheme.colors.accentContainer.copy(alpha = 0.7f),
                                            shape = RoundedCornerShape(50),
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Text(
                                                    "join: $leftName.${join.leftColumn} = $rightName.${join.rightColumn}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                )
                                                IconButton(
                                                    onClick = { queryViewModel.removeJoin(index) },
                                                    modifier = Modifier.size(28.dp),
                                                ) {
                                                    Icon(
                                                        Icons.Default.Close,
                                                        contentDescription = "Remove join",
                                                        modifier = Modifier.size(16.dp),
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                            ) {
                                if (queryViewModel.canvasTables.isEmpty()) {
                                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Text(
                                            "Click + next to a table in the sidebar to add it.",
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                } else {
                                    Canvas(
                                        queryViewModel = queryViewModel,
                                        contentTopInset = QueryControlsCanvasInset,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                    Row(
                                        modifier = Modifier
                                            .align(Alignment.TopCenter)
                                            .fillMaxWidth()
                                            .padding(
                                                horizontal = 16.dp,
                                                vertical = QueryControlsVerticalPadding,
                                            ),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        verticalAlignment = Alignment.Top,
                                    ) {
                                        Box(modifier = Modifier.weight(1f)) {
                                            if (queryViewModel.filterCount > 0) {
                                                FilterBuilder(
                                                    queryViewModel = queryViewModel,
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .heightIn(max = QueryControlsMaxHeight)
                                                        .verticalScroll(rememberScrollState())
                                                        .horizontalScroll(rememberScrollState()),
                                                )
                                            }
                                        }
                                        QueryOptionsCard(
                                            distinct = queryViewModel.distinct,
                                            onDistinctChange = queryViewModel::setDistinct,
                                            groups = queryViewModel.groups,
                                            sorts = queryViewModel.sorts,
                                            onMoveGroup = queryViewModel::moveGroup,
                                            onMoveSort = queryViewModel::moveSort,
                                            tableNamesByAlias = queryViewModel.canvasTables.associate {
                                                it.alias to it.tableInfo.name
                                            },
                                            modifier = Modifier.widthIn(min = 208.dp, max = 256.dp),
                                        )
                                    }
                                }
                            }

                            visibleQueryError?.let { error ->
                                MessageBanner(
                                    text = error,
                                    kind = BannerKind.ERROR,
                                )
                            }
                        }

                        queryViewModel.results?.let { result ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(resultsPaneHeight.dp),
                            ) {
                                ResultsPaneResizeBar(
                                    mode = resultsPaneMode,
                                    resizing = resizing,
                                    onToggleMode = {
                                        val nextState = toggleResultsPane(resultsPaneMode, resultsHeight)
                                        resultsPaneMode = nextState.mode
                                        resultsHeight = nextState.height
                                    },
                                    onDragStart = {
                                        resizing = true
                                        resultsHeight = when (resultsPaneMode) {
                                            ResultsPaneMode.Normal -> resultsHeight
                                            ResultsPaneMode.Maximized -> maxNormalHeight
                                        }.coerceIn(ResultsPaneMinHeight, maxNormalHeight)
                                        resultsPaneMode = ResultsPaneMode.Normal
                                    },
                                    onDragEnd = {
                                        resizing = false
                                    },
                                    onDrag = { dragY ->
                                        resultsHeight = (resultsHeight - dragY)
                                            .coerceIn(ResultsPaneMinHeight, maxNormalHeight)
                                    },
                                )
                                ResultsTable(
                                    result = result,
                                    modifier = Modifier.fillMaxSize(),
                                ) {
                                    PrimaryButton(
                                        onClick = onOpenExplore,
                                    ) {
                                        Text("Explore")
                                    }
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
    val barColor = if (active) {
        c.accentContainer.copy(alpha = 0.85f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }
    val gripColor = if (active) c.actionPrimary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.58f)
    val pillColor = if (active) c.actionPrimary else MaterialTheme.colorScheme.surfaceContainerHigh
    val pillContent = if (active) c.onActionPrimary else MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = Modifier
            .fillMaxWidth()
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
            modifier = Modifier
                .fillMaxWidth()
                .height(ResultsPaneHandleHeight.dp)
                .padding(horizontal = 18.dp),
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
            modifier = Modifier
                .clip(RoundedCornerShape(2.dp))
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

private const val ResultsPaneMinHeight = 128f
private const val ResultsPaneMaxHeight = 640f
private const val ResultsPaneHandleHeight = 18f
