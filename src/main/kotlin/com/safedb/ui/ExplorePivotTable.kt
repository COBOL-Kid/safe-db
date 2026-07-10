package com.safedb.ui

import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.safedb.explore.ExploreConfig
import com.safedb.explore.ExplorePivotLayout
import com.safedb.explore.ExplorePreviewResult
import com.safedb.explore.ExploreSortTarget
import com.safedb.explore.PivotHeaderCell
import com.safedb.explore.PivotRowEntry
import com.safedb.explore.PivotRowKind
import com.safedb.explore.SortDir
import com.safedb.explore.ShowAsMode
import com.safedb.model.ResultCell
import com.safedb.ui.components.StatusChip
import com.safedb.ui.components.StatusChipKind
import com.safedb.ui.theme.DataMono
import com.safedb.ui.theme.SafeDbTheme

@Composable
internal fun ExplorePivotTable(
    preview: ExplorePreviewResult,
    config: ExploreConfig,
    onConfigChange: (ExploreConfig) -> Unit,
    onToggleRow: (String) -> Unit,
    onToggleColumn: (String) -> Unit,
    onDrill: (rowPath: String, columnPath: String, measureAlias: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val result = preview.result
    val layout = preview.layout
    val columns = remember(result.columns, result.rows) { buildResultTableColumns(result) }
    val rowCount = layout.rowEntries.count { it.kind in setOf(PivotRowKind.Leaf, PivotRowKind.Subtotal) }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (layout.rowDimensions.isEmpty()) "Summary" else "$rowCount visible group${if (rowCount == 1) "" else "s"}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            if (result.warnings.isNotEmpty()) {
                StatusChip("${result.warnings.size} warning${if (result.warnings.size == 1) "" else "s"}", StatusChipKind.WARNING)
            }
            Spacer(Modifier.weight(1f))
            Text(
                "Double-click a value to show sampled rows",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (result.warnings.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SafeDbTheme.colors.warningContainer.copy(alpha = 0.55f))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                result.warnings.forEach { warning ->
                    Text("⚠ $warning", style = MaterialTheme.typography.labelSmall, color = SafeDbTheme.colors.onWarningContainer)
                }
            }
        }

        if (result.rows.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No groups to display.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Column
        }

        val horizontalScroll = rememberScrollState()
        val verticalScroll = rememberScrollState()
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(end = 8.dp, bottom = 8.dp)
                    .verticalScroll(verticalScroll)
                    .horizontalScroll(horizontalScroll),
            ) {
                layout.columnHeaderRows.forEach { headerRow ->
                    PivotNestedHeaderRow(
                        headerRow = headerRow,
                        layout = layout,
                        columns = columns,
                        onToggleColumn = onToggleColumn,
                    )
                }
                PivotLeafHeaderRow(layout, columns, config, onConfigChange)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                result.rows.forEachIndexed { rowIndex, row ->
                    val entry = layout.rowEntries.getOrNull(rowIndex)
                    val formatted = layout.formattedRows.getOrNull(rowIndex).orEmpty()
                    PivotDataRow(
                        row = row,
                        formatted = formatted,
                        entry = entry,
                        layout = layout,
                        columns = columns,
                        onToggleRow = onToggleRow,
                        onDrill = onDrill,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))
                }
            }
            HorizontalScrollbar(
                adapter = rememberScrollbarAdapter(horizontalScroll),
                modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(end = 8.dp),
            )
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(verticalScroll),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(bottom = 8.dp),
            )
        }
    }
}

