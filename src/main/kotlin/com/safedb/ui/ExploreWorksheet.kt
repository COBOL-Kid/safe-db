package com.safedb.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Functions
import androidx.compose.material.icons.filled.GroupWork
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.ViewColumn
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.safedb.explore.ExploreMode
import com.safedb.explore.PivotNumberFormat
import com.safedb.explore.SortDir
import com.safedb.explore.WorksheetCalculation
import com.safedb.explore.WorksheetColumnLayout
import com.safedb.explore.WorksheetConfig
import com.safedb.explore.WorksheetDisplayColumn
import com.safedb.explore.WorksheetPreview
import com.safedb.explore.WorksheetProjectedRow
import com.safedb.explore.WorksheetRowKind
import com.safedb.explore.WorksheetSort
import com.safedb.explore.WorksheetValueRef
import com.safedb.explore.displayColumnLabel
import com.safedb.explore.moveWorksheetColumn
import com.safedb.explore.projectWorksheetTable
import com.safedb.explore.toWorksheetColumnLayout
import com.safedb.model.QueryResult
import com.safedb.model.ResultCell
import com.safedb.ui.components.MenuActionRow
import com.safedb.ui.components.PrimaryButton
import com.safedb.ui.components.SafeDropdownMenu
import com.safedb.ui.components.ScrollableMenuColumn
import com.safedb.ui.theme.DataMono
import com.safedb.ui.theme.SafeDbTheme

private const val WorksheetGroupColumnWidth = 220

