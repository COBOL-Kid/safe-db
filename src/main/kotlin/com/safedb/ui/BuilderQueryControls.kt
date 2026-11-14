package com.safedb.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.safedb.AppRoute
import com.safedb.model.GroupSpec
import com.safedb.model.JoinSpec
import com.safedb.model.SortDirection
import com.safedb.model.SortSpec
import com.safedb.query.DEFAULT_LIMIT
import com.safedb.query.LARGE_LIMIT_WARNING_THRESHOLD
import com.safedb.query.parseLimit
import com.safedb.ui.components.BannerKind
import com.safedb.ui.components.MessageBanner
import com.safedb.ui.components.SecondaryButton
import com.safedb.ui.theme.ChipShape
import com.safedb.ui.theme.DialogShape
import com.safedb.ui.theme.SafeDbTheme
import com.safedb.ui.util.limitFieldText
import com.safedb.viewmodel.QueryViewModel

internal const val BUILDER_ALL_LIMIT_LABEL = "ALL"

internal val BUILDER_LIMIT_CHOICES = listOf(DEFAULT_LIMIT, LARGE_LIMIT_WARNING_THRESHOLD, 5_000)

internal fun builderLimitChoiceLabel(choice: Int): String = "%,d".format(choice)

/** Null keeps the committed limit. Blank and zero are not a new cap. */
internal fun commitBuilderLimitDraft(text: String): Int? {
    val digits = text.filter { it.isDigit() }
    if (digits.isEmpty() || digits.all { it == '0' }) return null
    return parseLimit(digits)
}

internal val QueryControlsMaxHeight = 208.dp
internal val QueryControlsVerticalPadding = 8.dp
internal val QueryStartOverlayGap = 6.dp
internal val QueryControlsStartPadding = 16.dp
internal val QueryOptionsPanelMinWidth = 208.dp
internal val QueryOptionsPanelMaxWidth = 256.dp
internal val QueryOptionsPanelEndPadding = 16.dp
internal val JoinItemsMaxHeight = 88.dp
internal val BuilderSchemaBrowserWidth = 288.dp
internal val BuilderSchemaBrowserDividerWidth = 1.dp
internal val BuilderToolbarConnectionMinWidth = 160.dp
internal val BuilderQueryOptionsButtonReserve = 48.dp
internal val BuilderFilterPanelMinHeight = 96.dp

internal fun builderFilterPanelMaxHeight(workspaceHeight: Dp): Dp {
    val budget =
        (workspaceHeight - BuilderQueryOptionsButtonReserve - CANVAS_MIN_VISIBLE_CONTENT_PX.dp)
            .coerceAtLeast(BuilderFilterPanelMinHeight)
    return minOf(QueryControlsMaxHeight, budget)
}

