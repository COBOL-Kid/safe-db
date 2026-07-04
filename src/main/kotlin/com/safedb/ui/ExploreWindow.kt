package com.safedb.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.safedb.explore.ExploreConfig
import com.safedb.explore.ExploreSort
import com.safedb.explore.ExploreSortTarget
import com.safedb.explore.MeasureFn
import com.safedb.explore.PivotDimension
import com.safedb.explore.PivotMeasure
import com.safedb.explore.SortDir
import com.safedb.explore.displayColumnLabel
import com.safedb.model.QuerySpec
import com.safedb.ui.components.BannerKind
import com.safedb.ui.components.MenuActionRow
import com.safedb.ui.components.PrimaryButton
import com.safedb.ui.components.SafeDropdownMenu
import com.safedb.ui.components.SecondaryButton
import com.safedb.ui.components.StatusChip
import com.safedb.ui.components.StatusChipKind
import com.safedb.ui.components.MessageBanner
import com.safedb.viewmodel.ExploreViewModel
import java.io.File
import javax.swing.JFileChooser

@Composable
fun ExploreWindowContent(
    viewModel: ExploreViewModel,
    currentSpec: QuerySpec,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val session = viewModel.session
    val preview = viewModel.preview
    val config = viewModel.config
    val columns = session.sample.columns
    val stale = viewModel.isStale(currentSpec)

    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Explore", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    session.connectionLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StatusChip("${session.sample.rowCount} sample rows", StatusChipKind.INFO)
            if (session.sample.truncated) StatusChip("Truncated", StatusChipKind.WARNING)
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Close Explore")
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            ExploreConfigPanel(
                config = config,
                columns = columns.map { PivotDimension(it.name, displayColumnLabel(it.name)) },
                onConfigChange = { next -> viewModel.updateConfig { next } },
                modifier = Modifier.width(328.dp).fillMaxHeight(),
            )

            Box(modifier = Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outline))

            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MessageBanner(
                        text = "Previewing the current query sample: ${session.sample.rowCount} rows",
                        kind = BannerKind.INFO,
                    )
                    if (session.sample.truncated) {
                        MessageBanner(
                            text = "This view is based on a truncated sample.",
                            kind = BannerKind.WARNING,
                        )
                    }
                    if (stale) {
                        MessageBanner(
                            text = "The builder query changed. Re-run the query and reopen Explore to analyze the new result.",
                            kind = BannerKind.WARNING,
                        )
                    }
                }

                ResultsTable(result = preview.result, modifier = Modifier.weight(1f).fillMaxWidth())

                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    viewModel.exportMessage?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    viewModel.exportError?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    Box(modifier = Modifier.weight(1f))
                    SecondaryButton(onClick = viewModel::clearExportMessages) {
                        Text("Clear status")
                    }
                    PrimaryButton(
                        onClick = {
                            chooseCsvFile(session.connectionLabel)?.let(viewModel::savePreviewCsv)
                        },
                    ) {
                        Text("Export CSV")
                    }
                }
            }
        }
    }
}