@Composable
private fun PivotNestedHeaderRow(
    headerRow: List<PivotHeaderCell>,
    layout: ExplorePivotLayout,
    columns: List<ResultTableColumnLayout>,
    onToggleColumn: (String) -> Unit,
) {
    Row(
        modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainer),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val rowHeaderWidth = if (layout.rowDimensions.isNotEmpty()) columns.firstOrNull()?.widthDp ?: 0 else 0
        if (rowHeaderWidth > 0) Spacer(Modifier.width(rowHeaderWidth.dp))
        headerRow.forEach { header ->
            val startColumn = (if (layout.rowDimensions.isNotEmpty()) 1 else 0) + header.startLeafIndex * layout.measures.size
            val spanColumns = header.leafSpan * layout.measures.size
            val width = (startColumn until startColumn + spanColumns).sumOf { columns.getOrNull(it)?.widthDp ?: 0 }
            Row(
                modifier = Modifier
                    .width(width.dp)
                    .background(if (header.isTotal) SafeDbTheme.colors.accentContainer.copy(alpha = 0.45f) else Color.Transparent)
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (header.hasChildren) {
                    Text(
                        if (header.expanded) "−" else "+",
                        modifier = Modifier
                            .clickable { onToggleColumn(header.pathKey) }
                            .pointerHoverIcon(PointerIcon.Hand)
                            .padding(end = 5.dp),
                        color = SafeDbTheme.colors.actionPrimary,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(
                    header.label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun PivotLeafHeaderRow(
    layout: ExplorePivotLayout,
    columns: List<ResultTableColumnLayout>,
    config: ExploreConfig,
    onConfigChange: (ExploreConfig) -> Unit,
) {
    Row(
        modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainerLow),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        columns.forEachIndexed { index, column ->
            val rowHeader = layout.rowDimensions.isNotEmpty() && index == 0
            val measureIndex = if (rowHeader) -1 else (index - if (layout.rowDimensions.isNotEmpty()) 1 else 0) % layout.measures.size
            val measure = layout.measures.getOrNull(measureIndex)
            val target = if (rowHeader) {
                layout.rowDimensions.firstOrNull()?.let { ExploreSortTarget.Dimension(it.column) }
            } else {
                measure?.takeUnless { it.showAs.mode in orderDependentModes }
                    ?.let { ExploreSortTarget.Measure(it.alias) }
            }
            val active = target != null && target == config.sort?.target
            val label = if (rowHeader) {
                "Row labels"
            } else {
                measure?.label ?: column.label
            } + if (active) " ${if (config.sort?.dir == SortDir.Asc) "↑" else "↓"}" else ""
            Text(
                label,
                modifier = Modifier
                    .width(column.widthDp.dp)
                    .then(
                        if (target != null) Modifier.clickable { onConfigChange(toggleExploreSort(config, target)) }
                            .pointerHoverIcon(PointerIcon.Hand) else Modifier,
                    )
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                style = DataMono.copy(fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium),
                color = if (active) SafeDbTheme.colors.actionPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = column.alignment.textAlign(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PivotDataRow(
    row: List<ResultCell>,
    formatted: List<String>,
    entry: PivotRowEntry?,
    layout: ExplorePivotLayout,
    columns: List<ResultTableColumnLayout>,
    onToggleRow: (String) -> Unit,
    onDrill: (String, String, String) -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val emphasized = entry?.kind in setOf(PivotRowKind.Subtotal, PivotRowKind.GrandTotal)
    val background = when {
        entry?.kind == PivotRowKind.GrandTotal -> SafeDbTheme.colors.accentContainer.copy(alpha = 0.62f)
        entry?.kind == PivotRowKind.Subtotal -> MaterialTheme.colorScheme.surfaceContainer
        entry?.kind == PivotRowKind.Group -> MaterialTheme.colorScheme.surfaceContainerLow
        hovered -> SafeDbTheme.colors.accentContainer.copy(alpha = 0.38f)
        else -> MaterialTheme.colorScheme.surface
    }
    Row(
        modifier = Modifier.fillMaxWidth().background(background).hoverable(interactionSource),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        columns.forEachIndexed { columnIndex, column ->
            val rowHeader = layout.rowDimensions.isNotEmpty() && columnIndex == 0
            if (rowHeader) {
                Row(
                    modifier = Modifier
                        .width(column.widthDp.dp)
                        .padding(start = (10 + (entry?.depth ?: 0) * 16).dp, end = 10.dp, top = 7.dp, bottom = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (entry?.hasChildren == true) {
                        Text(
                            if (entry.expanded) "−" else "+",
                            modifier = Modifier
                                .clickable { onToggleRow(entry.pathKey) }
                                .pointerHoverIcon(PointerIcon.Hand)
                                .padding(end = 6.dp),
                            color = SafeDbTheme.colors.actionPrimary,
                            fontWeight = FontWeight.Bold,
                        )
                    } else if ((entry?.depth ?: 0) > 0) {
                        Spacer(Modifier.width(12.dp))
                    }
                    Text(
                        formatted.getOrNull(columnIndex) ?: formatCell(row.getOrNull(columnIndex)),
                        style = DataMono.copy(fontWeight = if (emphasized || entry?.kind == PivotRowKind.Group) FontWeight.SemiBold else FontWeight.Normal),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                val valueOffset = columnIndex - if (layout.rowDimensions.isNotEmpty()) 1 else 0
                val leafIndex = valueOffset / layout.measures.size
                val measureIndex = valueOffset % layout.measures.size
                val leaf = layout.columnLeaves.getOrNull(leafIndex)
                val measure = layout.measures.getOrNull(measureIndex)
                val cell = row.getOrNull(columnIndex)
                val text = formatted.getOrNull(columnIndex) ?: formatCell(cell)
                Text(
                    if (cell is ResultCell.Null) "" else text,
                    modifier = Modifier
                        .width(column.widthDp.dp)
                        .pointerInput(entry?.pathKey, leaf?.pathKey, measure?.alias) {
                            detectTapGestures(
                                onDoubleTap = {
                                    if (entry != null && leaf != null && measure != null && entry.kind != PivotRowKind.Group) {
                                        onDrill(entry.pathKey, leaf.pathKey, measure.alias)
                                    }
                                },
                            )
                        }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    style = DataMono.copy(
                        fontWeight = if (emphasized || leaf?.isGrandTotal == true || leaf?.isSubtotal == true) FontWeight.SemiBold else FontWeight.Normal,
                        fontStyle = if (cell is ResultCell.Null) FontStyle.Italic else FontStyle.Normal,
                    ),
                    textAlign = column.alignment.textAlign(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun ResultTableCellAlignment.textAlign(): TextAlign = when (this) {
    ResultTableCellAlignment.Start -> TextAlign.Start
    ResultTableCellAlignment.End -> TextAlign.End
}

internal fun pivotSortTarget(layout: ExplorePivotLayout, columnIndex: Int): ExploreSortTarget? {
    if (layout.rowDimensions.isNotEmpty() && columnIndex == 0) {
        return layout.rowDimensions.firstOrNull()?.let { ExploreSortTarget.Dimension(it.column) }
    }
    val offset = columnIndex - if (layout.rowDimensions.isNotEmpty()) 1 else 0
    if (offset < 0 || layout.measures.isEmpty()) return null
    return layout.measures.getOrNull(offset % layout.measures.size)?.let { ExploreSortTarget.Measure(it.alias) }
}

internal fun pivotLeafLabel(layout: ExplorePivotLayout, columnIndex: Int, fallback: String): String {
    if (layout.rowDimensions.isNotEmpty() && columnIndex == 0) return "Row labels"
    val target = pivotSortTarget(layout, columnIndex) as? ExploreSortTarget.Measure ?: return fallback
    return layout.measures.firstOrNull { it.alias == target.alias }?.label ?: fallback
}

private val orderDependentModes = setOf(
    ShowAsMode.DifferenceFrom,
    ShowAsMode.PercentDifferenceFrom,
    ShowAsMode.RunningTotal,
    ShowAsMode.PercentRunningTotal,
)
