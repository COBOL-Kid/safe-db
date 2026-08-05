package com.safedb.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.safedb.model.SortDirection
import com.safedb.query.CANVAS_HEADER_HEIGHT
import com.safedb.query.CANVAS_RESIZE_FOOTER_HEIGHT
import com.safedb.query.CANVAS_ROW_HEIGHT
import com.safedb.query.opLabel
import com.safedb.query.opsForColumn
import com.safedb.ui.components.MenuActionRow
import com.safedb.ui.components.MenuSectionLabel
import com.safedb.ui.components.SafeDropdownMenu
import com.safedb.ui.theme.DataMono
import com.safedb.ui.theme.SafeDbTheme
import com.safedb.viewmodel.CanvasTable
import com.safedb.viewmodel.QueryViewModel

@Composable
@OptIn(ExperimentalComposeUiApi::class)
fun TableCard(
    canvasTable: CanvasTable,
    queryViewModel: QueryViewModel,
    onStartDrag: () -> Unit,
    onDragTable: (Offset) -> Unit,
    onStartJoin: (String) -> Unit,
    onDragJoin: (Offset) -> Unit,
    onJoinClick: (String) -> Unit,
    onJoinTargetClick: (String, String) -> Unit,
    onStartResize: () -> Unit,
    onResizeTable: (Offset) -> Unit,
    onEndGesture: () -> Unit,
    fieldScrollState: ScrollState = rememberScrollState(),
    joinDragActive: Boolean = false,
    highlightJoinTargets: Pair<String, String>? = null,
    modifier: Modifier = Modifier,
) {
    val table = canvasTable.tableInfo
    val alias = canvasTable.alias
    var filterMenuColumn by remember { mutableStateOf<String?>(null) }
    var hoveredColumn by remember { mutableStateOf<String?>(null) }
    val displayHeight = canvasTable.height
    val bodyHeight =
        (displayHeight - CANVAS_HEADER_HEIGHT - CANVAS_RESIZE_FOOTER_HEIGHT).coerceAtLeast(64f)
    val resizeHandleColor = SafeDbTheme.colors.actionPrimary.copy(alpha = 0.84f)
    val joinColor = SafeDbTheme.colors.actionPrimary
    val joinTargetColor = SafeDbTheme.colors.accentContainer
    val selectedColumnCount =
        table.columns.count { queryViewModel.isColumnSelected(alias, it.name) }
    val tableSelectionState = tableColumnToggleState(selectedColumnCount, table.columns.size)
    val headerSelectionInteractionSource = remember { MutableInteractionSource() }

    Surface(
        modifier = modifier.width(canvasTable.width.dp).height(displayHeight.dp),
        shape = MaterialTheme.shapes.small,
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column {
            Row(
                modifier =
                    Modifier.fillMaxWidth()
                        .height(CANVAS_HEADER_HEIGHT.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Box(
                    modifier =
                        Modifier.width(19.dp)
                            .fillMaxHeight()
                            .clickable(
                                interactionSource = headerSelectionInteractionSource,
                                indication = null,
                                enabled = table.columns.isNotEmpty(),
                                onClick = { queryViewModel.toggleAllColumns(alias) },
                            )
                            .semantics {
                                contentDescription =
                                    if (tableSelectionState == ToggleableState.On) {
                                        "Clear all column selections in ${table.name}"
                                    } else {
                                        "Select all columns in ${table.name}"
                                    }
                            },
                    contentAlignment = Alignment.CenterStart,
                ) {
                    CompactSelectionIndicator(tableSelectionState)
                }
                Row(
                    modifier =
                        Modifier.weight(1f)
                            .fillMaxHeight()
                            .pointerHoverIcon(PointerIcon.Hand)
                            .semantics { contentDescription = "Move ${table.name} table" }
                            .pointerInput(alias) {
                                detectDragGestures(
                                    onDragStart = { onStartDrag() },
                                    onDragEnd = { onEndGesture() },
                                    onDragCancel = { onEndGesture() },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        onDragTable(dragAmount)
                                    },
                                )
                            },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.DragHandle,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        table.name,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(start = 5.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(
                    onClick = { queryViewModel.removeTable(alias) },
                    modifier = Modifier.size(30.dp),
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Remove table",
                        modifier = Modifier.size(19.dp),
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)

            Box(modifier = Modifier.fillMaxWidth().height(bodyHeight.dp)) {
                Column(
                    modifier =
                        Modifier.fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 3.dp)
                            .verticalScroll(fieldScrollState)
                ) {
                    for (column in table.columns) {
                        val selected = queryViewModel.isColumnSelected(alias, column.name)
                        val filterActive = queryViewModel.hasFilterForColumn(alias, column.name)
                        val group = queryViewModel.groupForColumn(alias, column.name)
                        val groupIndex = queryViewModel.groups.indexOf(group)
                        val sort = queryViewModel.sortForColumn(alias, column.name)
                        val sortIndex = queryViewModel.sorts.indexOf(sort)
                        val columnHovered = hoveredColumn == column.name
                        val columnSelectionInteractionSource =
                            remember(alias, column.name) { MutableInteractionSource() }
                        val joinTargetInteractionSource =
                            remember(alias, column.name, "join-target") {
                                MutableInteractionSource()
                            }
                        val joinTarget =
                            highlightJoinTargets != null &&
                                column.isIndexed &&
                                !(highlightJoinTargets.first == alias &&
                                    highlightJoinTargets.second == column.name)

                        Row(
                            modifier =
                                Modifier.fillMaxWidth()
                                    .height((CANVAS_ROW_HEIGHT - 1f).dp)
                                    .then(
                                        if (joinTarget && joinDragActive) {
                                            Modifier.clickable(
                                                interactionSource = joinTargetInteractionSource,
                                                indication = null,
                                                onClick = { onJoinTargetClick(alias, column.name) },
                                            )
                                        } else {
                                            Modifier
                                        }
                                    )
                                    .onPointerEvent(PointerEventType.Enter) {
                                        hoveredColumn = column.name
                                    }
                                    .onPointerEvent(PointerEventType.Exit) {
                                        if (hoveredColumn == column.name) hoveredColumn = null
                                    }
                                    .background(
                                        when {
                                            selected ->
                                                SafeDbTheme.colors.accentContainer.copy(
                                                    alpha = 0.72f
                                                )
                                            joinTarget -> joinTargetColor.copy(alpha = 0.72f)
                                            else -> MaterialTheme.colorScheme.surface
                                        },
                                        RoundedCornerShape(0.dp),
                                    )
                                    .padding(start = 6.dp, top = 2.dp, end = 4.dp, bottom = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                modifier =
                                    Modifier.weight(1f)
                                        .clickable(
                                            interactionSource = columnSelectionInteractionSource,
                                            indication = null,
                                            onClick = {
                                                if (joinTarget && joinDragActive) {
                                                    onJoinTargetClick(alias, column.name)
                                                } else {
                                                    queryViewModel.toggleColumn(alias, column.name)
                                                }
                                            },
                                        ),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CompactSelectionIndicator(
                                    if (selected) ToggleableState.On else ToggleableState.Off
                                )
                                Row(
                                    modifier = Modifier.padding(start = 6.dp).weight(1f),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        column.name,
                                        style = DataMono.copy(fontWeight = FontWeight.Medium),
                                        modifier = Modifier.weight(1f, fill = false),
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        column.dataType,
                                        style =
                                            DataMono.copy(
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                fontWeight = FontWeight.Medium,
                                            ),
                                        modifier =
                                            Modifier.padding(start = 6.dp)
                                                .weight(0.45f, fill = false),
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }

                            ExactDesktopTargetArea {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Start,
                                ) {
                                    if (
                                        columnHovered ||
                                            filterActive ||
                                            filterMenuColumn == column.name
                                    ) {
                                        Box {
                                            ColumnActionButton(
                                                icon = Icons.Default.FilterAlt,
                                                contentDescription =
                                                    if (filterActive) {
                                                        "Filter ${column.name}; this column already has a filter"
                                                    } else {
                                                        "Filter ${column.name}"
                                                    },
                                                active = filterActive,
                                                onClick = { filterMenuColumn = column.name },
                                            )
                                            SafeDropdownMenu(
                                                expanded = filterMenuColumn == column.name,
                                                onDismissRequest = { filterMenuColumn = null },
                                            ) {
                                                MenuSectionLabel("Filter where")
                                                for (op in opsForColumn(column.dataType)) {
                                                    MenuActionRow(
                                                        text = "${column.name} ${opLabel(op)}",
                                                        onClick = {
                                                            queryViewModel.addFilterForColumn(
                                                                alias,
                                                                column.name,
                                                                column.dataType,
                                                                op,
                                                            )
                                                            filterMenuColumn = null
                                                        },
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    if (columnHovered || group != null) {
                                        ColumnActionButton(
                                            icon = Icons.Default.GridView,
                                            contentDescription =
                                                groupDescription(
                                                    column.name,
                                                    groupIndex,
                                                    group != null,
                                                ),
                                            active = group != null,
                                            onClick = {
                                                queryViewModel.toggleGroup(alias, column.name)
                                            },
                                        )
                                    }
                                    if (columnHovered || sort != null) {
                                        ColumnActionButton(
                                            icon =
                                                if (sort?.direction == SortDirection.Desc) {
                                                    Icons.Default.ArrowDownward
                                                } else {
                                                    Icons.Default.ArrowUpward
                                                },
                                            contentDescription =
                                                sortDescription(
                                                    column.name,
                                                    sort?.direction,
                                                    sortIndex,
                                                ),
                                            active = sort != null,
                                            onClick = {
                                                queryViewModel.cycleSort(alias, column.name)
                                            },
                                        )
                                    }
                                }

                                if (column.isIndexed) {
                                    JoinActionButton(
                                        column = column.name,
                                        selectingTarget = joinDragActive,
                                        tint = joinColor,
                                        onClick = {
                                            if (joinDragActive) {
                                                onJoinTargetClick(alias, column.name)
                                            } else {
                                                onJoinClick(column.name)
                                            }
                                        },
                                        onStartDrag = { onStartJoin(column.name) },
                                        onDrag = onDragJoin,
                                        onEndGesture = onEndGesture,
                                    )
                                } else {
                                    Box(modifier = Modifier.size(28.dp))
                                }
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }

                if (fieldScrollState.value < fieldScrollState.maxValue) {
                    Box(
                        modifier =
                            Modifier.align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .height(28.dp)
                                .background(
                                    Brush.verticalGradient(
                                        listOf(
                                            MaterialTheme.colorScheme.surface.copy(alpha = 0f),
                                            MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                                        )
                                    )
                                )
                    )
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        contentDescription = "More fields below",
                        tint = SafeDbTheme.colors.actionPrimary,
                        modifier =
                            Modifier.align(Alignment.BottomCenter)
                                .padding(bottom = 2.dp)
                                .size(18.dp),
                    )
                }
            }

            Box(
                modifier =
                    Modifier.fillMaxWidth().height(CANVAS_RESIZE_FOOTER_HEIGHT.dp).padding(3.dp),
                contentAlignment = Alignment.BottomEnd,
            ) {
                Box(
                    modifier =
                        Modifier.size(20.dp)
                            .pointerHoverIcon(PointerIcon.Hand)
                            .semantics { contentDescription = "Resize table" }
                            .pointerInput(alias) {
                                detectDragGestures(
                                    onDragStart = { onStartResize() },
                                    onDragEnd = { onEndGesture() },
                                    onDragCancel = { onEndGesture() },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        onResizeTable(dragAmount)
                                    },
                                )
                            }
                ) {
                    Canvas(modifier = Modifier.size(20.dp)) {
                        val stroke = 1.8.dp.toPx()
                        drawLine(
                            color = resizeHandleColor,
                            start = Offset(size.width * 0.48f, size.height * 0.82f),
                            end = Offset(size.width * 0.82f, size.height * 0.48f),
                            strokeWidth = stroke,
                            cap = StrokeCap.Round,
                        )
                        drawLine(
                            color = resizeHandleColor,
                            start = Offset(size.width * 0.68f, size.height * 0.86f),
                            end = Offset(size.width * 0.86f, size.height * 0.68f),
                            strokeWidth = stroke,
                            cap = StrokeCap.Round,
                        )
                    }
                }
            }
        }
    }
}

internal fun tableColumnToggleState(selectedCount: Int, totalCount: Int): ToggleableState =
    when {
        totalCount <= 0 || selectedCount <= 0 -> ToggleableState.Off
        selectedCount >= totalCount -> ToggleableState.On
        else -> ToggleableState.Indeterminate
    }

@Composable
internal fun CompactSelectionIndicator(state: ToggleableState) {
    val selected = state != ToggleableState.Off
    Box(
        modifier =
            Modifier.size(13.dp)
                .border(
                    1.dp,
                    if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline,
                    RoundedCornerShape(1.dp),
                )
                .background(
                    if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surface,
                    RoundedCornerShape(1.dp),
                ),
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            ToggleableState.On ->
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(9.dp),
                )
            ToggleableState.Indeterminate ->
                Icon(
                    Icons.Default.Remove,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(9.dp),
                )
            ToggleableState.Off -> Unit
        }
    }
}

@Composable
private fun ExactDesktopTargetArea(content: @Composable () -> Unit) {
    val parentViewConfiguration = LocalViewConfiguration.current
    val exactTargetViewConfiguration =
        remember(parentViewConfiguration) {
            object : ViewConfiguration by parentViewConfiguration {
                override val minimumTouchTargetSize = DpSize(0.dp, 0.dp)
            }
        }
    CompositionLocalProvider(
        LocalViewConfiguration provides exactTargetViewConfiguration,
        content = content,
    )
}

internal fun joinActionDescription(column: String, selectingTarget: Boolean): String =
    if (selectingTarget) {
        "Join to $column; click to complete the join"
    } else {
        "Join from $column; click to select or drag to another indexed column"
    }

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun JoinActionButton(
    column: String,
    selectingTarget: Boolean,
    tint: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
    onStartDrag: () -> Unit,
    onDrag: (Offset) -> Unit,
    onEndGesture: () -> Unit,
) {
    val description = joinActionDescription(column, selectingTarget)
    TooltipBox(
        positionProvider =
            TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(description) } },
        state = rememberTooltipState(),
    ) {
        Box(
            modifier =
                Modifier.size(28.dp)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .semantics { contentDescription = description }
                    .clickable(role = Role.Button, onClick = onClick)
                    .pointerInput(column) {
                        detectDragGestures(
                            onDragStart = { onStartDrag() },
                            onDragEnd = { onEndGesture() },
                            onDragCancel = { onEndGesture() },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                onDrag(dragAmount)
                            },
                        )
                    },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Link,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = tint,
            )
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ColumnActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    TooltipBox(
        positionProvider =
            TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(contentDescription) } },
        state = rememberTooltipState(),
    ) {
        Box(
            modifier =
                Modifier.size(28.dp)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .semantics { this.contentDescription = contentDescription }
                    .clickable(role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint =
                    if (active) SafeDbTheme.colors.actionPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

private fun sortDescription(column: String, direction: SortDirection?, index: Int): String =
    when (direction) {
        null -> "Sort $column ascending"
        SortDirection.Asc ->
            "Sort $column is priority ${index + 1}, ascending; click for descending"
        SortDirection.Desc ->
            "Sort $column is priority ${index + 1}, descending; click to remove sorting"
    }

private fun groupDescription(column: String, index: Int, active: Boolean): String =
    if (active) "Group $column is priority ${index + 1}; click to remove grouping"
    else "Group by $column"
