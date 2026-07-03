package com.safedb.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.safedb.AppRoute
import com.safedb.ui.components.AppCard
import com.safedb.ui.components.ConfirmDialog
import com.safedb.ui.components.DeleteIconButton
import com.safedb.ui.theme.SafeDbTheme
import com.safedb.viewmodel.AppViewModel

@Composable
fun HomeScreen(
    viewModel: AppViewModel,
    onNavigate: (AppRoute) -> Unit,
    onOpenSavedQuery: (com.safedb.model.SavedQuery) -> Unit,
) {
    val initialLoading by viewModel.initialLoading.collectAsState()
    val savedQueries by viewModel.savedQueries.queries.collectAsState()
    var deleteTargetId by remember { mutableStateOf<String?>(null) }

    ConfirmDialog(
        open = deleteTargetId != null,
        title = "Delete saved query?",
        message = "This saved query will be permanently removed.",
        destructive = true,
        onConfirm = {
            deleteTargetId?.let(viewModel.savedQueries::delete)
            deleteTargetId = null
        },
        onCancel = { deleteTargetId = null },
    )

    if (initialLoading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 896.dp)
                .padding(horizontal = 32.dp, vertical = 48.dp),
        ) {
            Text("Welcome to safe-db", style = MaterialTheme.typography.headlineLarge)
            Text(
                "Safely explore production databases with non-locking reads and enforced best practices.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )

            Spacer(Modifier.height(40.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                QuickLinkCard(
                    title = "New Connection",
                    description = "Connect to a database",
                    icon = Icons.Filled.Add,
                    onClick = { onNavigate(AppRoute.Connections) },
                    modifier = Modifier.weight(1f),
                )
                QuickLinkCard(
                    title = "Build a Query",
                    description = "Visually explore your data",
                    icon = Icons.Filled.Build,
                    onClick = { onNavigate(AppRoute.Builder) },
                    modifier = Modifier.weight(1f),
                )
                QuickLinkCard(
                    title = "Recent Queries",
                    description = "Revisit past explorations",
                    icon = Icons.Filled.History,
                    onClick = { onNavigate(AppRoute.History) },
                    modifier = Modifier.weight(1f),
                )
            }

            if (savedQueries.isNotEmpty()) {
                Spacer(Modifier.height(40.dp))
                Text(
                    "Saved Queries",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(12.dp))
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 320.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(((savedQueries.size + 1) / 2 * 92).dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    userScrollEnabled = false,
                ) {
                    items(savedQueries, key = { it.id }) { query ->
                        SavedQueryCard(
                            name = query.name,
                            subtitle = "${viewModel.connections.connectionName(query.connectionId)} · " +
                                "${query.spec.tables.size} table${if (query.spec.tables.size == 1) "" else "s"}",
                            onOpen = { onOpenSavedQuery(query) },
                            onDelete = { deleteTargetId = query.id },
                        )
                    }
                }
            }

            Spacer(Modifier.height(48.dp))

            ProtectionInfoCard()
        }
    }

}

@Composable
private fun QuickLinkCard(
    title: String,
    description: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()

    val borderColor by animateColorAsState(
        if (hovered) {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
        } else {
            MaterialTheme.colorScheme.outline
        },
    )
    val shadow by animateDpAsState(if (hovered) 3.dp else 0.dp)
    val tileColor by animateColorAsState(
        if (hovered) SafeDbTheme.colors.actionPrimary else SafeDbTheme.colors.accentContainer,
    )
    val tileIconColor by animateColorAsState(
        if (hovered) SafeDbTheme.colors.onActionPrimary else SafeDbTheme.colors.onAccentContainer,
    )

    Surface(
        modifier = modifier
            .hoverable(interactionSource)
            .clickable(interactionSource = interactionSource, onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, borderColor),
        shadowElevation = shadow,
        tonalElevation = 0.dp,
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = RoundedCornerShape(11.dp),
                color = tileColor,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = tileIconColor, modifier = Modifier.size(20.dp))
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun SavedQueryCard(
    name: String,
    subtitle: String,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    AppCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onOpen),
            ) {
                Text(name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            DeleteIconButton(onClick = onDelete, contentDescription = "Delete saved query")
        }
    }
}

@Composable
private fun ProtectionInfoCard() {
    AppCard {
        Column(modifier = Modifier.padding(24.dp)) {
            Text("How safe-db protects your database", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(16.dp))
            val items = listOf(
                "Non-locking reads" to "Dirty-read tolerant isolation — never blocks production writes.",
                "Indexed joins only" to "Joins on non-indexed columns are rejected before execution.",
                "Guided row limits & timeouts" to "Every query is bounded, with coaching when reporting needs more rows.",
                "Read-only by construction" to "Writes are structurally impossible — no SQL injection surface.",
            )
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                for ((title, body) in items) {
                    ProtectionItem(title, body)
                }
            }
        }
    }
}

@Composable
private fun ProtectionItem(title: String, body: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(
            modifier = Modifier.size(20.dp),
            shape = RoundedCornerShape(50),
            color = SafeDbTheme.colors.successContainer,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = SafeDbTheme.colors.success,
                )
            }
        }
        Column {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
