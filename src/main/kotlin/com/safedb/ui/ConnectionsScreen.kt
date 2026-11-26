package com.safedb.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.safedb.ConnectionFormRequest
import com.safedb.model.ConnectionDef
import com.safedb.model.displayName
import com.safedb.ui.components.AppCard
import com.safedb.ui.components.ConfirmDialog
import com.safedb.ui.components.DeleteIconButton
import com.safedb.ui.components.EmptyState
import com.safedb.ui.components.MessageBanner
import com.safedb.ui.components.PrimaryButton
import com.safedb.ui.components.SecondaryButton
import com.safedb.ui.components.StatusChip
import com.safedb.ui.components.StatusChipKind
import com.safedb.ui.theme.DataMono
import com.safedb.ui.theme.LabelMicro
import com.safedb.ui.theme.SafeDbTheme
import com.safedb.ui.theme.ScreenHeaderHorizontalPadding
import com.safedb.ui.theme.TitleHeaderVerticalPadding
import com.safedb.viewmodel.ConnectionsViewModel

internal sealed interface ConnectionFormMode {
    data object New : ConnectionFormMode

    data class Edit(val connection: ConnectionDef) : ConnectionFormMode
}

@Composable
fun ConnectionsScreen(
    viewModel: ConnectionsViewModel,
    onDeleted: (String) -> Unit,
    onConnectionChanged: (String) -> Unit,
    onSelectConnection: (ConnectionDef) -> Unit = {},
    connectionFormRequest: ConnectionFormRequest? = null,
    onConnectionFormRequestConsumed: () -> Unit = {},
    activeConnectionId: String? = null,
    defaultConnectionId: String? = null,
) {
    var formMode by remember { mutableStateOf<ConnectionFormMode?>(null) }
    val connections by viewModel.connections.collectAsState()
    val loading by viewModel.loading.collectAsState()
    LaunchedEffect(connectionFormRequest, connections, loading) {
        when (connectionFormRequest) {
            null -> Unit
            ConnectionFormRequest.New -> {
                formMode = ConnectionFormMode.New
                onConnectionFormRequestConsumed()
            }
            is ConnectionFormRequest.Edit -> {
                val target = connections.firstOrNull { it.id == connectionFormRequest.connectionId }
                if (target != null) {
                    formMode = ConnectionFormMode.Edit(target)
                    onConnectionFormRequestConsumed()
                } else if (!loading) {
                    onConnectionFormRequestConsumed()
                }
            }
        }
    }
    ConnectionsScreenContent(
        viewModel = viewModel,
        connections = connections,
        loading = loading,
        formMode = formMode,
        onFormModeChange = { formMode = it },
        onDeleted = onDeleted,
        onConnectionChanged = onConnectionChanged,
        onSelectConnection = onSelectConnection,
        activeConnectionId = activeConnectionId,
        defaultConnectionId = defaultConnectionId,
    )
}

