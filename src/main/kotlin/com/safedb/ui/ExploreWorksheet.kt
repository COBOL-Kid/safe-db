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
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.Calculate
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
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
import com.safedb.explore.DateGroupUnit
import com.safedb.explore.NumberFormatKind
import com.safedb.explore.PivotGrouping
import com.safedb.explore.PivotNumberFormat
import com.safedb.explore.SortDir
import com.safedb.explore.WorksheetAggregateFn
import com.safedb.explore.WorksheetCalculation
import com.safedb.explore.WorksheetColumnLayout
import com.safedb.explore.WorksheetConfig
import com.safedb.explore.WorksheetDisplayColumn
import com.safedb.explore.WorksheetFilter
import com.safedb.explore.WorksheetFilterOp
import com.safedb.explore.WorksheetGrain
import com.safedb.explore.WorksheetGroup
import com.safedb.explore.WorksheetPreview
import com.safedb.explore.WorksheetProjectedRow
import com.safedb.explore.WorksheetRowKind
import com.safedb.explore.WorksheetSort
import com.safedb.explore.WorksheetValueRef
import com.safedb.explore.WorksheetWindowFn
import com.safedb.explore.displayColumnLabel
import com.safedb.explore.evaluatePivotFormula
import com.safedb.explore.moveWorksheetColumn
import com.safedb.explore.pivotCellKey
import com.safedb.explore.projectWorksheetTable
import com.safedb.explore.toWorksheetColumnLayout
import com.safedb.model.QueryResult
import com.safedb.model.ResultCell
import com.safedb.model.classifyColumn
import com.safedb.model.isNumeric
import com.safedb.model.isTemporal
import com.safedb.ui.components.PrimaryButton
import com.safedb.ui.components.SafeDropdownMenu
import com.safedb.ui.components.SecondaryButton
import com.safedb.ui.components.SectionLabel
import com.safedb.ui.components.SelectablePill
import com.safedb.ui.theme.DataMono
import com.safedb.ui.theme.SafeDbTheme
import java.text.DecimalFormat
import java.util.Currency
import java.util.UUID

private const val WorksheetGroupColumnWidth = 220

