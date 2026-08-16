package com.safedb.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.safedb.AppRoute
import com.safedb.AppState
import com.safedb.ui.theme.CardShape
import com.safedb.ui.theme.InputShape
import com.safedb.viewmodel.AppViewModel

private data class PaletteCommand(
    val id: String,
    val label: String,
    val hint: String,
    val icon: ImageVector,
    val action: () -> Unit,
)

@Composable
fun CommandPalette(
    open: Boolean,
    onDismiss: () -> Unit,
    appState: AppState,
    viewModel: AppViewModel,
) {
    if (!open) return

    var search by remember { mutableStateOf("") }
    var selectedIndex by remember { mutableIntStateOf(0) }
    val activeConnectionId by appState.activeConnectionId.collectAsState()
    val currentRoute by appState.route.collectAsState()
    val connections by viewModel.connections.connections.collectAsState()
    val settings by viewModel.settings.settings.collectAsState()

    val commands = buildList {
        add(
            PaletteCommand("nav-home", "Go to Home", "Home page", Icons.Filled.Home) {
                appState.navigate(AppRoute.Home)
                onDismiss()
            }
        )
        add(
            PaletteCommand(
                "nav-connections",
                "Go to Connections",
                "Manage connections",
                Icons.Filled.Link,
            ) {
                appState.navigate(AppRoute.Connections)
                onDismiss()
            }
        )
        add(
            PaletteCommand(
                "nav-builder",
                "Go to Query Builder",
                "Build queries",
                Icons.Filled.Build,
            ) {
                appState.navigate(AppRoute.Builder)
                onDismiss()
            }
        )
        add(
            PaletteCommand("nav-sql", "Go to SQL", "Write SELECT queries", Icons.Filled.Code) {
                appState.navigate(AppRoute.Sql)
                onDismiss()
            }
        )
        add(
            PaletteCommand("nav-map", "Go to Map", "Explore database schema", Icons.Filled.Hub) {
                appState.navigate(AppRoute.Map)
                onDismiss()
            }
        )
        add(
            PaletteCommand("nav-history", "Go to History", "Recent queries", Icons.Filled.History) {
                appState.navigate(AppRoute.History)
                onDismiss()
            }
        )
        add(
            PaletteCommand(
                "lock-credentials",
                "Lock credentials",
                "Clear unlocked passwords",
                Icons.Filled.Lock,
            ) {
                viewModel.lockCredentials()
                onDismiss()
            }
        )
        val runConnectionId = activeConnectionId
        val schemaMatchesConnection =
            runConnectionId != null &&
                viewModel.schema.schema != null &&
                viewModel.schema.loadedConnectionId == runConnectionId
        // These act on the builder canvas. On the SQL screen they would run or clear a query the
        // user cannot see — on a read-only tool, sending the wrong statement to the database.
        val builderCommandsApply = currentRoute != AppRoute.Sql
        if (
            builderCommandsApply &&
                runConnectionId != null &&
                schemaMatchesConnection &&
                viewModel.query.canRun
        ) {
            add(
                PaletteCommand(
                    "run-query",
                    "Run Query",
                    "Execute current query",
                    Icons.Filled.PlayArrow,
                ) {
                    if (
                        viewModel.schema.schema != null &&
                            viewModel.schema.loadedConnectionId == runConnectionId
                    ) {
                        viewModel.query.run(runConnectionId)
                        appState.navigate(AppRoute.Builder)
                        onDismiss()
                    }
                }
            )
        }
        if (builderCommandsApply && viewModel.query.canvasTables.isNotEmpty()) {
            add(
                PaletteCommand(
                    "clear-canvas",
                    "Clear Canvas",
                    "Remove all tables",
                    Icons.Filled.Delete,
                ) {
                    viewModel.query.clear()
                    onDismiss()
                }
            )
        }
        for ((_, id, name, dialect, _, _, database, _, _, _) in connections) {
            add(
                PaletteCommand(
                    id = "conn-$id",
                    label = "Explore: $name",
                    hint = "$dialect · $database",
                    icon = Icons.Filled.Link,
                ) {
                    appState.setActiveConnection(
                        id,
                        com.safedb.resolveConnectionSchemaSelection(id, settings),
                    )
                    appState.navigate(AppRoute.Builder)
                    onDismiss()
                }
            )
        }
    }

    val filtered =
        remember(search, commands) {
            val query = search.trim().lowercase()
            if (query.isEmpty()) {
                commands
            } else {
                commands.filter { cmd ->
                    cmd.label.lowercase().contains(query) || cmd.hint.lowercase().contains(query)
                }
            }
        }

    LaunchedEffect(filtered.size) { selectedIndex = 0 }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier =
                Modifier.widthIn(max = 560.dp).fillMaxWidth().onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.Escape -> {
                            onDismiss()
                            true
                        }
                        Key.Enter -> {
                            filtered.getOrNull(selectedIndex)?.action?.invoke()
                            true
                        }
                        Key.DirectionDown -> {
                            if (filtered.isNotEmpty()) {
                                selectedIndex = (selectedIndex + 1).coerceAtMost(filtered.lastIndex)
                            }
                            true
                        }
                        Key.DirectionUp -> {
                            if (filtered.isNotEmpty()) {
                                selectedIndex = (selectedIndex - 1).coerceAtLeast(0)
                            }
                            true
                        }
                        else -> false
                    }
                },
            shape = CardShape,
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 12.dp,
            tonalElevation = 0.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            Column {
                Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                    TextField(
                        value = search,
                        onValueChange = { search = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = {
                            Text("Type a command...", style = MaterialTheme.typography.bodyMedium)
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Filled.Search,
                                contentDescription = null,
                                tint =
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                modifier = Modifier.size(18.dp),
                            )
                        },
                        singleLine = true,
                        shape = InputShape,
                        textStyle = MaterialTheme.typography.bodyMedium,
                        colors =
                            TextFieldDefaults.colors(
                                focusedContainerColor =
                                    MaterialTheme.colorScheme.surfaceContainerLow,
                                unfocusedContainerColor =
                                    MaterialTheme.colorScheme.surfaceContainerLow,
                                disabledContainerColor =
                                    MaterialTheme.colorScheme.surfaceContainerLow,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                disabledIndicatorColor = Color.Transparent,
                            ),
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline)

                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp).padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (filtered.isEmpty()) {
                        item {
                            Text(
                                "No commands found",
                                style = MaterialTheme.typography.bodyMedium,
                                color =
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                modifier =
                                    Modifier.fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 28.dp),
                            )
                        }
                    } else {
                        itemsIndexed(filtered, key = { _, cmd -> cmd.id }) { index, cmd ->
                            MenuActionRow(
                                text = cmd.label,
                                supportingText = cmd.hint,
                                selected = index == selectedIndex,
                                onClick = { cmd.action() },
                                leading = { CommandIconBox(icon = cmd.icon) },
                            )
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline)

                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ShortcutHint("↑↓", "navigate")
                    ShortcutHint("↵", "select")
                    ShortcutHint("esc", "close")
                }
            }
        }
    }
}

@Composable
private fun CommandIconBox(icon: ImageVector) {
    Surface(
        modifier = Modifier.size(28.dp),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 0.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun ShortcutHint(key: String, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Kbd(key)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
        )
    }
}