@Composable
internal fun ExploreWorksheet(
    sample: QueryResult,
    config: WorksheetConfig,
    preview: WorksheetPreview,
    onConfigChange: (WorksheetConfig) -> Unit,
    onColumnLayoutChange: (List<WorksheetColumnLayout>) -> Unit,
    onToggleGroup: (String) -> Unit,
    configReplacementRevision: Int,
    railVisible: Boolean,
    onRailVisibilityChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    // Rendered under the table so the rail spans the full height, matching Pivot/Visualization.
    exportBar: @Composable () -> Unit = {},
) {
    var editingFilter by remember { mutableStateOf<String?>(null) }
    var editingGroup by remember { mutableStateOf<String?>(null) }
    var editingCalculation by
        remember(configReplacementRevision) { mutableStateOf<WorksheetCalculation?>(null) }
    var calculationEditorRevision by remember(configReplacementRevision) { mutableStateOf(0) }
    var calculationEditorActive by remember(configReplacementRevision) { mutableStateOf(false) }

    editingFilter?.let { column ->
        val existing = config.filters.firstOrNull { it.column == column }
        WorksheetFilterDialog(
            column = column,
            dataType = sample.columns.firstOrNull { it.name == column }?.dataType.orEmpty(),
            existing = existing,
            sample = sample,
            onSave = { filter ->
                onConfigChange(
                    config.copy(filters = config.filters.filterNot { it.column == column } + filter)
                )
                editingFilter = null
            },
            onRemove =
                if (existing != null) {
                    {
                        onConfigChange(config.copy(filters = config.filters - existing))
                        editingFilter = null
                    }
                } else null,
            onDismiss = { editingFilter = null },
        )
    }
    editingGroup?.let { column ->
        val existing = config.groups.firstOrNull { it.column == column }
        WorksheetGroupDialog(
            column = column,
            displayLabel =
                preview.columns.firstOrNull { it.sourceColumn == column }?.label
                    ?: displayColumnLabel(column),
            dataType = sample.columns.firstOrNull { it.name == column }?.dataType.orEmpty(),
            existing = existing,
            onSave = { group ->
                val groups =
                    if (existing == null) config.groups + group
                    else config.groups.map { if (it.id == existing.id) group else it }
                onConfigChange(config.copy(groups = groups))
                editingGroup = null
            },
            onRemove =
                if (existing != null) {
                    {
                        onConfigChange(config.copy(groups = config.groups - existing))
                        editingGroup = null
                    }
                } else null,
            onDismiss = { editingGroup = null },
        )
    }
    Row(modifier = modifier.fillMaxSize()) {
        if (railVisible) {
            Column(modifier = Modifier.width(320.dp).fillMaxHeight()) {
                CalculationRail(
                    sample = sample,
                    config = config,
                    existing = editingCalculation,
                    editorActive = calculationEditorActive,
                    editorRevision = calculationEditorRevision,
                    onAdd = {
                        editingCalculation = null
                        calculationEditorActive = true
                        calculationEditorRevision += 1
                    },
                    onEdit = {
                        editingCalculation = it
                        calculationEditorActive = true
                        calculationEditorRevision += 1
                    },
                    onSave = { calculation, requiredSort ->
                        val calculations =
                            if (editingCalculation == null) {
                                config.calculations + calculation
                            } else {
                                config.calculations.map {
                                    if (it.id == calculation.id) calculation else it
                                }
                            }
                        val sorts =
                            requiredSort?.let { sort ->
                                if (config.sorts.any { it.target == sort.target }) config.sorts
                                else config.sorts + sort
                            } ?: config.sorts
                        onConfigChange(config.copy(calculations = calculations, sorts = sorts))
                        editingCalculation = null
                        calculationEditorActive = false
                        calculationEditorRevision += 1
                    },
                    onCancel = {
                        editingCalculation = null
                        calculationEditorActive = false
                        calculationEditorRevision += 1
                    },
                    onRemove = { calculation ->
                        onConfigChange(
                            config.copy(
                                calculations = config.calculations - calculation,
                                columnLayout =
                                    config.columnLayout.filterNot {
                                        it.ref == WorksheetValueRef.Calculation(calculation.id)
                                    },
                            )
                        )
                        if (editingCalculation?.id == calculation.id) {
                            editingCalculation = null
                            calculationEditorActive = false
                            calculationEditorRevision += 1
                        }
                    },
                    onCollapse = { onRailVisibilityChange(false) },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            }
        } else {
            CollapsedExploreRail(ExploreMode.Worksheet) { onRailVisibilityChange(true) }
        }
        HorizontalDivider(
            modifier = Modifier.width(1.dp).fillMaxHeight(),
            color = MaterialTheme.colorScheme.outline,
        )

        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            if (preview.warnings.isNotEmpty() || preview.calculationErrorCount > 0) {
                Surface(color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)) {
                    Text(
                        buildList {
                                addAll(preview.warnings)
                                if (preview.calculationErrorCount > 0)
                                    add(
                                        "${preview.calculationErrorCount} calculation error${if (preview.calculationErrorCount == 1) "" else "s"}"
                                    )
                            }
                            .joinToString(" · "),
                        modifier =
                            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            WorksheetTable(
                config = config,
                preview = preview,
                onColumnLayoutChange = onColumnLayoutChange,
                onSort = { ref ->
                    onConfigChange(config.copy(sorts = cycleSort(config.sorts, ref)))
                },
                onGroup = { column -> editingGroup = column },
                onFilter = { column -> editingFilter = column },
                onToggleGroup = onToggleGroup,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            exportBar()
        }
    }
}

@Composable
private fun WorksheetTable(
    config: WorksheetConfig,
    preview: WorksheetPreview,
    onColumnLayoutChange: (List<WorksheetColumnLayout>) -> Unit,
    onSort: (WorksheetValueRef) -> Unit,
    onGroup: (String) -> Unit,
    onFilter: (String) -> Unit,
    onToggleGroup: (String) -> Unit,
    modifier: Modifier,
) {
    val scroll = rememberScrollState()
    val verticalScroll = rememberLazyListState()
    val projection =
        remember(preview, config.columnLayout) {
            projectWorksheetTable(preview, config.columnLayout)
        }
    val resolvedColumns = projection.resolvedColumns
    val visibleColumns = projection.columns
    val widths = visibleColumns.associate { it.id to if (it.calculationId == null) 176 else 196 }
    val groupColumnWidth = if (projection.hasRowLabels) WorksheetGroupColumnWidth else 0
    val tableWidth =
        (visibleColumns.sumOf { widths.getValue(it.id) } + groupColumnWidth).coerceAtLeast(1)

    Column(modifier = modifier) {
        WorksheetColumnMenu(
            columns = resolvedColumns,
            naturalColumns = preview.columns,
            onLayoutChange = onColumnLayoutChange,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (visibleColumns.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "All worksheet columns are hidden. Use Columns to show one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    Column(
                        modifier =
                            Modifier.fillMaxSize().horizontalScroll(scroll).width(tableWidth.dp)
                    ) {
                        Row(
                            modifier =
                                Modifier.background(MaterialTheme.colorScheme.surfaceContainerLow)
                        ) {
                            if (projection.hasRowLabels) {
                                WorksheetGroupHeader()
                            }
                            visibleColumns.forEachIndexed { visibleIndex, column ->
                                WorksheetHeader(
                                    column = column,
                                    width = widths.getValue(column.id),
                                    sortIndex =
                                        config.sorts.indexOfFirst { it.target == column.valueRef },
                                    sort =
                                        config.sorts.firstOrNull { it.target == column.valueRef },
                                    grouped =
                                        column.sourceColumn?.let { source ->
                                            config.groups.any { it.column == source }
                                        } == true,
                                    filtered =
                                        column.sourceColumn?.let { source ->
                                            config.filters.any { it.column == source }
                                        } == true,
                                    canMoveLeft = visibleIndex > 0,
                                    canMoveRight = visibleIndex < visibleColumns.lastIndex,
                                    onSort = { onSort(column.valueRef) },
                                    onGroup = column.sourceColumn?.let { { onGroup(it) } },
                                    onFilter = column.sourceColumn?.let { { onFilter(it) } },
                                    onHide = {
                                        onColumnLayoutChange(
                                            setWorksheetColumnVisibility(
                                                resolvedColumns.toWorksheetColumnLayout(),
                                                column.valueRef,
                                                visible = false,
                                            )
                                        )
                                    },
                                    onMoveLeft = {
                                        onColumnLayoutChange(
                                            moveVisibleWorksheetColumn(
                                                resolvedColumns.toWorksheetColumnLayout(),
                                                visibleIndex,
                                                visibleIndex - 1,
                                            )
                                        )
                                    },
                                    onMoveRight = {
                                        onColumnLayoutChange(
                                            moveVisibleWorksheetColumn(
                                                resolvedColumns.toWorksheetColumnLayout(),
                                                visibleIndex,
                                                visibleIndex + 1,
                                            )
                                        )
                                    },
                                )
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        if (preview.rows.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "No rows match the worksheet filters.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        } else {
                            LazyColumn(state = verticalScroll, modifier = Modifier.fillMaxSize()) {
                                itemsIndexed(
                                    projection.rows,
                                    key = { _, row -> "${row.kind}:${row.pathKey}" },
                                ) { index, row ->
                                    val background =
                                        when {
                                            row.kind != WorksheetRowKind.Detail ->
                                                MaterialTheme.colorScheme.primaryContainer.copy(
                                                    alpha = 0.35f
                                                )
                                            index % 2 == 1 ->
                                                MaterialTheme.colorScheme.surfaceContainerLow.copy(
                                                    alpha = 0.55f
                                                )
                                            else -> MaterialTheme.colorScheme.surface
                                        }
                                    Row(modifier = Modifier.background(background)) {
                                        if (projection.hasRowLabels) {
                                            WorksheetGroupCell(row, onToggleGroup)
                                        }
                                        visibleColumns.forEachIndexed { visibleIndex, column ->
                                            val cell = row.cells[visibleIndex]
                                            Box(
                                                modifier =
                                                    Modifier.width(widths.getValue(column.id).dp)
                                                        .height(34.dp)
                                                        .padding(horizontal = 10.dp),
                                                contentAlignment =
                                                    if (
                                                        cell.value is ResultCell.IntegerCell ||
                                                            cell.value is ResultCell.FloatCell
                                                    )
                                                        Alignment.CenterEnd
                                                    else Alignment.CenterStart,
                                            ) {
                                                WorksheetCellText(
                                                    cell.value,
                                                    cell.error,
                                                    column.numberFormat,
                                                )
                                            }
                                        }
                                    }
                                    HorizontalDivider(
                                        color =
                                            MaterialTheme.colorScheme.outlineVariant.copy(
                                                alpha = 0.65f
                                            )
                                    )
                                }
                            }
                        }
                    }
                }
                Box(modifier = Modifier.width(12.dp).fillMaxHeight()) {
                    VerticalScrollbar(
                        adapter = rememberScrollbarAdapter(verticalScroll),
                        modifier = Modifier.align(Alignment.Center).fillMaxHeight(),
                    )
                }
            }
            HorizontalScrollbar(
                adapter = rememberScrollbarAdapter(scroll),
                modifier = Modifier.fillMaxWidth().padding(end = 12.dp, bottom = 4.dp),
            )
        }
    }
}

@Composable
private fun WorksheetGroupHeader() {
    Column(
        modifier =
            Modifier.width(WorksheetGroupColumnWidth.dp).padding(horizontal = 8.dp, vertical = 5.dp)
    ) {
        Text("Group", style = DataMono.copy(fontWeight = FontWeight.Medium))
        Text(
            "worksheet hierarchy",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WorksheetGroupCell(row: WorksheetProjectedRow, onToggleGroup: (String) -> Unit) {
    Box(
        modifier =
            Modifier.width(WorksheetGroupColumnWidth.dp)
                .height(34.dp)
                .then(
                    if (row.kind == WorksheetRowKind.Group) {
                        Modifier.clickable { onToggleGroup(row.pathKey) }
                    } else {
                        Modifier
                    }
                )
                .padding(horizontal = 10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        when (row.kind) {
            WorksheetRowKind.Detail -> Unit
            WorksheetRowKind.Group ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width((row.depth * 12).dp))
                    Icon(
                        if (row.expanded) Icons.Default.KeyboardArrowDown
                        else Icons.Default.ChevronRight,
                        contentDescription = if (row.expanded) "Collapse group" else "Expand group",
                        modifier = Modifier.size(17.dp),
                    )
                    Text(
                        row.rowLabel.orEmpty(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            WorksheetRowKind.GrandTotal ->
                Text(row.rowLabel ?: "Grand total", fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun WorksheetColumnMenu(
    columns: List<com.safedb.explore.ResolvedWorksheetColumn>,
    naturalColumns: List<WorksheetDisplayColumn>,
    onLayoutChange: (List<WorksheetColumnLayout>) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var draggedIndex by remember { mutableStateOf<Int?>(null) }
    var draggedLayout by remember { mutableStateOf<List<WorksheetColumnLayout>>(emptyList()) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val dragStepPx = with(LocalDensity.current) { 36.dp.toPx() }
    val hiddenCount = columns.count { !it.visible }
    val naturalRefs = naturalColumns.map { it.valueRef }
    val currentRefs = columns.map { it.column.valueRef }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            TextButton(onClick = { expanded = true }) {
                Icon(
                    Icons.Default.ViewColumn,
                    contentDescription = null,
                    modifier = Modifier.size(17.dp),
                )
                Text(
                    if (hiddenCount == 0) "Columns" else "Columns · $hiddenCount hidden",
                    modifier = Modifier.padding(start = 5.dp),
                )
            }
            SafeDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                minWidth = 330.dp,
            ) {
                Column(modifier = Modifier.widthIn(min = 330.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Worksheet columns",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = {
                                onLayoutChange(
                                    columns.map {
                                        WorksheetColumnLayout(it.column.valueRef, visible = true)
                                    }
                                )
                            },
                            enabled = hiddenCount > 0,
                        ) {
                            Text("Show all")
                        }
                        TextButton(
                            onClick = {
                                val visibility = columns.associate {
                                    it.column.valueRef to it.visible
                                }
                                onLayoutChange(
                                    naturalColumns.map { column ->
                                        WorksheetColumnLayout(
                                            column.valueRef,
                                            visibility[column.valueRef] ?: true,
                                        )
                                    }
                                )
                            },
                            enabled = currentRefs != naturalRefs,
                        ) {
                            Text("Reset order")
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    ScrollableMenuColumn {
                        columns.forEachIndexed { index, resolved ->
                            val column = resolved.column
                            Row(
                                modifier =
                                    Modifier.fillMaxWidth()
                                        .padding(horizontal = 6.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Default.DragHandle,
                                    contentDescription = "Drag to reorder ${column.label}",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier =
                                        Modifier.size(20.dp).pointerInput(index, columns.size) {
                                            detectDragGestures(
                                                onDragStart = {
                                                    draggedIndex = index
                                                    draggedLayout =
                                                        columns.toWorksheetColumnLayout()
                                                    dragOffset = 0f
                                                },
                                                onDragEnd = {
                                                    draggedIndex = null
                                                    draggedLayout = emptyList()
                                                    dragOffset = 0f
                                                },
                                                onDragCancel = {
                                                    draggedIndex = null
                                                    draggedLayout = emptyList()
                                                    dragOffset = 0f
                                                },
                                                onDrag = { change, amount ->
                                                    change.consume()
                                                    dragOffset += amount.y
                                                    var current =
                                                        draggedIndex ?: return@detectDragGestures
                                                    while (
                                                        dragOffset >= dragStepPx &&
                                                            current < columns.lastIndex
                                                    ) {
                                                        draggedLayout =
                                                            moveWorksheetColumn(
                                                                draggedLayout,
                                                                current,
                                                                current + 1,
                                                            )
                                                        onLayoutChange(draggedLayout)
                                                        current += 1
                                                        draggedIndex = current
                                                        dragOffset -= dragStepPx
                                                    }
                                                    while (
                                                        dragOffset <= -dragStepPx && current > 0
                                                    ) {
                                                        draggedLayout =
                                                            moveWorksheetColumn(
                                                                draggedLayout,
                                                                current,
                                                                current - 1,
                                                            )
                                                        onLayoutChange(draggedLayout)
                                                        current -= 1
                                                        draggedIndex = current
                                                        dragOffset += dragStepPx
                                                    }
                                                },
                                            )
                                        },
                                )
                                Checkbox(
                                    checked = resolved.visible,
                                    onCheckedChange = { visible ->
                                        onLayoutChange(
                                            columns.map {
                                                WorksheetColumnLayout(
                                                    it.column.valueRef,
                                                    if (it.column.valueRef == column.valueRef)
                                                        visible
                                                    else it.visible,
                                                )
                                            }
                                        )
                                    },
                                    modifier = Modifier.size(34.dp),
                                )
                                Column(modifier = Modifier.weight(1f).padding(horizontal = 5.dp)) {
                                    Text(
                                        column.label,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        column.dataType,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        onLayoutChange(
                                            moveWorksheetColumn(
                                                columns.toWorksheetColumnLayout(),
                                                index,
                                                index - 1,
                                            )
                                        )
                                    },
                                    enabled = index > 0,
                                    modifier = Modifier.size(30.dp),
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                        contentDescription = "Move ${column.label} left",
                                        modifier = Modifier.size(17.dp),
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        onLayoutChange(
                                            moveWorksheetColumn(
                                                columns.toWorksheetColumnLayout(),
                                                index,
                                                index + 1,
                                            )
                                        )
                                    },
                                    enabled = index < columns.lastIndex,
                                    modifier = Modifier.size(30.dp),
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                        contentDescription = "Move ${column.label} right",
                                        modifier = Modifier.size(17.dp),
                                    )
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
private fun WorksheetHeader(
    column: WorksheetDisplayColumn,
    width: Int,
    sortIndex: Int,
    sort: WorksheetSort?,
    grouped: Boolean,
    filtered: Boolean,
    canMoveLeft: Boolean,
    canMoveRight: Boolean,
    onSort: () -> Unit,
    onGroup: (() -> Unit)?,
    onFilter: (() -> Unit)?,
    onHide: () -> Unit,
    onMoveLeft: () -> Unit,
    onMoveRight: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Column(modifier = Modifier.width(width.dp).padding(horizontal = 8.dp, vertical = 5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                column.label,
                style = DataMono.copy(fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = onSort,
                modifier = Modifier.size(24.dp).pointerHoverIcon(PointerIcon.Hand),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector =
                            when (sort?.dir) {
                                SortDir.Asc -> Icons.Default.KeyboardArrowUp
                                SortDir.Desc -> Icons.Default.KeyboardArrowDown
                                null -> Icons.AutoMirrored.Filled.Sort
                            },
                        contentDescription = "Sort ${column.label}",
                        modifier = Modifier.size(16.dp),
                        tint =
                            if (sort != null) SafeDbTheme.colors.actionPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (sortIndex >= 0) {
                        Text(
                            "${sortIndex + 1}",
                            style = MaterialTheme.typography.labelSmall,
                            color = SafeDbTheme.colors.actionPrimary,
                        )
                    }
                }
            }
            Box {
                IconButton(
                    onClick = { menuOpen = true },
                    modifier = Modifier.size(24.dp).pointerHoverIcon(PointerIcon.Hand),
                ) {
                    Icon(
                        Icons.Default.ArrowDropDown,
                        contentDescription = "Column actions for ${column.label}",
                        modifier = Modifier.size(18.dp),
                        tint =
                            if (grouped || filtered) SafeDbTheme.colors.actionPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                SafeDropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    minWidth = 190.dp,
                ) {
                    onGroup?.let { group ->
                        MenuActionRow(
                            text = "Group",
                            selected = grouped,
                            supportingText = if (grouped) "Grouping is on" else null,
                            leading = {
                                Icon(
                                    Icons.Default.GroupWork,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                            onClick = {
                                menuOpen = false
                                group()
                            },
                        )
                    }
                    onFilter?.let { filter ->
                        MenuActionRow(
                            text = "Filter",
                            selected = filtered,
                            supportingText = if (filtered) "Filter is on" else null,
                            leading = {
                                Icon(
                                    Icons.Default.FilterAlt,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                            onClick = {
                                menuOpen = false
                                filter()
                            },
                        )
                    }
                    if (canMoveLeft || canMoveRight) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    if (canMoveLeft) {
                        MenuActionRow(
                            text = "Move left",
                            leading = {
                                Icon(
                                    Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                            onClick = {
                                menuOpen = false
                                onMoveLeft()
                            },
                        )
                    }
                    if (canMoveRight) {
                        MenuActionRow(
                            text = "Move right",
                            leading = {
                                Icon(
                                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                            onClick = {
                                menuOpen = false
                                onMoveRight()
                            },
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    MenuActionRow(
                        text = "Hide column",
                        leading = {
                            Icon(
                                Icons.Default.VisibilityOff,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        },
                        onClick = {
                            menuOpen = false
                            onHide()
                        },
                    )
                }
            }
        }
        Text(
            column.dataType,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun WorksheetCellText(value: ResultCell, error: String?, numberFormat: PivotNumberFormat?) {
    when {
        error != null ->
            Text("Error", style = DataMono, color = MaterialTheme.colorScheme.error, maxLines = 1)
        value is ResultCell.Null ->
            Text(
                "null",
                style = DataMono,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        else ->
            Text(
                formatWorksheetValue(value, numberFormat),
                style = DataMono,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign =
                    if (value is ResultCell.IntegerCell || value is ResultCell.FloatCell)
                        TextAlign.End
                    else TextAlign.Start,
            )
    }
}

@Composable
private fun CalculationRail(
    sample: QueryResult,
    config: WorksheetConfig,
    existing: WorksheetCalculation?,
    editorActive: Boolean,
    editorRevision: Int,
    onAdd: () -> Unit,
    onEdit: (WorksheetCalculation) -> Unit,
    onSave: (WorksheetCalculation, WorksheetSort?) -> Unit,
    onCancel: () -> Unit,
    onRemove: (WorksheetCalculation) -> Unit,
    onCollapse: () -> Unit,
    modifier: Modifier,
) {
    val scroll = rememberScrollState()
    Surface(modifier = modifier, color = SafeDbTheme.colors.workspacePanel) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier =
                    Modifier.fillMaxSize()
                        .verticalScroll(scroll)
                        .padding(start = 14.dp, top = 14.dp, end = 20.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.Start,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Calculations",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "Add summaries, formulas, and windows.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onCollapse) {
                        Icon(
                            Icons.Default.ChevronLeft,
                            contentDescription = "Hide Worksheet sidebar",
                        )
                    }
                }
                if (editorActive) {
                    key(existing?.id, editorRevision) {
                        WorksheetCalculationEditor(
                            sample = sample,
                            config = config,
                            existing = existing,
                            onSave = onSave,
                            onCancel = onCancel,
                        )
                    }
                } else {
                    PrimaryButton(
                        onClick = onAdd,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 5.dp),
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            "Add calculation",
                            modifier = Modifier.padding(start = 5.dp),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    "Saved calculations",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                if (config.calculations.isEmpty()) {
                    Text(
                        "Calculated columns will appear here and to the right of the sample fields.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                config.calculations.forEach { calculation ->
                    Surface(
                        shape = RoundedCornerShape(3.dp),
                        color =
                            if (existing?.id == calculation.id) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerLow
                            },
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth().clickable { onEdit(calculation) },
                    ) {
                        Row(
                            modifier =
                                Modifier.padding(
                                    start = 10.dp,
                                    top = 8.dp,
                                    bottom = 8.dp,
                                    end = 3.dp,
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.Functions,
                                contentDescription = null,
                                modifier = Modifier.size(17.dp),
                                tint = SafeDbTheme.colors.actionPrimary,
                            )
                            Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                                Text(
                                    calculation.label,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    calculationSummary(calculation),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                )
                            }
                            IconButton(
                                onClick = { onRemove(calculation) },
                                modifier = Modifier.size(30.dp),
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Remove ${calculation.label}",
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }
            }
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(scroll),
                modifier =
                    Modifier.align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .padding(end = 4.dp, top = 4.dp, bottom = 4.dp),
            )
        }
    }
}
