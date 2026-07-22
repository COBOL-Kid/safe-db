package com.safedb.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.safedb.AppRoute
import com.safedb.AppState
import com.safedb.model.QuerySpec
import com.safedb.ui.components.CommandPalette
import com.safedb.ui.components.Kbd
import com.safedb.ui.theme.SafeDbTheme
import com.safedb.viewmodel.AppViewModel
import kotlinx.coroutines.delay

@Composable
fun AppShell(
    appState: AppState,
    viewModel: AppViewModel,
    paletteOpen: Boolean,
    onPaletteOpenChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    initialSidebarCollapsed: Boolean = false,
) {
    val route by appState.route.collectAsState()
    val settingsOpen by appState.settingsOpen.collectAsState()
    val activeConnectionId by appState.activeConnectionId.collectAsState()
    val settings by viewModel.settings.settings.collectAsState()
    val isDark = settings.theme == "dark"
    val activeConnection = activeConnectionId?.let(viewModel.connections::connectionById)
    var sidebarCollapsed by rememberSaveable { mutableStateOf(initialSidebarCollapsed) }

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
            collapsed = sidebarCollapsed,
            onCollapsedChange = { sidebarCollapsed = it },
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
                    connections = viewModel.connections.connections.value,
                    queryViewModel = viewModel.query,
                    savedQueriesViewModel = viewModel.savedQueries,
                    recipesViewModel = viewModel.recipes,
                    schemaViewModel = viewModel.schema,
                    onOpenExplore = {
                        val connection = activeConnection
                        val result = viewModel.query.results
                        if (connection != null && result != null) {
                            viewModel.openExplore(connection, viewModel.query.spec, result)
                        }
                    },
                    onApplyRecipe = { recipe, targetConnection ->
                        if (recipe.querySpec != null) {
                            appState.setActiveConnection(targetConnection.id)
                            viewModel.runRecipe(targetConnection, recipe)
                        } else {
                            val sample = viewModel.query.currentSample(targetConnection.id)
                            if (sample != null) {
                                viewModel.openExploreRecipe(targetConnection, sample.spec, sample.result, recipe)
                            }
                        }
                    },
                    onCancelQueryRun = viewModel::cancelPendingRecipeRun,
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
    collapsed: Boolean,
    onCollapsedChange: (Boolean) -> Unit,
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
    // Keep the leading icon track expanded until the rail finishes shrinking. Switching both
    // states together centers the compact buttons inside the still-wide rail and makes them jump.
    var widthCollapsed by remember { mutableStateOf(collapsed) }
    var layoutCollapsed by remember { mutableStateOf(collapsed) }
    val sidebarWidth by animateDpAsState(
        targetValue = if (widthCollapsed) CollapsedSidebarWidth else ExpandedSidebarWidth,
        animationSpec = tween(durationMillis = SidebarWidthAnimationMillis),
        label = "sidebarWidth",
    )
    var revealStep by remember { mutableIntStateOf(if (collapsed) 0 else SidebarRevealAll) }
    var revealInitialized by remember { mutableStateOf(false) }

    LaunchedEffect(collapsed) {
        if (!revealInitialized) {
            revealInitialized = true
            widthCollapsed = collapsed
            layoutCollapsed = collapsed
            revealStep = if (collapsed) 0 else SidebarRevealAll
            return@LaunchedEffect
        }

        if (collapsed) {
            widthCollapsed = false
            layoutCollapsed = false
            for (step in revealStep downTo 0) {
                revealStep = step
                delay(SidebarRevealStaggerMillis.toLong())
            }
            widthCollapsed = true
            delay(SidebarWidthAnimationMillis.toLong())
            layoutCollapsed = true
        } else {
            layoutCollapsed = false
            widthCollapsed = false
            revealStep = 0
            delay(SidebarWidthAnimationMillis.toLong())
            for (step in 1..SidebarRevealAll) {
                revealStep = step
                delay(SidebarRevealStaggerMillis.toLong())
            }
        }
    }

    Row(modifier = Modifier.fillMaxHeight()) {
        Column(
            modifier = Modifier
                .width(sidebarWidth)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surfaceContainerLow),
            horizontalAlignment = if (layoutCollapsed) Alignment.CenterHorizontally else Alignment.Start,
        ) {
            SidebarHeader(
                collapsed = layoutCollapsed,
                brandVisible = revealStep >= SidebarRevealHeader,
                onToggleCollapsed = { onCollapsedChange(!collapsed) },
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                horizontalAlignment = Alignment.Start,
            ) {
                for ((index, item) in navItems.withIndex()) {
                    NavButton(
                        item = item,
                        selected = route == item.route,
                        collapsed = layoutCollapsed,
                        labelVisible = revealStep >= SidebarRevealFirstNav + index,
                        onClick = { onNavigate(item.route) },
                    )
                }
            }

            SidebarUtilities(
                collapsed = layoutCollapsed,
                isDark = isDark,
                commandVisible = revealStep >= SidebarRevealCommand,
                statusVisible = revealStep >= SidebarRevealStatus,
                utilityButtonsVisible = revealStep >= SidebarRevealUtilityButtons,
                onOpenPalette = onOpenPalette,
                onOpenSettings = onOpenSettings,
                onToggleTheme = onToggleTheme,
            )
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
private fun SidebarHeader(
    collapsed: Boolean,
    brandVisible: Boolean,
    onToggleCollapsed: () -> Unit,
) {
    if (collapsed) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(SidebarHeaderHeight)
                .clickable(onClick = onToggleCollapsed)
                .semantics { contentDescription = "Expand sidebar" },
            contentAlignment = Alignment.Center,
        ) {
            LogoMark()
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 4.dp)
                    .size(14.dp),
            )
        }
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(SidebarHeaderHeight)
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            LogoMark()
            AnimatedSidebarLabel(visible = brandVisible, modifier = Modifier.weight(1f)) {
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
            SidebarIconButton(
                icon = Icons.Filled.ChevronLeft,
                contentDescription = "Collapse sidebar",
                onClick = onToggleCollapsed,
            )
        }
    }
}

