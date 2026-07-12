package com.safedb.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.safedb.explore.PivotFilter
import com.safedb.ui.components.MenuActionRow
import com.safedb.ui.components.SafeDropdownMenu
import com.safedb.ui.theme.SafeDbTheme
import com.safedb.viewmodel.MemberOption

@Composable
internal fun ExploreFilterStrip(
    filters: List<PivotFilter.Members>,
    optionsFor: (String) -> List<MemberOption>,
    onSelectionChange: (filterId: String, keys: Set<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (filters.isEmpty()) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        filters.forEach { filter ->
            SlicerControl(
                filter = filter,
                options = remember(filter.column) { optionsFor(filter.column) },
                onSelectionChange = { onSelectionChange(filter.id, it) },
            )
        }
    }
}

@Composable
private fun SlicerControl(
    filter: PivotFilter.Members,
    options: List<MemberOption>,
    onSelectionChange: (Set<String>) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val selected = filter.includedKeys
    val summary = if (selected.isEmpty() || selected.size == options.size) {
        "All (${options.size})"
    } else {
        "${selected.size} of ${options.size} selected"
    }
    val visible = options.filter { query.isBlank() || it.label.contains(query, ignoreCase = true) }

    Box {
        Surface(
            shape = RoundedCornerShape(9.dp),
            color = if (selected.isEmpty()) MaterialTheme.colorScheme.surface else SafeDbTheme.colors.accentContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            onClick = { expanded = true },
        ) {
            Column(modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp)) {
                Text(filter.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(summary, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        SafeDropdownMenu(
            expanded = expanded,
            onDismissRequest = {
                expanded = false
                query = ""
            },
            minWidth = 280.dp,
        ) {
            Column(modifier = Modifier.widthIn(min = 280.dp).heightIn(max = 440.dp)) {
                ExploreSlicerSearch(query, onQueryChange = { query = it })
                MenuActionRow(
                    text = if (selected.isEmpty()) "Clear selection" else "Select all",
                    supportingText = "${options.size} sample values",
                    onClick = { onSelectionChange(emptySet()) },
                )
                visible.forEach { option ->
                    val checked = selected.isEmpty() || option.key in selected
                    MenuActionRow(
                        text = option.label,
                        supportingText = "${option.count} row${if (option.count == 1) "" else "s"}",
                        leading = { Checkbox(checked = checked, onCheckedChange = null) },
                        onClick = {
                            val baseline = if (selected.isEmpty()) options.map { it.key }.toSet() else selected
                            val next = if (option.key in baseline) baseline - option.key else baseline + option.key
                            if (next.isNotEmpty()) {
                                onSelectionChange(if (next.size == options.size) emptySet() else next)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ExploreSlicerSearch(query: String, onQueryChange: (String) -> Unit) {
    // Reuse the same quiet search treatment as the field picker without exposing
    // its private implementation.
    androidx.compose.material3.OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text("Search values") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
    )
}
