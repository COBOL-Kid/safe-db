package com.safedb.ui

import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.TableRows
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.safedb.model.QueryResult
import com.safedb.model.ResultCell
import com.safedb.model.ResultColumn
import com.safedb.ui.components.EmptyState
import com.safedb.ui.components.StatusChip
import com.safedb.ui.components.StatusChipKind
import com.safedb.ui.theme.DataMono
import com.safedb.ui.theme.SafeDbTheme

@Composable
fun ResultsTable(
    result: QueryResult,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val columns = remember(result.columns, result.rows) { buildResultTableColumns(result) }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${result.rowCount} row${if (result.rowCount != 1) "s" else ""}",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (result.truncated) {
                StatusChip("Truncated", StatusChipKind.WARNING)
            }
            if (result.warnings.isNotEmpty()) {
                StatusChip(
                    "${result.warnings.size} warning${if (result.warnings.size != 1) "s" else ""}",
                    StatusChipKind.ERROR,
                )
            }
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.weight(1f))
            actions()
        }

        if (result.warnings.isNotEmpty()) {
            Column(
                modifier =
                    Modifier.fillMaxWidth()
                        .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f))
                        .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                for (warning in result.warnings) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            Icons.Outlined.WarningAmber,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(warning, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        if (result.rows.isEmpty() && result.columns.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.TableRows,
                title = "No rows returned",
                subtitle = "The query completed without any rows.",
            )
        } else {
            val horizontalScroll = rememberScrollState()
            val listState = rememberLazyListState()
            val tableWidth = columns.sumOf { it.widthDp }.dp
            Box(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier =
                        Modifier.fillMaxSize()
                            .padding(end = 8.dp, bottom = 8.dp)
                            .horizontalScroll(horizontalScroll)
                ) {
                    Row(
                        modifier =
                            Modifier.width(tableWidth)
                                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        for ((_, label, widthDp, alignment) in columns) {
                            Text(
                                label,
                                modifier =
                                    Modifier.width(widthDp.dp)
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                style = DataMono.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = alignment.textAlign,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    if (result.rows.isEmpty()) {
                        Text(
                            "No rows returned.",
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.width(tableWidth).weight(1f),
                        ) {
                            itemsIndexed(result.rows, key = { index, _ -> index }) { rowIdx, row ->
                                val interactionSource = remember { MutableInteractionSource() }
                                val hovered by interactionSource.collectIsHoveredAsState()
                                val rowBg =
                                    when {
                                        hovered ->
                                            SafeDbTheme.colors.accentContainer.copy(alpha = 0.55f)
                                        rowIdx % 2 == 1 ->
                                            MaterialTheme.colorScheme.surfaceContainerLow.copy(
                                                alpha = 0.6f
                                            )
                                        else -> MaterialTheme.colorScheme.surface
                                    }
                                Row(
                                    modifier =
                                        Modifier.width(tableWidth)
                                            .background(rowBg)
                                            .hoverable(interactionSource),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    columns.forEach { column ->
                                        val cell = row.getOrNull(column.index)
                                        Text(
                                            if (cell == ResultCell.Null) "null"
                                            else formatCell(cell),
                                            modifier =
                                                Modifier.width(column.widthDp.dp)
                                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                            style = DataMono,
                                            fontStyle =
                                                if (cell == ResultCell.Null) FontStyle.Italic
                                                else FontStyle.Normal,
                                            textAlign = column.alignment.textAlign,
                                            color =
                                                if (cell == ResultCell.Null) {
                                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                                        alpha = 0.6f
                                                    )
                                                } else {
                                                    MaterialTheme.colorScheme.onSurface
                                                },
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
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
                HorizontalScrollbar(
                    adapter = rememberScrollbarAdapter(horizontalScroll),
                    modifier =
                        Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(end = 8.dp),
                )
                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(listState),
                    modifier =
                        Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(bottom = 8.dp),
                )
            }
        }
    }
}

internal data class ResultTableColumnLayout(
    val index: Int,
    val label: String,
    val widthDp: Int,
    val alignment: ResultTableCellAlignment,
)

internal enum class ResultTableCellAlignment {
    Start,
    End,
}

private val ResultTableCellAlignment.textAlign: TextAlign
    get() =
        when (this) {
            ResultTableCellAlignment.Start -> TextAlign.Start
            ResultTableCellAlignment.End -> TextAlign.End
        }

internal fun buildResultTableColumns(result: QueryResult): List<ResultTableColumnLayout> {
    val labels = result.columns.map(::displayColumnName)
    val maxLengths = IntArray(labels.size) { labels[it].length }
    val hasConcrete = BooleanArray(labels.size)
    val allNumeric = BooleanArray(labels.size) { true }
    result.rows.forEach { row ->
        labels.indices.forEach { index ->
            val cell = row.getOrNull(index)
            maxLengths[index] = maxOf(maxLengths[index], formatCell(cell).length)
            if (cell != null && cell !is ResultCell.Null) {
                hasConcrete[index] = true
                if (
                    cell !is ResultCell.IntegerCell &&
                        cell !is ResultCell.FloatCell &&
                        cell !is ResultCell.BoolCell
                ) {
                    allNumeric[index] = false
                }
            }
        }
    }
    return labels.mapIndexed { index, label ->
        ResultTableColumnLayout(
            index = index,
            label = label,
            widthDp =
                (maxLengths[index] * RESULT_TABLE_CHAR_WIDTH_DP +
                        RESULT_TABLE_HORIZONTAL_PADDING_DP)
                    .coerceIn(RESULT_TABLE_MIN_COLUMN_WIDTH_DP, RESULT_TABLE_MAX_COLUMN_WIDTH_DP),
            alignment =
                if (hasConcrete[index] && allNumeric[index]) {
                    ResultTableCellAlignment.End
                } else {
                    ResultTableCellAlignment.Start
                },
        )
    }
}

internal fun formatCell(value: ResultCell?): String =
    when (value) {
        null -> ""
        is ResultCell.Null -> ""
        is ResultCell.BoolCell -> value.value.toString()
        is ResultCell.IntegerCell -> value.value.toString()
        is ResultCell.FloatCell -> value.value.toString()
        is ResultCell.TextCell -> value.value.text + if (value.value.truncated) "…" else ""
        is ResultCell.BinaryCell -> value.value.base64 + if (value.value.truncated) "…" else ""
    }

internal fun displayColumnName(column: ResultColumn): String {
    val raw = column.name
    return raw.replace(Regex("^t\\d+__(.+)$"), "$1")
}

internal fun resultColumnAlignment(cells: List<ResultCell?>): ResultTableCellAlignment {
    val concreteCells = cells.filterNotNull().filterNot { it is ResultCell.Null }
    if (concreteCells.isEmpty()) return ResultTableCellAlignment.Start
    return if (
        concreteCells.all {
            it is ResultCell.IntegerCell || it is ResultCell.FloatCell || it is ResultCell.BoolCell
        }
    ) {
        ResultTableCellAlignment.End
    } else {
        ResultTableCellAlignment.Start
    }
}

internal fun resultColumnWidthDp(label: String, cells: List<ResultCell?>): Int {
    val longestText =
        (listOf(label) + cells.map(::formatCell)).maxOfOrNull { it.length } ?: label.length
    return (longestText * RESULT_TABLE_CHAR_WIDTH_DP + RESULT_TABLE_HORIZONTAL_PADDING_DP).coerceIn(
        RESULT_TABLE_MIN_COLUMN_WIDTH_DP,
        RESULT_TABLE_MAX_COLUMN_WIDTH_DP,
    )
}

private const val RESULT_TABLE_CHAR_WIDTH_DP = 9
private const val RESULT_TABLE_HORIZONTAL_PADDING_DP = 32
private const val RESULT_TABLE_MIN_COLUMN_WIDTH_DP = 72
private const val RESULT_TABLE_MAX_COLUMN_WIDTH_DP = 280