@Composable
private fun SidebarUtilities(
    collapsed: Boolean,
    isDark: Boolean,
    commandVisible: Boolean,
    statusVisible: Boolean,
    utilityButtonsVisible: Boolean,
    onOpenPalette: () -> Unit,
    onOpenSettings: () -> Unit,
    onToggleTheme: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (collapsed) {
            SidebarIconButton(
                icon = Icons.Filled.Search,
                contentDescription = "Search commands",
                onClick = onOpenPalette,
            )
            SidebarStatusIndicator()
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
        } else {
            AnimatedSidebarLabel(visible = commandVisible, modifier = Modifier.fillMaxWidth()) {
                SidebarCommandButton(onClick = onOpenPalette)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AnimatedSidebarLabel(visible = statusVisible, modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
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
                }

                AnimatedSidebarLabel(visible = utilityButtonsVisible) {
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
    }
}

@Composable
private fun LogoMark() {
    val accent = SafeDbTheme.colors.actionPrimary
    val accentDeep = SafeDbTheme.colors.actionPrimaryHover
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(8.dp))
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
private fun SidebarStatusIndicator() {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(6.dp))
            .semantics { contentDescription = "Safe Read Mode" },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(50))
                .background(SafeDbTheme.colors.success),
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
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
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
            .clip(RoundedCornerShape(6.dp))
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
    collapsed: Boolean,
    labelVisible: Boolean,
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
            .height(44.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            .hoverable(interactionSource)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Icon(
            item.icon,
            contentDescription = if (collapsed) item.label else null,
            tint = content,
            modifier = Modifier.size(18.dp),
        )
        AnimatedSidebarLabel(visible = labelVisible) {
            Text(
                item.label,
                color = content,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            )
        }
    }
}

@Composable
private fun AnimatedSidebarLabel(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(120)) + expandHorizontally(tween(160), expandFrom = Alignment.Start),
        exit = fadeOut(tween(80)) + shrinkHorizontally(tween(120), shrinkTowards = Alignment.Start),
    ) {
        content()
    }
}

private data class NavItem(
    val route: AppRoute,
    val label: String,
    val icon: ImageVector,
)

private val ExpandedSidebarWidth = 232.dp
private val CollapsedSidebarWidth = 72.dp
private val SidebarHeaderHeight = 78.dp
private const val SidebarWidthAnimationMillis = 240
private const val SidebarRevealStaggerMillis = 55
private const val SidebarRevealHeader = 1
private const val SidebarRevealFirstNav = 2
private const val SidebarRevealCommand = 6
private const val SidebarRevealStatus = 7
private const val SidebarRevealUtilityButtons = 8
private const val SidebarRevealAll = SidebarRevealUtilityButtons
