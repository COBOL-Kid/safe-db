package com.safedb.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.safedb.model.QueryResult
import com.safedb.model.ResultCell
import com.safedb.model.ResultColumn

@Composable
fun ResultsTable(
    result: QueryResult,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "${result.rowCount} row${if (result.rowCount != 1) "s" else ""}",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (result.truncated) {
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        "Truncated",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            if (result.warnings.isNotEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        "${result.warnings.size} warning${if (result.warnings.size != 1) "s" else ""}",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }

        if (result.warnings.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                for (warning in result.warnings) {
                    Text("⚠ $warning", style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        if (result.rows.isEmpty() && result.columns.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                Text("No rows returned.", style = MaterialTheme.typography.bodySmall)
            }
        } else {
            val horizontalScroll = rememberScrollState()
            val verticalScroll = rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(verticalScroll)
                    .horizontalScroll(horizontalScroll),
            ) {
                Row(
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(vertical = 4.dp),
                ) {
                    for (column in result.columns) {
                        Text(
                            displayColumnName(column),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (result.rows.isEmpty()) {
                    Text(
                        "No rows returned.",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    result.rows.forEachIndexed { rowIdx, row ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (rowIdx % 2 == 1) {
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                                    } else {
                                        MaterialTheme.colorScheme.surface
                                    },
                                ),
                        ) {
                            row.forEach { cell ->
                                val formatted = formatCell(cell)
                                if (isNullCell(cell)) {
                                    Text(
                                        "null",
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontStyle = FontStyle.Italic,
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                } else {
                                    Text(
                                        formatted,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                        style = MaterialTheme.typography.bodySmall,
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
    }
}

private fun formatCell(value: ResultCell): String = when (value) {
    is ResultCell.Null -> ""
    is ResultCell.BoolCell -> value.value.toString()
    is ResultCell.IntegerCell -> value.value.toString()
    is ResultCell.FloatCell -> value.value.toString()
    is ResultCell.TextCell -> value.value.text + if (value.value.truncated) "…" else ""
    is ResultCell.BinaryCell -> value.value.base64 + if (value.value.truncated) "…" else ""
}

private fun isNullCell(value: ResultCell): Boolean = value is ResultCell.Null

private fun displayColumnName(column: ResultColumn): String {
    val raw = column.name
    return raw.replace(Regex("^t\\d+__(.+)$"), "$1")
}
