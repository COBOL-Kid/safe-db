package com.safedb.ui

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Storage
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
import androidx.compose.ui.graphics.Brush
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
        NavItem(AppRoute.Home, "Home", Icons.Outlined.Home),
        NavItem(AppRoute.Connections, "Connections", Icons.Outlined.Storage),
        NavItem(AppRoute.Builder, "Query Builder", Icons.Outlined.AccountTree),
        NavItem(AppRoute.History, "History", Icons.Outlined.History),
    )

    Row(modifier = Modifier.fillMaxHeight()) {
        Column(
            modifier = Modifier
                .width(232.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 22.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                LogoMark()
                Column {
                    Text(
                        "Safe-DB",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "read-only explorer",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                for (item in navItems) {
                    NavButton(
                        item = item,
                        selected = route == item.route,
                        onClick = { onNavigate(item.route) },
                    )
                }
            }

            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SidebarCommandButton(onClick = onOpenPalette)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 4.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(RoundedCornerShape(50))
                                .background(SafeDbTheme.colors.success),
                        )
                        Column {
                            Text(
                                "Safe Read Mode",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                "No-lock · Indexed joins",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
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

        Box(
            modifier = Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.outline),
        )
    }
}

@Composable
private fun LogoMark() {
    val accent = SafeDbTheme.colors.actionPrimary
    val accentDeep = SafeDbTheme.colors.actionPrimaryHover
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Brush.linearGradient(listOf(accent, accentDeep))),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "sDB",
            color = SafeDbTheme.colors.onActionPrimary,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun SidebarCommandButton(onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val background by animateColorAsState(
        if (hovered) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(background)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(9.dp))
            .hoverable(interactionSource)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Search commands…",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    val background by animateColorAsState(
        if (hovered) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
    )

    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(background)
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

    val c = SafeDbTheme.colors
    val background by animateColorAsState(
        when {
            selected -> c.accentContainer
            hovered -> MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f)
            else -> Color.Transparent
        },
    )
    val content by animateColorAsState(
        when {
            selected -> c.onAccentContainer
            hovered -> MaterialTheme.colorScheme.onSurface
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(background)
            .hoverable(interactionSource)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Icon(item.icon, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
        Text(
            item.label,
            color = content,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        )
    }
}

private data class NavItem(
    val route: AppRoute,
    val label: String,
    val icon: ImageVector,
)
