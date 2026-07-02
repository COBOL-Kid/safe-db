package com.safedb.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.safedb.model.TableInfo
import com.safedb.model.qualifiedName
import com.safedb.viewmodel.SchemaViewModel
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Search

@Composable
fun SchemaBrowser(
    schemaViewModel: SchemaViewModel,
    onAddTable: ((TableInfo) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(setOf<String>()) }

    Column(modifier = modifier.fillMaxSize()) {
        OutlinedTextField(
            value = schemaViewModel.search,
            onValueChange = { schemaViewModel.search = it },
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            placeholder = { Text("Search tables…") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
        )

        when {
            schemaViewModel.loading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Text(
                            "Loading schema…",
                            modifier = Modifier.padding(top = 12.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            schemaViewModel.error != null -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "Failed to load schema",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            schemaViewModel.error ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
            schemaViewModel.filteredTables.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (schemaViewModel.search.isNotBlank()) {
                            "No tables match your search."
                        } else {
                            "No tables found."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            else -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    for (table in schemaViewModel.filteredTables) {
                        val key = table.qualifiedName()
                        val isOpen = expanded.contains(key)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(
                                onClick = {
                                    expanded = if (isOpen) expanded - key else expanded + key
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(
                                    imageVector = if (isOpen) {
                                        Icons.Default.KeyboardArrowDown
                                    } else {
                                        Icons.Default.KeyboardArrowRight
                                    },
                                    contentDescription = null,
                                )
                                Text(table.name, modifier = Modifier.weight(1f))
                                Text(
                                    "${table.columns.size}",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                            if (onAddTable != null) {
                                IconButton(onClick = { onAddTable(table) }) {
                                    Icon(Icons.Default.Add, contentDescription = "Add to canvas")
                                }
                            }
                        }
                        if (isOpen) {
                            Column(modifier = Modifier.padding(start = 32.dp, bottom = 8.dp)) {
                                for (col in table.columns) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Text(col.name, style = MaterialTheme.typography.labelMedium)
                                        Text(
                                            col.dataType,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        if (col.isIndexed) {
                                            Text(
                                                "indexed",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                        if (col.nullable) {
                                            Text(
                                                "null",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.outline,
                                            )
                                        }
                                    }
                                }
                                if (table.indexes.isNotEmpty()) {
                                    Text(
                                        "INDEXES",
                                        style = com.safedb.ui.theme.LabelMicro,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                                    )
                                    for (idx in table.indexes) {
                                        val (badge, badgeColor) = when {
                                            idx.isPrimary -> "PK" to MaterialTheme.colorScheme.tertiary
                                            idx.isUnique -> "UQ" to com.safedb.ui.theme.SafeDbTheme.colors.uq
                                            else -> "IDX" to MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                        Row(
                                            modifier = Modifier.padding(vertical = 2.dp),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            Text(
                                                badge,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = badgeColor,
                                                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                                            )
                                            Text(
                                                "${idx.name} (${idx.columns.joinToString(", ")})",
                                                style = MaterialTheme.typography.labelSmall,
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
    }
}