@Composable
internal fun ExploreWorksheet(
    sample: QueryResult,
    config: WorksheetConfig,
    preview: WorksheetPreview,
    onConfigChange: (WorksheetConfig) -> Unit,
    onColumnLayoutChange: (List<WorksheetColumnLayout>) -> Unit,
    onToggleGroup: (String) -> Unit,
    railFooter: @Composable (collapsed: Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var railVisible by remember { mutableStateOf(true) }
    var editingFilter by remember { mutableStateOf<String?>(null) }
    var editingGroup by remember { mutableStateOf<String?>(null) }
    var editingCalculation by remember { mutableStateOf<WorksheetCalculation?>(null) }
    var addingCalculation by remember { mutableStateOf(false) }

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
    if (addingCalculation || editingCalculation != null) {
        WorksheetCalculationDialog(
            sample = sample,
            config = config,
            existing = editingCalculation,
            onSave = { calculation, requiredSort ->
                val calculations =
                    if (editingCalculation == null) {
                        config.calculations + calculation
                    } else {
                        config.calculations.map { if (it.id == calculation.id) calculation else it }
                    }
                val sorts =
                    requiredSort?.let { sort ->
                        if (config.sorts.any { it.target == sort.target }) config.sorts
                        else config.sorts + sort
                    } ?: config.sorts
                onConfigChange(config.copy(calculations = calculations, sorts = sorts))
                addingCalculation = false
                editingCalculation = null
            },
            onDismiss = {
                addingCalculation = false
                editingCalculation = null
            },
        )
    }

    Row(modifier = modifier.fillMaxSize()) {
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
        }

        if (railVisible) {
            HorizontalDivider(
                modifier = Modifier.width(1.dp).fillMaxHeight(),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            Column(modifier = Modifier.width(300.dp).fillMaxHeight()) {
                CalculationRail(
                    calculations = config.calculations,
                    onAdd = { addingCalculation = true },
                    onEdit = { editingCalculation = it },
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
                    },
                    onCollapse = { railVisible = false },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
                railFooter(false)
            }
        } else {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Column(modifier = Modifier.fillMaxHeight()) {
                    IconButton(onClick = { railVisible = true }) {
                        Icon(Icons.Default.Calculate, contentDescription = "Show calculations")
                    }
                    Spacer(Modifier.weight(1f))
                    railFooter(true)
                }
            }
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
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                Column(
                    modifier =
                        Modifier.fillMaxSize()
                            .padding(end = 8.dp)
                            .horizontalScroll(scroll)
                            .width(tableWidth.dp)
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
                                sort = config.sorts.firstOrNull { it.target == column.valueRef },
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
                                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)
                                )
                            }
                        }
                    }
                }
                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(verticalScroll),
                    modifier =
                        Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(end = 4.dp),
                )
            }
            HorizontalScrollbar(
                adapter = rememberScrollbarAdapter(scroll),
                modifier = Modifier.fillMaxWidth().padding(end = 8.dp, bottom = 4.dp),
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
                Column(modifier = Modifier.widthIn(min = 330.dp).heightIn(max = 440.dp)) {
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
                    Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
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
        Text(
            "Drag or use arrows to set the table and CSV order.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
@OptIn(ExperimentalComposeUiApi::class)
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
                onClick = onHide,
                modifier = Modifier.size(24.dp).pointerHoverIcon(PointerIcon.Hand),
            ) {
                Icon(
                    Icons.Default.VisibilityOff,
                    contentDescription = "Hide ${column.label}",
                    modifier = Modifier.size(15.dp),
                )
            }
            IconButton(
                onClick = onMoveLeft,
                enabled = canMoveLeft,
                modifier =
                    Modifier.size(24.dp)
                        .pointerHoverIcon(
                            if (canMoveLeft) PointerIcon.Hand else PointerIcon.Default
                        ),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = "Move ${column.label} left",
                    modifier = Modifier.size(16.dp),
                )
            }
            IconButton(
                onClick = onMoveRight,
                enabled = canMoveRight,
                modifier =
                    Modifier.size(24.dp)
                        .pointerHoverIcon(
                            if (canMoveRight) PointerIcon.Hand else PointerIcon.Default
                        ),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "Move ${column.label} right",
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        Text(
            column.dataType,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            HeaderAction(
                icon =
                    when (sort?.dir) {
                        SortDir.Asc -> Icons.Default.KeyboardArrowUp
                        SortDir.Desc -> Icons.Default.KeyboardArrowDown
                        null -> Icons.AutoMirrored.Filled.Sort
                    },
                label = if (sortIndex >= 0) "Sort ${sortIndex + 1}" else "Sort",
                active = sort != null,
                onClick = onSort,
            )
            onGroup?.let { HeaderAction(Icons.Default.GroupWork, "Group", grouped, it) }
            onFilter?.let { HeaderAction(Icons.Default.FilterAlt, "Filter", filtered, it) }
        }
    }
}

