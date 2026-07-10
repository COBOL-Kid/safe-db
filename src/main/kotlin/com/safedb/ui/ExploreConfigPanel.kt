package com.safedb.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.safedb.explore.ExploreConfig
import com.safedb.explore.ExploreSortTarget
import com.safedb.explore.MeasureFn
import com.safedb.explore.PivotMeasure
import com.safedb.explore.SortDir
import com.safedb.ui.components.MenuActionRow
import com.safedb.ui.components.MenuSectionLabel
import com.safedb.ui.components.SafeDropdownMenu
import com.safedb.ui.theme.SafeDbTheme

@Composable
internal fun ExploreConfigPanel(
    config: ExploreConfig,
    fields: List<ExploreFieldOption>,
    onConfigChange: (ExploreConfig) -> Unit,
    onReset: () -> Unit,
    resetEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    var optionsExpanded by remember { mutableStateOf(false) }

    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Build view",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "Choose how to group and summarize the sample.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onReset, enabled = resetEnabled) { Text("Reset") }
            }

            FieldWell(title = "Group rows by") {
                config.rowDimensions.forEachIndexed { index, dimension ->
                    val field = fields.firstOrNull { it.column == dimension.column }
                    FieldChip(
                        label = dimension.label,
                        supportingText = field?.dataType,
                        onRemove = {
                            onConfigChange(config.copy(rowDimensions = config.rowDimensions - dimension))
                        },
                        onMoveUp = if (index > 0) {
                            { onConfigChange(config.copy(rowDimensions = moveDimension(config.rowDimensions, dimension, -1))) }
                        } else {
                            null
                        },
                        onMoveDown = if (index < config.rowDimensions.lastIndex) {
                            { onConfigChange(config.copy(rowDimensions = moveDimension(config.rowDimensions, dimension, 1))) }
                        } else {
                            null
                        },
                    )
                }
                FieldPickerButton(
                    label = "Add field",
                    fields = fields.filterNot { candidate ->
                        config.rowDimensions.any { it.column == candidate.column }
                    },
                    onSelect = { field ->
                        onConfigChange(config.copy(rowDimensions = config.rowDimensions + field.asDimension()))
                    },
                )
            }

            FieldWell(title = "Split into columns") {
                val columnDimension = config.columnDimension
                columnDimension?.let { dimension ->
                    val field = fields.firstOrNull { it.column == dimension.column }
                    FieldChip(
                        label = dimension.label,
                        supportingText = field?.dataType,
                        onRemove = { onConfigChange(config.copy(columnDimension = null)) },
                    )
                }
                if (columnDimension == null) {
                    FieldPickerButton(
                        label = "Add field",
                        fields = fields,
                        onSelect = { onConfigChange(config.copy(columnDimension = it.asDimension())) },
                    )
                } else {
                    FieldPickerButton(
                        label = "Replace field",
                        fields = fields.filterNot { it.column == columnDimension.column },
                        onSelect = { onConfigChange(config.copy(columnDimension = it.asDimension())) },
                    )
                }
            }

            FieldWell(title = "Values") {
                config.measures.forEach { measure ->
                    FieldChip(
                        label = measure.label,
                        supportingText = measureSupportingText(measure, fields),
                        onRemove = if (config.measures.size > 1) {
                            { onConfigChange(config.copy(measures = config.measures - measure)) }
                        } else {
                            null
                        },
                    )
                }
                MeasurePickerButton(
                    fields = fields,
                    existing = config.measures,
                    onSelect = { onConfigChange(config.copy(measures = config.measures + it)) },
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { optionsExpanded = !optionsExpanded }
                    .pointerHoverIcon(PointerIcon.Hand)
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Options", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        optionsSummary(config),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    if (optionsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (optionsExpanded) "Hide options" else "Show options",
                )
            }

            if (optionsExpanded) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (config.columnDimension != null) {
                        ExploreToggleRow(
                            label = "Show row totals",
                            checked = config.showRowTotals,
                            onCheckedChange = { onConfigChange(config.copy(showRowTotals = it)) },
                        )
                    }
                    ExploreToggleRow(
                        label = "Show grand total row",
                        checked = config.showColumnTotals,
                        onCheckedChange = { onConfigChange(config.copy(showColumnTotals = it)) },
                    )
                    config.sort?.let { sort ->
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 6.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Sort", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                                Text(
                                    sortSummary(config),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { onConfigChange(config.copy(sort = null)) }) {
                                Text("Clear")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FieldWell(
    title: String,
    content: @Composable () -> Unit,
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
private fun FieldChip(
    label: String,
    supportingText: String?,
    onRemove: (() -> Unit)?,
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(9.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            modifier = Modifier.padding(start = 11.dp, end = 5.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                supportingText?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            if (onMoveUp != null) {
                CompactIconAction(Icons.Default.KeyboardArrowUp, "Move up", onMoveUp)
            }
            if (onMoveDown != null) {
                CompactIconAction(Icons.Default.KeyboardArrowDown, "Move down", onMoveDown)
            }
            if (onRemove != null) {
                CompactIconAction(Icons.Default.Close, "Remove $label", onRemove)
            }
        }
    }
}

@Composable
private fun CompactIconAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = description,
            modifier = Modifier.size(17.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FieldPickerButton(
    label: String,
    fields: List<ExploreFieldOption>,
    onSelect: (ExploreFieldOption) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val filtered = fields.filter { field ->
        query.isBlank() || field.label.contains(query, ignoreCase = true) ||
            field.dataType.contains(query, ignoreCase = true)
    }

    Box {
        TextButton(onClick = { expanded = true }, enabled = fields.isNotEmpty()) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(label, modifier = Modifier.padding(start = 4.dp))
        }
        SafeDropdownMenu(
            expanded = expanded,
            onDismissRequest = {
                expanded = false
                query = ""
            },
            minWidth = 280.dp,
        ) {
            Column(modifier = Modifier.widthIn(min = 280.dp).heightIn(max = 420.dp)) {
                ExploreSearchField(query = query, onQueryChange = { query = it })
                if (filtered.isEmpty()) {
                    EmptyPickerMessage("No matching fields")
                } else {
                    filtered.forEach { field ->
                        MenuActionRow(
                            text = field.label,
                            supportingText = field.dataType,
                            onClick = {
                                expanded = false
                                query = ""
                                onSelect(field)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MeasurePickerButton(
    fields: List<ExploreFieldOption>,
    existing: List<PivotMeasure>,
    onSelect: (PivotMeasure) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var selectedField by remember { mutableStateOf<ExploreFieldOption?>(null) }
    val countRowsAvailable = existing.none { it.fn == MeasureFn.Count && it.sourceColumn == null }
    val availableFields = fields.filter { field ->
        availableMeasureFunctions(field).any { fn ->
            val candidate = measureFor(field, fn)
            existing.none { it.alias == candidate.alias }
        }
    }
    val filteredFields = availableFields.filter { field ->
        query.isBlank() || field.label.contains(query, ignoreCase = true) ||
            field.dataType.contains(query, ignoreCase = true)
    }

    Box {
        TextButton(onClick = { expanded = true }, enabled = countRowsAvailable || availableFields.isNotEmpty()) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
            Text("Add value", modifier = Modifier.padding(start = 4.dp))
        }
        SafeDropdownMenu(
            expanded = expanded,
            onDismissRequest = {
                expanded = false
                query = ""
                selectedField = null
            },
            minWidth = 292.dp,
        ) {
            Column(modifier = Modifier.widthIn(min = 292.dp).heightIn(max = 440.dp)) {
                val field = selectedField
                if (field == null) {
                    if (countRowsAvailable) {
                        MenuActionRow(
                            text = "Count rows",
                            supportingText = "Number of records in each group",
                            onClick = {
                                expanded = false
                                onSelect(PivotMeasure.countRows())
                            },
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 4.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                    ExploreSearchField(query = query, onQueryChange = { query = it })
                    MenuSectionLabel("Choose a field")
                    if (filteredFields.isEmpty()) {
                        EmptyPickerMessage("No more values available")
                    } else {
                        filteredFields.forEach { option ->
                            MenuActionRow(
                                text = option.label,
                                supportingText = option.dataType,
                                onClick = {
                                    selectedField = option
                                    query = ""
                                },
                            )
                        }
                    }
                } else {
                    MenuActionRow(
                        text = "All fields",
                        leading = {
                            Icon(Icons.Default.ChevronLeft, contentDescription = null, modifier = Modifier.size(18.dp))
                        },
                        onClick = { selectedField = null },
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                    MenuSectionLabel(field.label)
                    availableMeasureFunctions(field).forEach { function ->
                        val candidate = measureFor(field, function)
                        if (existing.none { it.alias == candidate.alias }) {
                            MenuActionRow(
                                text = measureFunctionLabel(function),
                                supportingText = candidate.label,
                                onClick = {
                                    expanded = false
                                    selectedField = null
                                    onSelect(candidate)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExploreSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Search,
                contentDescription = null,
                modifier = Modifier.size(17.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f).padding(start = 7.dp),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(SafeDbTheme.colors.actionPrimary),
                decorationBox = { inner ->
                    if (query.isEmpty()) {
                        Text(
                            "Search fields",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    inner()
                },
            )
        }
    }
}

@Composable
private fun EmptyPickerMessage(text: String) {
    Text(
        text,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ExploreToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

private fun measureSupportingText(
    measure: PivotMeasure,
    fields: List<ExploreFieldOption>,
): String = if (measure.fn == MeasureFn.Count && measure.sourceColumn == null) {
    "All rows"
} else {
    fields.firstOrNull { it.column == measure.sourceColumn }?.dataType ?: measure.fn.name
}

private fun optionsSummary(config: ExploreConfig): String {
    val totals = when {
        config.columnDimension != null && config.showRowTotals && config.showColumnTotals -> "Row and grand totals"
        config.columnDimension != null && config.showRowTotals -> "Row totals"
        config.showColumnTotals -> "Grand total"
        else -> "Totals hidden"
    }
    return if (config.sort == null) totals else "$totals · ${sortSummary(config)}"
}

private fun sortSummary(config: ExploreConfig): String {
    val sort = config.sort ?: return "Not sorted"
    val target = when (val sortTarget = sort.target) {
        is ExploreSortTarget.Dimension -> config.rowDimensions.firstOrNull { it.column == sortTarget.column }?.label
        is ExploreSortTarget.Measure -> config.measures.firstOrNull { it.alias == sortTarget.alias }?.label
    } ?: "Unknown field"
    return "$target, ${if (sort.dir == SortDir.Asc) "ascending" else "descending"}"
}
