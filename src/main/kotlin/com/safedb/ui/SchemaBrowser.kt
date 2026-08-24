package com.safedb.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.safedb.model.ConnectionDef
import com.safedb.model.TableInfo
import com.safedb.model.qualifiedName
import com.safedb.ui.components.MenuActionRow
import com.safedb.ui.components.SafeDropdownMenu
import com.safedb.ui.components.ScrollableMenuColumn
import com.safedb.ui.components.SecondaryButton
import com.safedb.ui.theme.DataMono
import com.safedb.ui.theme.InputShape
import com.safedb.ui.theme.SafeDbTheme
import com.safedb.viewmodel.SchemaViewModel

@Composable
fun SchemaBrowser(
    schemaViewModel: SchemaViewModel,
    connection: ConnectionDef?,
    connections: List<ConnectionDef>,
    onConnectionSelected: (ConnectionDef) -> Unit,
    onAddTable: ((TableInfo) -> Unit)? = null,
    onSchemaSelected: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(setOf<String>()) }
    var schemaMenuOpen by remember { mutableStateOf(false) }
    var connectionMenuOpen by remember { mutableStateOf(false) }
    val connectionChevronRotation by
        animateFloatAsState(
            targetValue = if (connectionMenuOpen) 90f else 0f,
            animationSpec = tween(durationMillis = 160),
            label = "connectionChevronRotation",
        )

    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp)
        ) {
            Row(
                modifier = Modifier.padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Schema",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box {
                    Row(
                        modifier =
                            Modifier.clickable(
                                    enabled = connections.isNotEmpty(),
                                    role = Role.Button,
                                    onClick = { connectionMenuOpen = true },
                                )
                                .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Connection",
                            style = MaterialTheme.typography.labelMedium,
                            color = SafeDbTheme.colors.actionPrimary,
                        )
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription =
                                if (connectionMenuOpen) "Hide connections" else "Choose connection",
                            tint = SafeDbTheme.colors.actionPrimary,
                            modifier =
                                Modifier.size(16.dp).graphicsLayer {
                                    rotationZ = connectionChevronRotation
                                },
                        )
                    }
                    SafeDropdownMenu(
                        expanded = connectionMenuOpen,
                        onDismissRequest = { connectionMenuOpen = false },
                        modifier = Modifier.width(240.dp),
                    ) {
                        ScrollableMenuColumn {
                            connections.forEach { option ->
                                MenuActionRow(
                                    text = option.name,
                                    supportingText = "${option.dialect} · ${option.database}",
                                    selected = option.id == connection?.id,
                                    onClick = {
                                        connectionMenuOpen = false
                                        onConnectionSelected(option)
                                    },
                                )
                            }
                        }
                    }
                }
            }
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                SecondaryButton(
                    onClick = { schemaMenuOpen = true },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = schemaViewModel.schemaOptions.isNotEmpty(),
                ) {
                    Text(
                        schemaViewModel.selectedSchema ?: "Select schema",
                        modifier = Modifier.weight(1f),
                    )
                    Icon(Icons.Default.ExpandMore, contentDescription = null)
                }
                SafeDropdownMenu(
                    expanded = schemaMenuOpen,
                    onDismissRequest = { schemaMenuOpen = false },
                    modifier = Modifier.width(maxWidth),
                ) {
                    ScrollableMenuColumn {
                        schemaViewModel.schemaOptions.forEach { schema ->
                            MenuActionRow(
                                text = schema,
                                selected = schemaViewModel.selectedSchema == schema,
                                onClick = {
                                    schemaMenuOpen = false
                                    schemaViewModel.selectSchema(schema)
                                    onSchemaSelected(schema)
                                },
                            )
                        }
                    }
                }
            }
            schemaViewModel.preferredSchemaWarning?.let { warning ->
                Text(
                    warning,
                    style = MaterialTheme.typography.labelSmall,
                    color = SafeDbTheme.colors.warning,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }

        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .padding(12.dp)
                    .border(1.dp, MaterialTheme.colorScheme.outline, InputShape)
                    .background(
                        MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.5f),
                        InputShape,
                    )
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Default.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            BasicTextField(
                value = schemaViewModel.search,
                onValueChange = { schemaViewModel.search = it },
                enabled = schemaViewModel.selectedSchema != null && !schemaViewModel.loading,
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle =
                    MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                decorationBox = { innerTextField ->
                    Box {
                        if (schemaViewModel.search.isEmpty()) {
                            Text(
                                "Search tables…",
                                style = MaterialTheme.typography.bodySmall,
                                color =
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            )
                        }
                        innerTextField()
                    }
                },
            )
        }

        when {
            schemaViewModel.loading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
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
            connection == null -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "Choose a connection to view schemas.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            schemaViewModel.selectedSchema == null -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (schemaViewModel.schemaOptions.isEmpty()) {
                            "No schemas containing visible tables were found."
                        } else {
                            "Select a schema to view tables."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            schemaViewModel.filteredTables.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
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
                    modifier =
                        Modifier.fillMaxSize()
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
                                    imageVector =
                                        if (isOpen) {
                                            Icons.Default.KeyboardArrowDown
                                        } else {
                                            Icons.AutoMirrored.Filled.KeyboardArrowRight
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
                                for ((name, dataType, nullable, isIndexed, _, _) in table.columns) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Text(name, style = DataMono)
                                        Text(
                                            dataType,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        if (isIndexed) {
                                            Text(
                                                "indexed",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                        if (nullable) {
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
                                    for ((name, columns, _, _, _, isUnique, isPrimary, _, _, _) in
                                        table.indexes) {
                                        val (badge, badgeColor) =
                                            when {
                                                isPrimary ->
                                                    "PK" to MaterialTheme.colorScheme.tertiary
                                                isUnique -> "UQ" to SafeDbTheme.colors.uq
                                                else ->
                                                    "IDX" to
                                                        MaterialTheme.colorScheme.onSurfaceVariant
                                            }
                                        Row(
                                            modifier = Modifier.padding(vertical = 2.dp),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            Text(
                                                badge,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = badgeColor,
                                                fontWeight =
                                                    androidx.compose.ui.text.font.FontWeight
                                                        .SemiBold,
                                            )
                                            Text(
                                                "$name (${columns.joinToString(", ")})",
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