@Composable
private fun HeaderAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        contentPadding =
            androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 0.dp),
    ) {
        Icon(
            icon,
            contentDescription = label,
            modifier = Modifier.size(15.dp),
            tint =
                if (active) SafeDbTheme.colors.actionPrimary
                else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (active && label.startsWith("Sort "))
            Text(label.substringAfter(' '), style = MaterialTheme.typography.labelSmall)
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
    calculations: List<WorksheetCalculation>,
    onAdd: () -> Unit,
    onEdit: (WorksheetCalculation) -> Unit,
    onRemove: (WorksheetCalculation) -> Unit,
    onCollapse: () -> Unit,
    modifier: Modifier,
) {
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
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
                    Icon(Icons.Default.ChevronRight, contentDescription = "Hide calculations")
                }
            }
            PrimaryButton(onClick = onAdd, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(17.dp))
                Text("Add calculation", modifier = Modifier.padding(start = 5.dp))
            }
            if (calculations.isEmpty()) {
                Text(
                    "Calculated columns will appear here and to the right of the sample fields.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            calculations.forEach { calculation ->
                Surface(
                    shape = RoundedCornerShape(3.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth().clickable { onEdit(calculation) },
                ) {
                    Row(
                        modifier =
                            Modifier.padding(start = 10.dp, top = 8.dp, bottom = 8.dp, end = 3.dp),
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
    }
}

@Composable
private fun WorksheetFilterDialog(
    column: String,
    dataType: String,
    existing: WorksheetFilter?,
    sample: QueryResult,
    onSave: (WorksheetFilter) -> Unit,
    onRemove: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var op by remember { mutableStateOf(existing?.op ?: defaultFilterOp(dataType)) }
    var value by remember { mutableStateOf(existing?.value.orEmpty()) }
    var second by remember { mutableStateOf(existing?.secondValue.orEmpty()) }
    var members by remember { mutableStateOf(existing?.includedKeys.orEmpty()) }
    val index = sample.columns.indexOfFirst { it.name == column }
    val memberOptions =
        if (index < 0) emptyList()
        else sample.rows.mapNotNull { it.getOrNull(index) }.distinctBy(::pivotCellKey).take(100)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Filter ${displayColumnName(sample.columns.first { it.name == column })}") },
        text = {
            Column(
                modifier = Modifier.widthIn(min = 480.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SelectRow("Condition", filterOps(dataType), op, { it.name.toDisplayWords() }) {
                    op = it
                }
                if (op == WorksheetFilterOp.Members) {
                    Column(modifier = Modifier.height(220.dp)) {
                        memberOptions.forEach { cell ->
                            val key = pivotCellKey(cell)
                            Row(
                                modifier =
                                    Modifier.fillMaxWidth().clickable {
                                        members =
                                            if (key in members) members - key else members + key
                                    },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = key in members, onCheckedChange = null)
                                Text(
                                    formatCell(cell),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                } else if (op !in setOf(WorksheetFilterOp.IsNull, WorksheetFilterOp.IsNotNull)) {
                    OutlinedTextField(
                        value = value,
                        onValueChange = { value = it },
                        label = { Text("Value") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (op == WorksheetFilterOp.Between)
                        OutlinedTextField(
                            value = second,
                            onValueChange = { second = it },
                            label = { Text("Upper value") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                }
            }
        },
        confirmButton = {
            PrimaryButton(
                onClick = {
                    onSave(
                        WorksheetFilter(
                            existing?.id ?: UUID.randomUUID().toString(),
                            column,
                            op = op,
                            value = value,
                            secondValue = second.ifBlank { null },
                            includedKeys = members,
                        )
                    )
                }
            ) {
                Text("Apply")
            }
        },
        dismissButton = {
            Row {
                onRemove?.let { TextButton(onClick = it) { Text("Remove") } }
                SecondaryButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun WorksheetGroupDialog(
    column: String,
    displayLabel: String,
    dataType: String,
    existing: WorksheetGroup?,
    onSave: (WorksheetGroup) -> Unit,
    onRemove: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var grouping by remember { mutableStateOf(existing?.grouping ?: PivotGrouping.Exact) }
    var binSize by remember { mutableStateOf((grouping as? PivotGrouping.NumberBin)?.size ?: "10") }
    val choices =
        when {
            isTemporalType(dataType) ->
                listOf<PivotGrouping>(PivotGrouping.Exact) +
                    DateGroupUnit.entries.map(PivotGrouping::Date)
            isNumericType(dataType) -> listOf(PivotGrouping.Exact, PivotGrouping.NumberBin(binSize))
            else -> listOf(PivotGrouping.Exact)
        }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Group $displayLabel") },
        text = {
            Column(
                modifier = Modifier.widthIn(min = 440.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Group values by", style = MaterialTheme.typography.labelLarge)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    choices.forEach { choice ->
                        SelectPill(groupingLabel(choice), sameGroupingKind(grouping, choice)) {
                            grouping = choice
                        }
                    }
                }
                if (grouping is PivotGrouping.NumberBin) {
                    OutlinedTextField(
                        value = binSize,
                        onValueChange = {
                            binSize = it
                            grouping = PivotGrouping.NumberBin(it)
                        },
                        label = { Text("Bin size") },
                        singleLine = true,
                    )
                }
            }
        },
        confirmButton = {
            PrimaryButton(
                onClick = {
                    onSave(
                        WorksheetGroup(
                            existing?.id ?: UUID.randomUUID().toString(),
                            column,
                            label = displayLabel,
                            grouping = grouping,
                        )
                    )
                },
                enabled =
                    grouping !is PivotGrouping.NumberBin ||
                        binSize.toBigDecimalOrNull()?.signum() == 1,
            ) {
                Text("Apply")
            }
        },
        dismissButton = {
            Row {
                onRemove?.let { TextButton(onClick = it) { Text("Remove") } }
                SecondaryButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun WorksheetCalculationDialog(
    sample: QueryResult,
    config: WorksheetConfig,
    existing: WorksheetCalculation?,
    onSave: (WorksheetCalculation, WorksheetSort?) -> Unit,
    onDismiss: () -> Unit,
) {
    var kind by remember { mutableStateOf(calculationKind(existing)) }
    var label by remember { mutableStateOf(existing?.label.orEmpty()) }
    var formula by remember {
        mutableStateOf(
            (existing as? WorksheetCalculation.RowFormula)?.formula
                ?: (existing as? WorksheetCalculation.GroupFormula)?.formula.orEmpty()
        )
    }
    var aggregateFn by remember {
        mutableStateOf(
            (existing as? WorksheetCalculation.Aggregate)?.fn ?: WorksheetAggregateFn.Sum
        )
    }
    var windowFn by remember {
        mutableStateOf(
            (existing as? WorksheetCalculation.Window)?.fn ?: WorksheetWindowFn.RunningTotal
        )
    }
    var source by remember {
        mutableStateOf(
            sourceColumn(existing)
                ?: sample.columns.firstOrNull { isNumericType(it.dataType) }?.name.orEmpty()
        )
    }
    var groupColumn by remember {
        mutableStateOf(groupColumn(existing) ?: config.groups.lastOrNull()?.column.orEmpty())
    }
    var grain by remember {
        mutableStateOf(
            (existing as? WorksheetCalculation.Window)?.grain ?: WorksheetGrain.DetailRows
        )
    }
    var offset by remember {
        mutableStateOf(((existing as? WorksheetCalculation.Window)?.offset ?: 1).toString())
    }
    var formatKind by remember {
        mutableStateOf(existing?.numberFormat?.kind ?: NumberFormatKind.Auto)
    }
    val id = existing?.id ?: "calc_${UUID.randomUUID().toString().take(8)}"
    val numericColumns = sample.columns.filter { isNumericType(it.dataType) }
    val groupCalculations =
        config.calculations.filter {
            it is WorksheetCalculation.Aggregate || it is WorksheetCalculation.GroupFormula
        }
    val formulaTokens =
        if (kind == CalculationKind.GroupFormula) {
            groupCalculations.filterNot { it.id == id }.map { it.id to it.label }
        } else {
            sample.columns.map { it.name to displayColumnLabel(it.name) } +
                config.calculations
                    .filterIsInstance<WorksheetCalculation.RowFormula>()
                    .filterNot { it.id == id }
                    .map { it.id to it.label }
        }
    val formulaValidation =
        if (
            formula.isBlank() ||
                kind !in setOf(CalculationKind.RowFormula, CalculationKind.GroupFormula)
        ) {
            null
        } else {
            evaluatePivotFormula(
                    formula,
                    formulaTokens.associate { it.first to java.math.BigDecimal.ONE },
                )
                .error
        }
    val orderedWindow =
        windowFn in
            setOf(
                WorksheetWindowFn.RunningTotal,
                WorksheetWindowFn.RunningAverage,
                WorksheetWindowFn.PreviousValue,
                WorksheetWindowFn.DifferenceFromPrevious,
            )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add calculation" else "Edit calculation") },
        text = {
            Column(
                modifier = Modifier.widthIn(min = 560.dp, max = 620.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    CalculationKind.entries.forEach { choice ->
                        SelectPill(choice.label, kind == choice) { kind = choice }
                    }
                }
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                when (kind) {
                    CalculationKind.RowFormula,
                    CalculationKind.GroupFormula -> {
                        OutlinedTextField(
                            value = formula,
                            onValueChange = { formula = it },
                            label = { Text("Formula") },
                            placeholder = { Text("[amount] - [discount]") },
                            supportingText = {
                                Text(
                                    formulaValidation
                                        ?: "Use field tokens, numbers, parentheses, and +, -, *, /."
                                )
                            },
                            isError = formulaValidation != null,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "Insert field or calculation",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            formulaTokens.forEach { (token, display) ->
                                SelectPill(display, false) {
                                    formula += if (formula.isBlank()) "[$token]" else " [$token]"
                                }
                            }
                        }
                    }
                    CalculationKind.Aggregate -> {
                        SelectRow(
                            "Summary",
                            WorksheetAggregateFn.entries,
                            aggregateFn,
                            { it.name.toDisplayWords() },
                        ) {
                            aggregateFn = it
                        }
                        val aggregateSources =
                            if (aggregateFn == WorksheetAggregateFn.Count)
                                listOf("") + numericColumns.map { it.name }
                            else numericColumns.map { it.name }
                        SelectRow(
                            "Value",
                            aggregateSources,
                            source,
                            { if (it.isBlank()) "Rows" else displayColumnLabel(it) },
                        ) {
                            source = it
                        }
                    }
                    CalculationKind.Window -> {
                        SelectRow(
                            "Quick calculation",
                            WorksheetWindowFn.entries,
                            windowFn,
                            { it.name.toDisplayWords() },
                        ) {
                            windowFn = it
                        }
                        SelectRow(
                            "Value",
                            numericColumns.map { it.name } + config.calculations.map { it.id },
                            source,
                            { token ->
                                config.calculations.firstOrNull { it.id == token }?.label
                                    ?: displayColumnLabel(token)
                            },
                        ) {
                            source = it
                        }
                        if (config.groups.isNotEmpty()) {
                            SelectRow(
                                "Calculate over",
                                WorksheetGrain.entries,
                                grain,
                                {
                                    if (it == WorksheetGrain.DetailRows) "Detail rows"
                                    else "Group rows"
                                },
                            ) {
                                grain = it
                            }
                        }
                        if (
                            windowFn in
                                setOf(
                                    WorksheetWindowFn.PreviousValue,
                                    WorksheetWindowFn.DifferenceFromPrevious,
                                )
                        )
                            OutlinedTextField(
                                value = offset,
                                onValueChange = { offset = it },
                                label = { Text("Offset") },
                                singleLine = true,
                            )
                        if (orderedWindow && config.sorts.isEmpty())
                            Text(
                                "The worksheet will be sorted by the selected value in ascending order.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                    }
                }
                if (kind != CalculationKind.RowFormula && config.groups.isNotEmpty()) {
                    SelectRow(
                        "Group level",
                        listOf("") + config.groups.map { it.column },
                        groupColumn,
                        { if (it.isBlank()) "All / grand total" else displayColumnLabel(it) },
                    ) {
                        groupColumn = it
                    }
                }
                SelectRow(
                    "Format",
                    NumberFormatKind.entries,
                    formatKind,
                    { it.name.toDisplayWords() },
                ) {
                    formatKind = it
                }
            }
        },
        confirmButton = {
            val enabled =
                label.isNotBlank() &&
                    when (kind) {
                        CalculationKind.RowFormula,
                        CalculationKind.GroupFormula ->
                            formula.isNotBlank() && formulaValidation == null
                        CalculationKind.Aggregate ->
                            aggregateFn == WorksheetAggregateFn.Count || source.isNotBlank()
                        CalculationKind.Window ->
                            source.isNotBlank() && (!orderedWindow || source.isNotBlank())
                    }
            PrimaryButton(
                onClick = {
                    val calculation =
                        when (kind) {
                            CalculationKind.RowFormula ->
                                WorksheetCalculation.RowFormula(
                                    id,
                                    label.trim(),
                                    formula.trim(),
                                    numberFormat = PivotNumberFormat(kind = formatKind),
                                )
                            CalculationKind.GroupFormula ->
                                WorksheetCalculation.GroupFormula(
                                    id,
                                    label.trim(),
                                    formula.trim(),
                                    groupColumn.ifBlank { null },
                                    PivotNumberFormat(kind = formatKind),
                                )
                            CalculationKind.Aggregate ->
                                WorksheetCalculation.Aggregate(
                                    id,
                                    label.trim(),
                                    aggregateFn,
                                    source.takeUnless {
                                        aggregateFn == WorksheetAggregateFn.Count && it.isBlank()
                                    },
                                    groupColumn.ifBlank { null },
                                    PivotNumberFormat(kind = formatKind),
                                )
                            CalculationKind.Window ->
                                WorksheetCalculation.Window(
                                    id = id,
                                    label = label.trim(),
                                    fn = windowFn,
                                    source =
                                        config.calculations
                                            .firstOrNull { it.id == source }
                                            ?.let { WorksheetValueRef.Calculation(it.id) }
                                            ?: WorksheetValueRef.Column(source),
                                    grain = grain,
                                    groupColumn = groupColumn.ifBlank { null },
                                    restartColumns =
                                        config.groups
                                            .takeWhile { it.column != groupColumn }
                                            .map { it.column },
                                    offset = offset.toIntOrNull()?.coerceAtLeast(1) ?: 1,
                                    numberFormat = PivotNumberFormat(kind = formatKind),
                                )
                        }
                    val requiredSort =
                        if (
                            kind == CalculationKind.Window &&
                                orderedWindow &&
                                config.sorts.isEmpty()
                        ) {
                            WorksheetSort(
                                (calculation as WorksheetCalculation.Window).source,
                                SortDir.Asc,
                            )
                        } else null
                    onSave(calculation, requiredSort)
                },
                enabled = enabled,
            ) {
                Text(if (existing == null) "Add" else "Apply")
            }
        },
        dismissButton = { SecondaryButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun <T> SelectRow(
    label: String,
    choices: List<T>,
    selected: T,
    display: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        SectionLabel(label)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            choices.forEach { choice ->
                SelectPill(display(choice), choice == selected) { onSelect(choice) }
            }
        }
    }
}

@Composable
private fun SelectPill(label: String, selected: Boolean, onClick: () -> Unit) {
    SelectablePill(label, selected, onClick)
}

private enum class CalculationKind(val label: String) {
    RowFormula("Row formula"),
    Aggregate("Summary"),
    GroupFormula("Group formula"),
    Window("Quick calculation"),
}

private fun calculationKind(calculation: WorksheetCalculation?): CalculationKind =
    when (calculation) {
        is WorksheetCalculation.RowFormula -> CalculationKind.RowFormula
        is WorksheetCalculation.Aggregate -> CalculationKind.Aggregate
        is WorksheetCalculation.GroupFormula -> CalculationKind.GroupFormula
        is WorksheetCalculation.Window -> CalculationKind.Window
        null -> CalculationKind.Window
    }

private fun sourceColumn(calculation: WorksheetCalculation?): String? =
    when (calculation) {
        is WorksheetCalculation.Aggregate -> calculation.sourceColumn
        is WorksheetCalculation.Window ->
            when (val source = calculation.source) {
                is WorksheetValueRef.Column -> source.column
                is WorksheetValueRef.Calculation -> source.id
            }
        else -> null
    }

private fun groupColumn(calculation: WorksheetCalculation?): String? =
    when (calculation) {
        is WorksheetCalculation.Aggregate -> calculation.groupColumn
        is WorksheetCalculation.GroupFormula -> calculation.groupColumn
        is WorksheetCalculation.Window -> calculation.groupColumn
        else -> null
    }

private fun cycleSort(sorts: List<WorksheetSort>, target: WorksheetValueRef): List<WorksheetSort> {
    val existing = sorts.firstOrNull { it.target == target }
    return when (existing?.dir) {
        null -> sorts + WorksheetSort(target, SortDir.Asc)
        SortDir.Asc -> sorts.map { if (it.target == target) it.copy(dir = SortDir.Desc) else it }
        SortDir.Desc -> sorts.filterNot { it.target == target }
    }
}

private fun calculationSummary(calculation: WorksheetCalculation): String =
    when (calculation) {
        is WorksheetCalculation.RowFormula -> calculation.formula
        is WorksheetCalculation.Aggregate ->
            "${calculation.fn.name.toDisplayWords()} · ${calculation.sourceColumn?.let(::displayColumnLabel) ?: "rows"}"
        is WorksheetCalculation.GroupFormula -> calculation.formula
        is WorksheetCalculation.Window ->
            "${calculation.fn.name.toDisplayWords()} · ${calculation.grain.name.toDisplayWords()}"
    }

private fun filterOps(type: String): List<WorksheetFilterOp> =
    when {
        isNumericType(type) || isTemporalType(type) ->
            listOf(
                WorksheetFilterOp.Members,
                WorksheetFilterOp.Equals,
                WorksheetFilterOp.NotEquals,
                WorksheetFilterOp.GreaterThan,
                WorksheetFilterOp.GreaterThanOrEqual,
                WorksheetFilterOp.LessThan,
                WorksheetFilterOp.LessThanOrEqual,
                WorksheetFilterOp.Between,
                WorksheetFilterOp.IsNull,
                WorksheetFilterOp.IsNotNull,
            )
        else ->
            listOf(
                WorksheetFilterOp.Members,
                WorksheetFilterOp.Contains,
                WorksheetFilterOp.Equals,
                WorksheetFilterOp.NotEquals,
                WorksheetFilterOp.StartsWith,
                WorksheetFilterOp.EndsWith,
                WorksheetFilterOp.IsNull,
                WorksheetFilterOp.IsNotNull,
            )
    }

private fun defaultFilterOp(type: String): WorksheetFilterOp =
    if (isNumericType(type) || isTemporalType(type)) WorksheetFilterOp.Equals
    else WorksheetFilterOp.Contains

private fun isNumericType(type: String): Boolean = classifyColumn(type).isNumeric()

private fun isTemporalType(type: String): Boolean = classifyColumn(type).isTemporal()

private fun groupingLabel(grouping: PivotGrouping): String =
    when (grouping) {
        PivotGrouping.Exact -> "Exact values"
        is PivotGrouping.Date -> grouping.unit.name.toDisplayWords()
        is PivotGrouping.NumberBin -> "Number bins"
    }

private fun sameGroupingKind(left: PivotGrouping, right: PivotGrouping): Boolean =
    when {
        left is PivotGrouping.Date && right is PivotGrouping.Date -> left.unit == right.unit
        left is PivotGrouping.NumberBin && right is PivotGrouping.NumberBin -> true
        else -> left == right
    }

private fun String.toDisplayWords(): String =
    replace(Regex("([a-z])([A-Z])"), "$1 $2").lowercase().replaceFirstChar(Char::uppercase)

private fun formatWorksheetValue(value: ResultCell, format: PivotNumberFormat?): String {
    val number =
        when (value) {
            is ResultCell.IntegerCell -> value.value.toDouble()
            is ResultCell.FloatCell -> value.value
            else -> return formatCell(value)
        }
    val applied = format ?: return formatCell(value)
    if (applied.kind == NumberFormatKind.Auto) return formatCell(value)
    if (applied.kind == NumberFormatKind.Scientific)
        return "%1$.${applied.decimals}e".format(number)
    val pattern = buildString {
        append(if (applied.thousandsSeparator) "#,##0" else "0")
        if (applied.decimals > 0) append('.').append("0".repeat(applied.decimals))
    }
    val formatter = DecimalFormat(pattern)
    return when (applied.kind) {
        NumberFormatKind.Percent -> "${formatter.format(number * 100)}%"
        NumberFormatKind.Currency ->
            runCatching { Currency.getInstance(applied.currencyCode).symbol }
                .getOrDefault(applied.currencyCode) + formatter.format(number)
        NumberFormatKind.Number -> formatter.format(number)
        NumberFormatKind.Auto,
        NumberFormatKind.Scientific -> formatCell(value)
    }
}
