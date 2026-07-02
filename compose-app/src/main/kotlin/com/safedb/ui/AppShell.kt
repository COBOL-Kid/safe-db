package com.safedb.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.safedb.AppRoute
import com.safedb.AppState
import com.safedb.model.QuerySpec
import com.safedb.ui.components.CommandPalette
import com.safedb.ui.components.Kbd
import com.safedb.ui.theme.SafeDbTheme
import com.safedb.viewmodel.AppViewModel

@Composable
fun AppShell(
    appState: AppState,
    viewModel: AppViewModel,
    paletteOpen: Boolean,
    onPaletteOpenChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val route by appState.route.collectAsState()
    val settingsOpen by appState.settingsOpen.collectAsState()
    val activeConnectionId by appState.activeConnectionId.collectAsState()
    val settings by viewModel.settings.settings.collectAsState()
    val isDark = settings.theme == "dark"
    val activeConnection = activeConnectionId?.let(viewModel.connections::connectionById)

    fun restoreQuery(connectionId: String, spec: QuerySpec) {
        appState.setActiveConnection(connectionId)
        viewModel.restoreQueryForConnection(connectionId, spec) { restored ->
            if (restored) {
                appState.navigate(AppRoute.Builder)
            }
        }
    }

    CommandPalette(
        open = paletteOpen,
        onDismiss = { onPaletteOpenChange(false) },
        appState = appState,
        viewModel = viewModel,
    )

    SettingsPanel(
        open = settingsOpen,
        viewModel = viewModel.settings,
        onClose = appState::closeSettings,
    )

    Row(modifier = modifier.fillMaxSize()) {
        Sidebar(
            route = route,
            isDark = isDark,
            onNavigate = appState::navigate,
            onOpenSettings = appState::openSettings,
            onOpenPalette = { onPaletteOpenChange(true) },
            onToggleTheme = viewModel.settings::toggleTheme,
        )

        Surface(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            color = MaterialTheme.colorScheme.background,
        ) {
            when (route) {
                AppRoute.Home -> HomeScreen(
                    viewModel = viewModel,
                    onNavigate = appState::navigate,
                    onOpenSavedQuery = { savedQuery ->
                        restoreQuery(savedQuery.connectionId, savedQuery.spec)
                    },
                )
                AppRoute.Connections -> ConnectionsScreen(
                    service = appState.service,
                    viewModel = viewModel.connections,
                    onActivate = { id ->
                        appState.setActiveConnection(id)
                        appState.navigate(AppRoute.Builder)
                    },
                    onSaved = viewModel.connections::refresh,
                )
                AppRoute.Builder -> BuilderScreen(
                    connection = activeConnection,
                    queryViewModel = viewModel.query,
                    savedQueriesViewModel = viewModel.savedQueries,
                    schemaViewModel = viewModel.schema,
                )
                AppRoute.History -> HistoryScreen(
                    viewModel = viewModel,
                    onRerun = { entry ->
                        restoreQuery(entry.connectionId, entry.spec)
                    },
                    onNavigate = appState::navigate,
                )
            }
        }
    }
}

@Composable
private fun Sidebar(
    route: AppRoute,
    isDark: Boolean,
    onNavigate: (AppRoute) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPalette: () -> Unit,
    onToggleTheme: () -> Unit,
) {
    val navItems = listOf(
        NavItem(AppRoute.Home, "Home", Icons.Filled.Home),
        NavItem(AppRoute.Connections, "Connections", Icons.Filled.Link),
        NavItem(AppRoute.Builder, "Query Builder", Icons.Filled.Build),
        NavItem(AppRoute.History, "History", Icons.Filled.History),
    )

    Surface(
        modifier = Modifier
            .width(224.dp)
            .fillMaxHeight(),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        tonalElevation = 0.dp,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val action = SafeDbTheme.colors.actionPrimary
                val onAction = SafeDbTheme.colors.onActionPrimary
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(action),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "sd",
                        color = onAction,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(
                    "safe-db",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (item in navItems) {
                    NavButton(
                        item = item,
                        selected = route == item.route,
                        onClick = { onNavigate(item.route) },
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SidebarCommandButton(onClick = onOpenPalette)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Text(
                                "Safe Read Mode",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                "No-lock · Indexed joins",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            )
                        }
                    }

                    Spacer(Modifier.width(8.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        SidebarIconButton(
                            icon = Icons.Filled.Settings,
                            contentDescription = "Settings",
                            onClick = onOpenSettings,
                        )
                        SidebarIconButton(
                            icon = if (isDark) Icons.Filled.WbSunny else Icons.Outlined.DarkMode,
                            contentDescription = "Toggle theme",
                            onClick = onToggleTheme,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SidebarCommandButton(onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (hovered) MaterialTheme.colorScheme.surfaceContainerLow else Color.Transparent)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
            .hoverable(interactionSource)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Command",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            modifier = Modifier.weight(1f),
        )
        Kbd("\u2318K")
    }
}

@Composable
private fun SidebarIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()

    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (hovered) MaterialTheme.colorScheme.surfaceContainerLow else Color.Transparent)
            .hoverable(interactionSource)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = if (hovered) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun NavButton(
    item: NavItem,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()

    val action = SafeDbTheme.colors.actionPrimary
    val onAction = SafeDbTheme.colors.onActionPrimary
    val background = when {
        selected -> action
        hovered -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        else -> Color.Transparent
    }
    val content = if (selected) onAction else MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .hoverable(interactionSource)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(item.icon, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
        Text(item.label, color = content, style = MaterialTheme.typography.bodyMedium)
    }
}

private data class NavItem(
    val route: AppRoute,
    val label: String,
    val icon: ImageVector,
)
