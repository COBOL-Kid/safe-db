package com.safedb.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.safedb.AppRoute
import com.safedb.AppState
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
    val connections by viewModel.connections.connections.collectAsState()

    val commands =
        buildList {
            add(
                PaletteCommand("nav-home", "Go to Home", "Home page", Icons.Filled.Home) {
                    appState.navigate(AppRoute.Home)
                    onDismiss()
                },
            )
            add(
                PaletteCommand("nav-connections", "Go to Connections", "Manage connections", Icons.Filled.Link) {
                    appState.navigate(AppRoute.Connections)
                    onDismiss()
                },
            )
            add(
                PaletteCommand("nav-builder", "Go to Query Builder", "Build queries", Icons.Filled.Build) {
                    appState.navigate(AppRoute.Builder)
                    onDismiss()
                },
            )
            add(
                PaletteCommand("nav-history", "Go to History", "Recent queries", Icons.Filled.History) {
                    appState.navigate(AppRoute.History)
                    onDismiss()
                },
            )
            add(
                PaletteCommand("lock-credentials", "Lock credentials", "Clear unlocked passwords", Icons.Filled.Lock) {
                    viewModel.lockCredentials()
                    onDismiss()
                },
            )
            val runConnectionId = activeConnectionId
            if (runConnectionId != null && viewModel.query.canRun) {
                add(
                    PaletteCommand("run-query", "Run Query", "Execute current query", Icons.Filled.PlayArrow) {
                        viewModel.query.run(runConnectionId)
                        appState.navigate(AppRoute.Builder)
                        onDismiss()
                    },
                )
            }
            if (viewModel.query.canvasTables.isNotEmpty()) {
                add(
                    PaletteCommand("clear-canvas", "Clear Canvas", "Remove all tables", Icons.Filled.Delete) {
                        viewModel.query.clear()
                        onDismiss()
                    },
                )
            }
            for (connection in connections) {
                add(
                    PaletteCommand(
                        id = "conn-${connection.id}",
                        label = "Explore: ${connection.name}",
                        hint = "${connection.dialect} · ${connection.database}",
                        icon = Icons.Filled.Link,
                    ) {
                        appState.setActiveConnection(connection.id)
                        appState.navigate(AppRoute.Builder)
                        onDismiss()
                    },
                )
            }
        }

    val filtered = remember(search, commands) {
        val query = search.trim().lowercase()
        if (query.isEmpty()) {
            commands
        } else {
            commands.filter { cmd ->
                cmd.label.lowercase().contains(query) || cmd.hint.lowercase().contains(query)
            }
        }
    }

    LaunchedEffect(filtered.size) {
        selectedIndex = 0
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .onPreviewKeyEvent { event ->
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
                                selectedIndex = (selectedIndex + 1) % filtered.size
                            }
                            true
                        }
                        Key.DirectionUp -> {
                            if (filtered.isNotEmpty()) {
                                selectedIndex = if (selectedIndex == 0) filtered.lastIndex else selectedIndex - 1
                            }
                            true
                        }
                        else -> false
                    }
                },
            shape = RoundedCornerShape(12.dp),
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search commands…") },
                    singleLine = true,
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    itemsIndexed(filtered, key = { _, cmd -> cmd.id }) { index, cmd ->
                        val selected = index == selectedIndex
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (selected) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surface
                                    },
                                    RoundedCornerShape(8.dp),
                                )
                                .clickable { cmd.action() }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(cmd.icon, contentDescription = null)
                            Column {
                                Text(cmd.label, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    cmd.hint,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