@Composable
internal fun BuilderQueryChrome(
    queryViewModel: QueryViewModel,
    showLargeLimitGuidance: Boolean,
    workspaceHeight: Dp,
    modifier: Modifier = Modifier,
) {
    var showQueryOptions by remember { mutableStateOf(false) }
    val tableNamesByAlias = queryViewModel.canvasTables.associate { it.alias to it.tableInfo.name }

    Column(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .background(SafeDbTheme.colors.workspacePanel)
                    .padding(
                        start = QueryControlsStartPadding,
                        end = QueryOptionsPanelEndPadding,
                        top = QueryControlsVerticalPadding,
                        bottom = QueryControlsVerticalPadding,
                    )
                    .testTag("builder-query-chrome"),
            verticalArrangement = Arrangement.spacedBy(QueryStartOverlayGap),
        ) {
            if (showLargeLimitGuidance) {
                MessageBanner(
                    text = LARGE_LIMIT_GUIDANCE_TEXT,
                    kind = BannerKind.INFO,
                    modifier =
                        Modifier.semantics { contentDescription = LARGE_LIMIT_GUIDANCE_TEXT },
                )
            }
            if (queryViewModel.filterCount > 0) {
                val filterPanelHeight = builderFilterPanelMaxHeight(workspaceHeight)
                Box(
                    modifier =
                        Modifier.fillMaxWidth()
                            .requiredHeightIn(
                                min = BuilderFilterPanelMinHeight,
                                max = filterPanelHeight,
                            )
                            .clipToBounds()
                            .testTag("builder-filter-panel")
                            .semantics { contentDescription = "Query filters" },
                ) {
                    FilterGroupCard(
                        queryViewModel = queryViewModel,
                        group = queryViewModel.filters,
                        path = emptyList(),
                        depth = 0,
                        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = { showQueryOptions = true },
                    modifier = Modifier.testTag("builder-query-options"),
                ) {
                    Text("Query options", softWrap = false)
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }

    BuilderQueryOptionsDialog(
        open = showQueryOptions,
        onDismiss = { showQueryOptions = false },
        queryViewModel = queryViewModel,
        tableNamesByAlias = tableNamesByAlias,
    )
}

@Composable
internal fun BuilderQueryOptionsDialog(
    open: Boolean,
    onDismiss: () -> Unit,
    queryViewModel: QueryViewModel,
    tableNamesByAlias: Map<String, String>,
) {
    if (!open) return
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = DialogShape,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("Query options", style = MaterialTheme.typography.titleMedium) },
        text = {
            BuilderQueryOptionsAndJoins(
                queryViewModel = queryViewModel,
                tableNamesByAlias = tableNamesByAlias,
                showHeading = false,
                modifier =
                    Modifier.widthIn(
                            min = QueryOptionsPanelMinWidth,
                            max = QueryOptionsPanelMaxWidth,
                        )
                        .testTag("builder-query-options-dialog"),
            )
        },
        confirmButton = { SecondaryButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
internal fun BuilderQueryOptionsAndJoins(
    queryViewModel: QueryViewModel,
    tableNamesByAlias: Map<String, String>,
    modifier: Modifier = Modifier,
    showHeading: Boolean = true,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        QueryOptionsCard(
            distinct = queryViewModel.distinct,
            onDistinctChange = queryViewModel::setDistinct,
            groups = queryViewModel.groups,
            sorts = queryViewModel.sorts,
            distinctSortConflicts = queryViewModel.distinctSortConflicts,
            onSelectDistinctSortColumns = queryViewModel::selectDistinctSortColumns,
            onRemoveDistinctSortConflicts = queryViewModel::removeDistinctSortConflicts,
            onMoveGroup = queryViewModel::moveGroup,
            onMoveSort = queryViewModel::moveSort,
            tableNamesByAlias = tableNamesByAlias,
            showHeading = showHeading,
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

internal enum class ResultsPaneMode {
    Normal,
    Maximized,
}

internal data class ResultsPaneState(val mode: ResultsPaneMode, val height: Float)

internal fun toggleResultsPane(mode: ResultsPaneMode, height: Float): ResultsPaneState =
    if (mode == ResultsPaneMode.Maximized) {
        ResultsPaneState(ResultsPaneMode.Normal, ResultsPaneMinHeight)
    } else {
        ResultsPaneState(ResultsPaneMode.Maximized, height)
    }

internal const val RESULTS_PANE_DEFAULT_HEIGHT = 240f
internal const val RESULTS_PANE_EXPANDED_MIN_HEIGHT = 320f

internal val BuilderResultsArriveReservePx =
    BuilderFilterPanelMinHeight.value +
        BuilderQueryOptionsButtonReserve.value +
        CANVAS_MIN_VISIBLE_CONTENT_PX +
        QueryControlsVerticalPadding.value * 2 +
        QueryStartOverlayGap.value

internal fun resultsPaneHeightWhenResultsArrive(
    currentHeight: Float,
    workspaceHeight: Float,
): Float {
    val expanded = maxOf(RESULTS_PANE_EXPANDED_MIN_HEIGHT, workspaceHeight * 0.4f)
    val maxHeight = minOf(ResultsPaneMaxHeight, workspaceHeight.coerceAtLeast(ResultsPaneMinHeight))
    val target = maxOf(currentHeight, expanded).coerceIn(ResultsPaneMinHeight, maxHeight)
    if (currentHeight >= target) return target
    val reservedMax =
        (workspaceHeight - BuilderResultsArriveReservePx).coerceAtLeast(ResultsPaneMinHeight)
    return minOf(target, reservedMax)
}

internal const val LARGE_LIMIT_GUIDANCE_TEXT =
    "Large limit: add filters or pick columns to keep queries fast."

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

internal fun joinLabel(join: JoinSpec, tableNamesByAlias: Map<String, String>): String {
    val leftName = tableNamesByAlias[join.leftAlias] ?: join.leftAlias
    val rightName = tableNamesByAlias[join.rightAlias] ?: join.rightAlias
    return "join: $leftName.${join.leftColumn} = $rightName.${join.rightColumn}"
}

internal fun queryOptionEmptyLabel(labels: List<String>): String? =
    "None".takeIf { labels.isEmpty() }

internal enum class BuilderConnectionSwitchDecision {
    NoOp,
    SwitchImmediately,
    ConfirmClear,
}

internal fun builderConnectionSwitchDecision(
    activeConnectionId: String?,
    targetConnectionId: String,
    hasDraft: Boolean,
): BuilderConnectionSwitchDecision =
    when {
        activeConnectionId == targetConnectionId -> BuilderConnectionSwitchDecision.NoOp
        hasDraft -> BuilderConnectionSwitchDecision.ConfirmClear
        else -> BuilderConnectionSwitchDecision.SwitchImmediately
    }

internal data class ConnectionSwitchPlan(
    val switchNow: Boolean,
    val awaitConfirm: Boolean,
    val navigateOnCommit: AppRoute?,
)

internal fun planConnectionSwitch(
    decision: BuilderConnectionSwitchDecision,
    thenNavigate: AppRoute?,
): ConnectionSwitchPlan =
    when (decision) {
        BuilderConnectionSwitchDecision.NoOp ->
            ConnectionSwitchPlan(
                switchNow = false,
                awaitConfirm = false,
                navigateOnCommit = thenNavigate,
            )
        BuilderConnectionSwitchDecision.SwitchImmediately ->
            ConnectionSwitchPlan(
                switchNow = true,
                awaitConfirm = false,
                navigateOnCommit = thenNavigate,
            )
        BuilderConnectionSwitchDecision.ConfirmClear ->
            ConnectionSwitchPlan(
                switchNow = false,
                awaitConfirm = true,
                navigateOnCommit = thenNavigate,
            )
    }

@Composable
internal fun QueryOptionsCard(
    distinct: Boolean,
    onDistinctChange: (Boolean) -> Unit,
    groups: List<GroupSpec>,
    sorts: List<SortSpec>,
    distinctSortConflicts: List<SortSpec>,
    onSelectDistinctSortColumns: () -> Unit,
    onRemoveDistinctSortConflicts: () -> Unit,
    onMoveGroup: (Int, Int) -> Unit,
    onMoveSort: (Int, Int) -> Unit,
    tableNamesByAlias: Map<String, String>,
    modifier: Modifier = Modifier,
    showHeading: Boolean = true,
) {
    val distinctInteractionSource = remember { MutableInteractionSource() }
    val groupLabels = groupingOrderLabels(groups, tableNamesByAlias)
    val sortLabels = sorts.map { sort ->
        "${tableNamesByAlias[sort.tableAlias] ?: sort.tableAlias}.${sort.column}"
    }
    val distinctSortConflictKeys =
        distinctSortConflicts.mapTo(mutableSetOf()) { it.tableAlias to it.column }
    val distinctSortConflictIndices =
        sorts.indices.filterTo(mutableSetOf()) { index ->
            val sort = sorts[index]
            (sort.tableAlias to sort.column) in distinctSortConflictKeys
        }
    val distinctSortConflictLabels = distinctSortConflicts.map { sort ->
        "${tableNamesByAlias[sort.tableAlias] ?: sort.tableAlias}.${sort.column}"
    }

    Surface(
        modifier = modifier.semantics { contentDescription = "Query options" },
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier =
                Modifier.semantics {
                        val groupingDescription =
                            groupLabels
                                .mapIndexed { index, label -> "${index + 1} $label" }
                                .joinToString(prefix = "Grouping order: ")
                        val sortingDescription =
                            sortOrderLabels(sorts, tableNamesByAlias)
                                .mapIndexed { index, label -> "${index + 1} $label" }
                                .joinToString(prefix = "Sorting order: ")
                        contentDescription =
                            buildList {
                                    add("Distinct rows: ${if (distinct) "on" else "off"}")
                                    add(
                                        groupingDescription.takeIf { groups.isNotEmpty() }
                                            ?: "Grouping order: none",
                                    )
                                    add(
                                        sortingDescription.takeIf { sorts.isNotEmpty() }
                                            ?: "Sorting order: none",
                                    )
                                    if (distinctSortConflicts.isNotEmpty()) {
                                        add(
                                            "Distinct rows cannot sort by unselected columns: ${distinctSortConflictLabels.joinToString()}",
                                        )
                                    }
                                }
                                .joinToString(". ")
                    }
                    .heightIn(max = QueryControlsMaxHeight)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            if (showHeading) {
                Text(
                    "Query options",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Row(
                modifier =
                    Modifier.fillMaxWidth()
                        .padding(top = 5.dp)
                        .toggleable(
                            value = distinct,
                            interactionSource = distinctInteractionSource,
                            indication = null,
                            role = Role.Checkbox,
                            onValueChange = onDistinctChange,
                        ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CompactSelectionIndicator(if (distinct) ToggleableState.On else ToggleableState.Off)
                Text(
                    "Distinct rows",
                    style = MaterialTheme.typography.labelSmall,
                    color = SafeDbTheme.colors.actionPrimary,
                    modifier = Modifier.padding(start = 5.dp),
                )
            }
            if (distinctSortConflicts.isNotEmpty()) {
                DistinctSortProjectionWarning(
                    onSelectColumns = onSelectDistinctSortColumns,
                    onRemoveSorts = onRemoveDistinctSortConflicts,
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
                warningIndices = distinctSortConflictIndices,
                separated = true,
            )
        }
    }
}

@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
internal fun JoinItems(
    joins: List<JoinSpec>,
    tableNamesByAlias: Map<String, String>,
    onRemove: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier.heightIn(max = JoinItemsMaxHeight).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        joins.forEachIndexed { index, join ->
            val label = joinLabel(join, tableNamesByAlias)
            TooltipBox(
                positionProvider =
                    TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                tooltip = { PlainTooltip { Text(label) } },
                state = rememberTooltipState(),
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = SafeDbTheme.colors.accentContainer.copy(alpha = 0.7f),
                    shape = ChipShape,
                    tonalElevation = 0.dp,
                ) {
                    Row(
                        modifier =
                            Modifier.padding(start = 8.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Box(
                            modifier =
                                Modifier.size(22.dp)
                                    .clickable(role = Role.Button, onClick = { onRemove(index) })
                                    .pointerHoverIcon(PointerIcon.Hand),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Remove $label",
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun DistinctSortProjectionWarning(onSelectColumns: () -> Unit, onRemoveSorts: () -> Unit) {
    val colors = SafeDbTheme.colors
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 5.dp),
        shape = RoundedCornerShape(3.dp),
        color = colors.warningContainer,
        tonalElevation = 0.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(
                "Distinct rows cannot sort by unselected columns.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onWarningContainer,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onSelectColumns) {
                    Text("Select columns", style = MaterialTheme.typography.labelSmall)
                }
                TextButton(onClick = onRemoveSorts) {
                    Text("Remove sorts", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalComposeUiApi::class)
internal fun QueryOrderSection(
    title: String,
    labels: List<String>,
    directions: List<SortDirection>? = null,
    onMove: (Int, Int) -> Unit,
    warningIndices: Set<Int> = emptySet(),
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
        val rowBackground =
            when (index) {
                draggedIndex -> SafeDbTheme.colors.accentContainer.copy(alpha = 0.7f)
                in warningIndices -> SafeDbTheme.colors.warningContainer.copy(alpha = 0.85f)
                hoveredIndex -> MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.65f)
                else -> Color.Transparent
            }
        Row(
            modifier =
                Modifier.padding(top = 2.dp)
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
                modifier =
                    Modifier.size(16.dp).pointerHoverIcon(PointerIcon.Hand).pointerInput(Unit) {
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
                                    val target =
                                        queryOrderMoveTarget(current, 1, currentLastIndex) ?: break
                                    currentOnMove(current, target)
                                    current = target
                                    draggedIndex = current
                                    dragOffset -= dragStepPx
                                }
                                while (dragOffset <= -dragStepPx) {
                                    val target =
                                        queryOrderMoveTarget(current, -1, currentLastIndex) ?: break
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
                color =
                    if (index in warningIndices) {
                        SafeDbTheme.colors.onWarningContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
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
        modifier =
            Modifier.size(20.dp)
                .clickable(
                    enabled = targetIndex != null,
                    role = Role.Button,
                    onClick = { targetIndex?.let(onMove) },
                )
                .pointerHoverIcon(
                    if (targetIndex != null) PointerIcon.Hand else PointerIcon.Default,
                ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription.takeIf { targetIndex != null },
            modifier = Modifier.size(14.dp),
            tint =
                if (targetIndex != null) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
                },
        )
    }
}

@Composable
internal fun LimitValueField(
    limit: Int,
    uncapped: Boolean,
    onCommit: (Int) -> Unit,
) {
    var draft by remember { mutableStateOf(limitFieldText(limit, uncapped)) }
    var focused by remember { mutableStateOf(false) }
    val textStyle =
        MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurface)
    BasicTextField(
        value = if (focused) draft else limitFieldText(limit, uncapped),
        onValueChange = { raw -> draft = raw.filter { it.isDigit() } },
        singleLine = true,
        textStyle = textStyle,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier =
            Modifier.width(120.dp)
                .height(30.dp)
                .onFocusChanged { state ->
                    if (!focused && state.isFocused) {
                        draft = limitFieldText(limit, uncapped)
                    }
                    if (focused && !state.isFocused) {
                        commitBuilderLimitDraft(draft)?.let(onCommit)
                    }
                    focused = state.isFocused
                }
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp))
                .padding(horizontal = 8.dp, vertical = 7.dp)
                .semantics { contentDescription = "Row limit" },
    )
}

@Composable
internal fun LimitChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val c = SafeDbTheme.colors
    val background =
        when {
            selected -> MaterialTheme.colorScheme.surface
            hovered -> MaterialTheme.colorScheme.surfaceContainerLow
            else -> Color.Transparent
        }
    val content = if (selected) c.actionPrimary else MaterialTheme.colorScheme.onSurfaceVariant

    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = content,
        modifier =
            Modifier.clip(ChipShape)
                .background(background)
                .border(1.dp, if (selected) c.actionPrimary else Color.Transparent, ChipShape)
                .hoverable(interactionSource)
                .clickable(onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 5.dp),
    )
}
