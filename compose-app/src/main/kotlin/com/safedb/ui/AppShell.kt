package com.safedb.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.safedb.AppRoute
import com.safedb.AppState
import com.safedb.model.QuerySpec
import com.safedb.ui.components.CommandPalette
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
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "sd",
                        color = MaterialTheme.colorScheme.onPrimary,
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

            HorizontalDivider()

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

            HorizontalDivider()

            Column(modifier = Modifier.padding(12.dp)) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
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

                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onOpenPalette) {
                        Text("⌘K", style = MaterialTheme.typography.labelSmall)
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                    IconButton(onClick = onToggleTheme) {
                        Icon(
                            imageVector = if (isDark) Icons.Filled.WbSunny else Icons.Outlined.DarkMode,
                            contentDescription = "Toggle theme",
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NavButton(
    item: NavItem,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val background = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surface
    }
    val content = if (selected) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(background)
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
