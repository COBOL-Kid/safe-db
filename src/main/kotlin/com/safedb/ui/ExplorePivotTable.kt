package com.safedb.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.HorizontalScrollbar
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.safedb.explore.ExploreConfig
import com.safedb.explore.ExplorePivotLayout
import com.safedb.explore.ExplorePreviewResult
import com.safedb.explore.ExploreSortTarget
import com.safedb.explore.SortDir
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
    modifier: Modifier = Modifier,
) {
    val result = preview.result
    val layout = preview.layout
    val columns = remember(result.columns, result.rows) { buildResultTableColumns(result) }
    val groupCount = result.rowCount - if (layout.hasGrandTotalRow) 1 else 0

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
                if (layout.rowDimensions.isEmpty()) "Summary" else "$groupCount group${if (groupCount == 1) "" else "s"}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            if (result.warnings.isNotEmpty()) {
                StatusChip(
                    "${result.warnings.size} warning${if (result.warnings.size == 1) "" else "s"}",
                    StatusChipKind.WARNING,
                )
            }
            Spacer(Modifier.weight(1f))
            config.sort?.let { sort ->
                Text(
                    text = "Sorted ${if (sort.dir == SortDir.Asc) "ascending" else "descending"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (result.warnings.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SafeDbTheme.colors.warningContainer.copy(alpha = 0.55f))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                result.warnings.forEach { warning ->
                    Text(
                        text = "⚠ $warning",
                        style = MaterialTheme.typography.labelSmall,
                        color = SafeDbTheme.colors.onWarningContainer,
                    )
                }
            }
        }

        if (result.rows.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "No groups to display.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
                if (layout.columnDimension != null) {
                    PivotGroupHeader(columns = columns, layout = layout)
                }
                Row(
                    modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainerLow),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    columns.forEach { column ->
                        val index = column.index
                        val totalColumn = layout.isTotalColumn(index)
                        PivotLeafHeader(
                            text = pivotLeafLabel(layout, index, column.label),
                            width = column.widthDp,
                            alignment = column.alignment,
                            target = pivotSortTarget(layout, index),
                            sortTarget = config.sort?.target,
                            sortDirection = config.sort?.dir,
                            total = totalColumn,
                            onSort = { target -> onConfigChange(toggleExploreSort(config, target)) },
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                result.rows.forEachIndexed { rowIndex, row ->
                    val isGrandTotal = layout.hasGrandTotalRow && rowIndex == result.rows.lastIndex
                    val interactionSource = remember { MutableInteractionSource() }
                    val hovered by interactionSource.collectIsHoveredAsState()
                    val rowBackground = when {
                        isGrandTotal -> SafeDbTheme.colors.accentContainer.copy(alpha = 0.62f)
                        hovered -> SafeDbTheme.colors.accentContainer.copy(alpha = 0.45f)
                        rowIndex % 2 == 1 -> MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.6f)
                        else -> MaterialTheme.colorScheme.surface
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(rowBackground)
                            .hoverable(interactionSource),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        columns.forEach { column ->
                            val cell = row.getOrNull(column.index)
                            val totalColumn = layout.isTotalColumn(column.index)
                            PivotCell(
                                cell = cell,
                                width = column.widthDp,
                                alignment = column.alignment,
                                emphasized = isGrandTotal || totalColumn,
                                background = if (totalColumn && !isGrandTotal) {
                                    SafeDbTheme.colors.accentContainer.copy(alpha = 0.28f)
                                } else {
                                    Color.Transparent
                                },
                            )
                        }
                    }
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
private fun PivotGroupHeader(
    columns: List<ResultTableColumnLayout>,
    layout: ExplorePivotLayout,
) {
    Row(
        modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainer),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        layout.rowDimensions.indices.forEach { index ->
            Spacer(Modifier.width(columns[index].widthDp.dp))
        }
        layout.columnGroups.forEach { group ->
            val width = group.measureAliases.indices.sumOf { offset ->
                columns.getOrNull(group.startColumnIndex + offset)?.widthDp ?: 0
            }
            Text(
                text = group.label.orEmpty(),
                modifier = Modifier
                    .width(width.dp)
                    .background(
                        if (group.isTotal) SafeDbTheme.colors.accentContainer.copy(alpha = 0.5f) else Color.Transparent,
                    )
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (group.isTotal) {
                    SafeDbTheme.colors.onAccentContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun PivotLeafHeader(
    text: String,
    width: Int,
    alignment: ResultTableCellAlignment,
    target: ExploreSortTarget?,
    sortTarget: ExploreSortTarget?,
    sortDirection: SortDir?,
    total: Boolean,
    onSort: (ExploreSortTarget) -> Unit,
) {
    val active = target != null && target == sortTarget
    val label = if (active) "$text ${if (sortDirection == SortDir.Asc) "↑" else "↓"}" else text
    Text(
        text = label,
        modifier = Modifier
            .width(width.dp)
            .background(if (total) SafeDbTheme.colors.accentContainer.copy(alpha = 0.38f) else Color.Transparent)
            .clip(RoundedCornerShape(6.dp))
            .then(
                if (target != null) {
                    Modifier
                        .clickable { onSort(target) }
                        .pointerHoverIcon(PointerIcon.Hand)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 12.dp, vertical = 7.dp),
        style = DataMono.copy(fontWeight = if (active || total) FontWeight.SemiBold else FontWeight.Medium),
        color = if (active) SafeDbTheme.colors.actionPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = alignment.textAlign(),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun PivotCell(
    cell: ResultCell?,
    width: Int,
    alignment: ResultTableCellAlignment,
    emphasized: Boolean,
    background: Color,
) {
    val isNull = cell == null || cell is ResultCell.Null
    Text(
        text = if (isNull) "null" else formatCell(cell),
        modifier = Modifier
            .width(width.dp)
            .background(background)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        style = DataMono.copy(
            fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Normal,
            fontStyle = if (isNull) FontStyle.Italic else FontStyle.Normal,
        ),
        color = if (isNull) {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        textAlign = alignment.textAlign(),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

private fun ResultTableCellAlignment.textAlign(): TextAlign = when (this) {
    ResultTableCellAlignment.Start -> TextAlign.Start
    ResultTableCellAlignment.End -> TextAlign.End
}

internal fun pivotSortTarget(layout: ExplorePivotLayout, columnIndex: Int): ExploreSortTarget? {
    layout.rowDimensions.getOrNull(columnIndex)?.let { return ExploreSortTarget.Dimension(it.column) }
    val group = layout.columnGroups.firstOrNull { candidate ->
        columnIndex >= candidate.startColumnIndex &&
            columnIndex < candidate.startColumnIndex + candidate.measureAliases.size
    } ?: return null
    val offset = columnIndex - group.startColumnIndex
    return group.measureAliases.getOrNull(offset)?.let(ExploreSortTarget::Measure)
}

internal fun pivotLeafLabel(
    layout: ExplorePivotLayout,
    columnIndex: Int,
    fallback: String,
): String {
    layout.rowDimensions.getOrNull(columnIndex)?.let { return it.label }
    val target = pivotSortTarget(layout, columnIndex) as? ExploreSortTarget.Measure ?: return fallback
    return layout.measures.firstOrNull { it.alias == target.alias }?.label ?: fallback
}

private fun ExplorePivotLayout.isTotalColumn(columnIndex: Int): Boolean =
    columnGroups.any { group ->
        group.isTotal && columnIndex >= group.startColumnIndex &&
            columnIndex < group.startColumnIndex + group.measureAliases.size
    }