@Composable
internal fun ConnectionsScreenContent(
    viewModel: ConnectionsViewModel,
    connections: List<ConnectionDef>,
    loading: Boolean,
    formMode: ConnectionFormMode?,
    onFormModeChange: (ConnectionFormMode?) -> Unit,
    onDeleted: (String) -> Unit,
    onConnectionChanged: (String) -> Unit,
    onSelectConnection: (ConnectionDef) -> Unit = {},
    activeConnectionId: String? = null,
    defaultConnectionId: String? = null,
) {
    val error by viewModel.error.collectAsState()
    val deleteError by viewModel.deleteError.collectAsState()
    var pendingDelete by remember { mutableStateOf<ConnectionDef?>(null) }

    ConfirmDialog(
        open = pendingDelete != null,
        title = "Delete connection?",
        message =
            pendingDelete?.let { "Delete connection \"${it.name}\"? This cannot be undone." } ?: "",
        destructive = true,
        onConfirm = {
            pendingDelete?.let { connection ->
                viewModel.delete(connection.id) { onDeleted(connection.id) }
            }
            pendingDelete = null
        },
        onCancel = { pendingDelete = null },
    )

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .background(SafeDbTheme.colors.workspaceHeader)
                    .padding(
                        horizontal = ScreenHeaderHorizontalPadding,
                        vertical = TitleHeaderVerticalPadding,
                    ),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    when (formMode) {
                        is ConnectionFormMode.Edit -> "Edit Connection"
                        ConnectionFormMode.New -> "New Connection"
                        null -> "Connections"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    when (formMode) {
                        is ConnectionFormMode.Edit ->
                            "Update connection details and driver properties."
                        ConnectionFormMode.New ->
                            "Connect to a database. Credentials are stored in your OS keychain."
                        null -> "Manage your database connections."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (formMode != null) {
                Text(
                    "Cancel",
                    style = MaterialTheme.typography.labelLarge,
                    color = SafeDbTheme.colors.actionPrimary,
                    modifier = Modifier.clickable { onFormModeChange(null) }.padding(8.dp),
                )
            } else {
                PrimaryButton(onClick = { onFormModeChange(ConnectionFormMode.New) }) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text("Add Connection", modifier = Modifier.padding(start = 8.dp))
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        when {
            formMode != null -> {
                val editingConnection = (formMode as? ConnectionFormMode.Edit)?.connection
                Box(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    key(editingConnection?.id) {
                        ConnectionForm(
                            connectionsViewModel = viewModel,
                            existingConnection = editingConnection,
                            onConnectionChanged = onConnectionChanged,
                            onSaved = { _, _ -> onFormModeChange(null) },
                            modifier = Modifier.widthIn(max = 1_020.dp),
                        )
                    }
                }
            }
            loading ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    androidx.compose.material3.CircularProgressIndicator()
                }
            error != null ->
                Box(Modifier.padding(32.dp)) {
                    Text(error!!, color = MaterialTheme.colorScheme.error)
                }
            connections.isEmpty() ->
                EmptyState(
                    icon = Icons.Filled.Add,
                    title = "No connections yet",
                    subtitle = "Add a connection to start exploring your data.",
                ) {
                    PrimaryButton(onClick = { onFormModeChange(ConnectionFormMode.New) }) {
                        Text("Add Connection")
                    }
                }
            else -> {
                Column(modifier = Modifier.fillMaxSize()) {
                    if (deleteError != null) {
                        MessageBanner(
                            text = deleteError!!,
                            kind = com.safedb.ui.components.BannerKind.ERROR,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                        ) {
                            SecondaryButton(onClick = viewModel::clearDeleteError) {
                                Text("Dismiss")
                            }
                        }
                    }

                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 276.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                    ) {
                        items(connections, key = { it.id }) { connection ->
                            ConnectionCard(
                                connection = connection,
                                isSelected = connection.id == activeConnectionId,
                                isDefault = connection.id == defaultConnectionId,
                                onSelect = { onSelectConnection(connection) },
                                onEdit = { onFormModeChange(ConnectionFormMode.Edit(connection)) },
                                onDelete = { pendingDelete = connection },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionCard(
    connection: ConnectionDef,
    isSelected: Boolean = false,
    isDefault: Boolean = false,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    AppCard(onClick = onSelect) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Surface(
                        modifier = Modifier.size(30.dp),
                        shape = MaterialTheme.shapes.small,
                        color = SafeDbTheme.colors.accentContainer,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                connection.dialect.displayName().take(2),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = SafeDbTheme.colors.onAccentContainer,
                            )
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            connection.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            connection.dialect.displayName(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Box(
                            modifier = Modifier.padding(top = 4.dp).height(20.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            if (isSelected || isDefault) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (isSelected) {
                                        StatusChip("Selected", StatusChipKind.NEUTRAL)
                                    }
                                    if (isDefault) {
                                        StatusChip("Default", StatusChipKind.INFO)
                                    }
                                }
                            }
                        }
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.IconButton(
                        onClick = onEdit,
                        modifier = Modifier.size(40.dp).pointerHoverIcon(PointerIcon.Hand),
                    ) {
                        Icon(
                            Icons.Filled.Settings,
                            contentDescription = "Edit ${connection.name}",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    DeleteIconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(40.dp),
                        iconModifier = Modifier.size(18.dp),
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            ConnectionDetailRow("Host:", "${connection.host}:${connection.port}")
            ConnectionDetailRow("DB:", connection.database)
            ConnectionDetailRow("User:", connection.username)
        }
    }
}

@Composable
private fun ConnectionDetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = LabelMicro,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(30.dp),
        )
        Text(
            value,
            style = DataMono,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