@Composable
private fun ExploreConfigPanel(
    config: ExploreConfig,
    columns: List<PivotDimension>,
    onConfigChange: (ExploreConfig) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ConfigSection("Rows") {
                config.rowDimensions.forEach { dimension ->
                    ConfigRow(label = dimension.label) {
                        TextButton(
                            onClick = {
                                onConfigChange(config.copy(rowDimensions = config.rowDimensions - dimension))
                            },
                        ) { Text("Remove") }
                    }
                }
                SelectMenuButton(
                    label = "Add row field",
                    items = columns.filterNot { candidate ->
                        config.rowDimensions.any { it.column == candidate.column }
                    },
                    itemLabel = { it.label },
                    onSelect = { onConfigChange(config.copy(rowDimensions = config.rowDimensions + it)) },
                )
            }

            ConfigSection("Columns") {
                ConfigRow(label = config.columnDimension?.label ?: "No column field") {
                    SelectMenuButton(
                        label = "Choose",
                        items = listOf(null) + columns,
                        itemLabel = { it?.label ?: "None" },
                        onSelect = { onConfigChange(config.copy(columnDimension = it)) },
                    )
                }
            }

            ConfigSection("Measures") {
                config.measures.forEach { measure ->
                    ConfigRow(label = measure.label) {
                        TextButton(
                            enabled = config.measures.size > 1,
                            onClick = {
                                onConfigChange(config.copy(measures = config.measures - measure))
                            },
                        ) { Text("Remove") }
                    }
                }
                SelectMenuButton(
                    label = "Add measure",
                    items = buildMeasureOptions(columns, config.measures),
                    itemLabel = { it.label },
                    onSelect = { onConfigChange(config.copy(measures = config.measures + it)) },
                    minWidth = 240.dp,
                )
            }

            ConfigSection("Totals") {
                ToggleRow(
                    label = "Row totals",
                    checked = config.showRowTotals,
                    onCheckedChange = { onConfigChange(config.copy(showRowTotals = it)) },
                )
                ToggleRow(
                    label = "Grand total row",
                    checked = config.showColumnTotals,
                    onCheckedChange = { onConfigChange(config.copy(showColumnTotals = it)) },
                )
            }

            ConfigSection("Sort") {
                ConfigRow(label = sortLabel(config)) {
                    SelectMenuButton(
                        label = "Sort by",
                        items = buildSortOptions(config),
                        itemLabel = { it.first },
                        onSelect = { onConfigChange(config.copy(sort = it.second)) },
                        minWidth = 220.dp,
                    )
                }
                config.sort?.let { currentSort ->
                    SecondaryButton(
                        onClick = {
                            onConfigChange(
                                config.copy(
                                    sort = currentSort.copy(
                                        dir = if (currentSort.dir == SortDir.Asc) SortDir.Desc else SortDir.Asc,
                                    ),
                                ),
                            )
                        },
                    ) {
                        Text(if (currentSort.dir == SortDir.Asc) "Ascending" else "Descending")
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfigSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
        )
        content()
    }
}

@Composable
private fun ConfigRow(
    label: String,
    action: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 36.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        action()
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun <T> SelectMenuButton(
    label: String,
    items: List<T>,
    itemLabel: (T) -> String,
    onSelect: (T) -> Unit,
    minWidth: androidx.compose.ui.unit.Dp = 176.dp,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        SecondaryButton(onClick = { expanded = true }, enabled = items.isNotEmpty()) {
            Text(label)
        }
        SafeDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            minWidth = minWidth,
        ) {
            items.forEach { item ->
                MenuActionRow(
                    text = itemLabel(item),
                    onClick = {
                        expanded = false
                        onSelect(item)
                    },
                )
            }
        }
    }
}

private fun buildMeasureOptions(
    columns: List<PivotDimension>,
    existing: List<PivotMeasure>,
): List<PivotMeasure> {
    val count = PivotMeasure.countRows()
    val options = mutableListOf(count)
    for (column in columns) {
        options.add(PivotMeasure("distinct_${column.column}", MeasureFn.CountDistinct, column.column, "Distinct ${column.label}"))
        options.add(PivotMeasure("sum_${column.column}", MeasureFn.Sum, column.column, "Sum ${column.label}"))
        options.add(PivotMeasure("avg_${column.column}", MeasureFn.Avg, column.column, "Avg ${column.label}"))
        options.add(PivotMeasure("min_${column.column}", MeasureFn.Min, column.column, "Min ${column.label}"))
        options.add(PivotMeasure("max_${column.column}", MeasureFn.Max, column.column, "Max ${column.label}"))
    }
    return options.filterNot { option ->
        existing.any { it.alias == option.alias && it.fn == option.fn && it.sourceColumn == option.sourceColumn }
    }
}

private fun buildSortOptions(config: ExploreConfig): List<Pair<String, ExploreSort?>> {
    val options = mutableListOf<Pair<String, ExploreSort?>>("None" to null)
    config.rowDimensions.forEach { dimension ->
        options.add(dimension.label to ExploreSort(ExploreSortTarget.Dimension(dimension.column)))
    }
    config.measures.forEach { measure ->
        options.add(measure.label to ExploreSort(ExploreSortTarget.Measure(measure.alias), SortDir.Desc))
    }
    return options
}

private fun sortLabel(config: ExploreConfig): String {
    val sort = config.sort ?: return "No sort"
    val target = when (val sortTarget = sort.target) {
        is ExploreSortTarget.Dimension -> config.rowDimensions.find { it.column == sortTarget.column }?.label
        is ExploreSortTarget.Measure -> config.measures.find { it.alias == sortTarget.alias }?.label
    } ?: "No sort"
    val dir = if (sort.dir == SortDir.Asc) "ascending" else "descending"
    return "$target, $dir"
}

private fun chooseCsvFile(connectionLabel: String): java.nio.file.Path? {
    val safeName = connectionLabel.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "explore" }
    val chooser = JFileChooser().apply {
        selectedFile = File("explore-$safeName.csv")
        dialogTitle = "Export Explore CSV"
    }
    val result = chooser.showSaveDialog(null)
    if (result != JFileChooser.APPROVE_OPTION) return null
    val file = chooser.selectedFile
    return if (file.extension.equals("csv", ignoreCase = true)) file.toPath() else File("${file.path}.csv").toPath()
}
