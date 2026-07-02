package com.safedb.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowForward
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
import androidx.compose.ui.unit.dp
import com.safedb.model.ConnectionDef
import com.safedb.model.Dialect
import com.safedb.ui.components.AppCard
import com.safedb.ui.components.ConfirmDialog
import com.safedb.ui.components.EmptyState
import com.safedb.ui.components.MessageBanner
import com.safedb.ui.components.PrimaryButton
import com.safedb.ui.components.SecondaryButton
import com.safedb.ui.components.DeleteIconButton
import com.safedb.viewmodel.ConnectionsViewModel

@Composable
fun ConnectionsScreen(
    service: com.safedb.service.SafeDbService,
    viewModel: ConnectionsViewModel,
    onActivate: (String) -> Unit,
    onSaved: () -> Unit,
) {
    val connections by viewModel.connections.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val error by viewModel.error.collectAsState()
    val deleteError by viewModel.deleteError.collectAsState()
    var showFormPlaceholder by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<ConnectionDef?>(null) }

    ConfirmDialog(
        open = pendingDelete != null,
        title = "Delete connection?",
        message = pendingDelete?.let { "Delete connection \"${it.name}\"? This cannot be undone." } ?: "",
        destructive = true,
        onConfirm = {
            pendingDelete?.let { viewModel.delete(it.id) }
            pendingDelete = null
        },
        onCancel = { pendingDelete = null },
    )

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("Connections", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    "Manage your database connections.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (!showFormPlaceholder) {
                PrimaryButton(onClick = { showFormPlaceholder = true }) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("Add Connection", modifier = Modifier.padding(start = 8.dp))
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        when {
            showFormPlaceholder -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                ConnectionForm(
                    service = service,
                    onSaved = {
                        showFormPlaceholder = false
                        onSaved()
                    },
                    onCancel = { showFormPlaceholder = false },
                    modifier = Modifier.widthIn(max = 720.dp),
                )
            }
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                androidx.compose.material3.CircularProgressIndicator()
            }
            error != null -> Box(Modifier.padding(32.dp)) {
                Text(error!!, color = MaterialTheme.colorScheme.error)
            }
            connections.isEmpty() -> EmptyState(
                icon = Icons.Filled.Add,
                title = "No connections yet",
                subtitle = "Add a connection to start exploring your data.",
            ) {
                PrimaryButton(onClick = { showFormPlaceholder = true }) {
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
                        columns = GridCells.Adaptive(minSize = 240.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                    ) {
                        items(connections, key = { it.id }) { connection ->
                            ConnectionCard(
                                connection = connection,
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        modifier = Modifier.size(30.dp),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                dialectLabel(connection.dialect).take(2),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                    Column {
                        Text(connection.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(
                            dialectLabel(connection.dialect),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                DeleteIconButton(onClick = onDelete)
            }

            androidx.compose.foundation.layout.Spacer(Modifier.height(12.dp))
            Text("Host  ${connection.host}:${connection.port}", style = MaterialTheme.typography.labelSmall)
            Text("DB    ${connection.database}", style = MaterialTheme.typography.labelSmall)
            Text("User  ${connection.username}", style = MaterialTheme.typography.labelSmall)

            PrimaryButton(
                onClick = onOpen,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp),
            ) {
                Text("Open")
                Icon(Icons.Filled.ArrowForward, contentDescription = null, modifier = Modifier.padding(start = 8.dp).size(16.dp))
            }
        }
    }
}

private fun dialectLabel(dialect: Dialect): String =
    when (dialect) {
        Dialect.Postgres -> "PostgreSQL"
        Dialect.MySql -> "MySQL"
        Dialect.Mssql -> "SQL Server"
        Dialect.Oracle -> "Oracle"
    }
