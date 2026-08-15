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
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.safedb.model.ConnectionDef
import com.safedb.ui.components.AppCard
import com.safedb.ui.components.ConfirmDialog
import com.safedb.ui.components.DeleteIconButton
import com.safedb.ui.components.EmptyState
import com.safedb.ui.components.MessageBanner
import com.safedb.ui.components.PrimaryButton
import com.safedb.ui.components.SecondaryButton
import com.safedb.ui.theme.DataMono
import com.safedb.ui.theme.LabelMicro
import com.safedb.ui.theme.SafeDbTheme
import com.safedb.ui.theme.ScreenHeaderHorizontalPadding
import com.safedb.ui.theme.TitleHeaderVerticalPadding
import com.safedb.viewmodel.ConnectionsViewModel

@Composable
fun ConnectionsScreen(
    viewModel: ConnectionsViewModel,
    onActivate: (String) -> Unit,
    onDeleted: (String) -> Unit,
    onConnectionChanged: (String) -> Unit,
    onSaved: () -> Unit,
) {
    var showConnectionForm by remember { mutableStateOf(false) }
    var editingConnection by remember { mutableStateOf<ConnectionDef?>(null) }
    ConnectionsScreenContent(
        viewModel = viewModel,
        showConnectionForm = showConnectionForm,
        editingConnection = editingConnection,
        onShowConnectionFormChange = { showConnectionForm = it },
        onEditingConnectionChange = { editingConnection = it },
        onActivate = onActivate,
        onDeleted = onDeleted,
        onConnectionChanged = onConnectionChanged,
        onSaved = onSaved,
    )
}

@Composable
internal fun ConnectionsScreenContent(
    viewModel: ConnectionsViewModel,
    showConnectionForm: Boolean,
    editingConnection: ConnectionDef?,
    onShowConnectionFormChange: (Boolean) -> Unit,
    onEditingConnectionChange: (ConnectionDef?) -> Unit,
    onActivate: (String) -> Unit,
    onDeleted: (String) -> Unit,
    onConnectionChanged: (String) -> Unit,
    onSaved: () -> Unit,
) {
    val connections by viewModel.connections.collectAsState()
    val loading by viewModel.loading.collectAsState()
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
                    when {
                        editingConnection != null -> "Edit Connection"
                        showConnectionForm -> "New Connection"
                        else -> "Connections"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    when {
                        editingConnection != null ->
                            "Update connection details and driver properties."
                        showConnectionForm ->
                            "Connect to a database. Credentials are stored in your OS keychain."
                        else -> "Manage your database connections."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (showConnectionForm || editingConnection != null) {
                Text(
                    "Cancel",
                    style = MaterialTheme.typography.labelLarge,
                    color = SafeDbTheme.colors.actionPrimary,
                    modifier =
                        Modifier.clickable {
                                onShowConnectionFormChange(false)
                                onEditingConnectionChange(null)
                            }
                            .padding(8.dp),
                )
            } else {
                PrimaryButton(onClick = { onShowConnectionFormChange(true) }) {
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
            showConnectionForm || editingConnection != null ->
                Box(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    ConnectionForm(
                        connectionsViewModel = viewModel,
                        existingConnection = editingConnection,
                        onSaved = { saved, credentialMaterialChanged ->
                            if (editingConnection != null && credentialMaterialChanged) {
                                onConnectionChanged(saved.id)
                            }
                            onShowConnectionFormChange(false)
                            onEditingConnectionChange(null)
                            onSaved()
                        },
                        modifier = Modifier.widthIn(max = 1_020.dp),
                    )
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
                    PrimaryButton(onClick = { onShowConnectionFormChange(true) }) {
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
                                onEdit = { onEditingConnectionChange(connection) },
                                onDelete = { pendingDelete = connection },
                                onOpen = { onActivate(connection.id) },
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
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onOpen: () -> Unit,
) {
    AppCard {
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
                                dialectLabel(connection.dialect).take(2),
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
                            dialectLabel(connection.dialect),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.IconButton(
                        onClick = onEdit,
                        modifier = Modifier.size(32.dp),
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
                        modifier = Modifier.size(32.dp),
                        iconModifier = Modifier.size(18.dp),
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            ConnectionDetailRow("Host:", "${connection.host}:${connection.port}")
            ConnectionDetailRow("DB:", connection.database)
            ConnectionDetailRow("User:", connection.username)

            PrimaryButton(
                onClick = onOpen,
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
            ) {
                Text("Open")
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier.padding(start = 8.dp).size(16.dp),
                )
            }
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
