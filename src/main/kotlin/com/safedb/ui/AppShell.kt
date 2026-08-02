package com.safedb.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.safedb.model.ConnectionDef
import com.safedb.AppRoute
import com.safedb.AppState
import com.safedb.model.QuerySpec
import com.safedb.ui.components.CommandPalette
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
    newConnectionPreview: Boolean = false,
    editConnectionPreview: ConnectionDef? = null,
) {
    val route by appState.route.collectAsState()
    val settingsOpen by appState.settingsOpen.collectAsState()
    val initialLoading by viewModel.initialLoading.collectAsState()
    val activeConnectionId by appState.activeConnectionId.collectAsState()
    val schemaSelection by appState.schemaSelection.collectAsState()
    val settings by viewModel.settings.settings.collectAsState()
    val schemaHistoryError by viewModel.settings.schemaHistoryError.collectAsState()
    val connections by viewModel.connections.connections.collectAsState()
    val isDark = settings.theme == "dark"
    val activeConnection = connections.firstOrNull { it.id == activeConnectionId }
    var sidebarCollapsed by rememberSaveable { mutableStateOf(initialSidebarCollapsed) }

    fun restoreQuery(connectionId: String, spec: QuerySpec) {
        val restoredSelection = com.safedb.resolveQuerySchemaSelection(spec)
        appState.setActiveConnection(connectionId, restoredSelection)
        viewModel.restoreQueryForConnection(connectionId, spec, restoredSelection) { restored ->
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
        open = shouldShowSettingsPanel(settingsOpen, initialLoading),
        viewModel = viewModel.settings,
        connections = connections,
        onDefaultLocationChanged = { connectionId, schema ->
            appState.activateDefaultConnection(connectionId, schema)
        },
        onDefaultLocationCleared = appState::clearDefaultConnection,
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
            color = SafeDbTheme.colors.workspaceBackground,
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
                        appState.setActiveConnection(
                            id,
                            com.safedb.resolveConnectionSchemaSelection(id, settings),
                        )
                        appState.navigate(AppRoute.Builder)
                    },
                    onDeleted = { id ->
                        appState.clearActiveConnectionIf(id)
                        viewModel.settings.clearDefaultIfConnection(id)
                    },
                    onConnectionChanged = { id ->
                        appState.clearActiveConnectionIf(id)
                        viewModel.settings.clearSchemaSelectionsForConnection(id)
                    },
                    onSaved = viewModel.connections::refresh,
                    initialCreating = newConnectionPreview,
                    initialEditingConnection = editConnectionPreview,
                )
                AppRoute.Builder -> BuilderScreen(
                    connection = activeConnection,
                    connections = connections,
                    queryViewModel = viewModel.query,
                    savedQueriesViewModel = viewModel.savedQueries,
                    recipesViewModel = viewModel.recipes,
                    schemaViewModel = viewModel.schema,
                    schemaSelection = schemaSelection,
                    schemaHistoryError = schemaHistoryError,
                    settings = settings,
                    onSchemaSelected = { schema ->
                        val connectionId = activeConnection?.id ?: return@BuilderScreen
                        appState.setActiveSchema(schema)
                        viewModel.settings.rememberLastSchema(connectionId, schema)
                    },
                    onUnavailableSchemaSelection = { selection ->
                        val connectionId = activeConnection?.id ?: return@BuilderScreen
                        if (selection.source == com.safedb.SchemaSelectionSource.ConnectionHistory) {
                            viewModel.settings.forgetLastSchema(connectionId)
                        }
                    },
                    onDismissSchemaHistoryError = viewModel.settings::clearSchemaHistoryError,
                    onOpenExplore = {
                        val connection = activeConnection
                        val sample = viewModel.query.currentSample(connection?.id)
                        if (connection != null && sample != null) {
                            viewModel.openExplore(connection, sample.spec, sample.result)
                        }
                    },
                    onOpenSettings = appState::openSettings,
                    onApplyRecipe = { recipe, targetConnection ->
                        val querySpec = recipe.querySpec
                        if (querySpec != null) {
                            appState.setActiveConnection(
                                targetConnection.id,
                                com.safedb.resolveQuerySchemaSelection(querySpec),
                            )
                            viewModel.runRecipe(targetConnection, recipe)
                        } else {
                            val sample = viewModel.query.currentSample(targetConnection.id)
                            if (sample != null) {
                                viewModel.openExploreRecipe(targetConnection, sample.spec, sample.result, recipe)
                            }
                        }
                    },
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
    val c = SafeDbTheme.colors
    val navItems = listOf(
        NavItem(AppRoute.Home, "Home", Icons.Outlined.Home),
        NavItem(AppRoute.Connections, "Connections", Icons.Outlined.Storage),
        NavItem(AppRoute.Builder, "Query Builder", Icons.Outlined.AccountTree),
        NavItem(AppRoute.History, "History", Icons.Outlined.History),
    )
    // Keep the content mode stable while the rail changes width. This leaves a clean gap
    // between the outgoing and incoming fade sequences instead of cross-fading both layouts.
    var widthCollapsed by remember { mutableStateOf(collapsed) }
    var layoutCollapsed by remember { mutableStateOf(collapsed) }
    val sidebarWidth by animateDpAsState(
        targetValue = if (widthCollapsed) CollapsedSidebarWidth else ExpandedSidebarWidth,
        animationSpec = tween(durationMillis = SidebarWidthAnimationMillis),
        label = "sidebarWidth",
    )
    var revealStep by remember { mutableIntStateOf(if (collapsed) 0 else SidebarRevealAll) }
    var compactRevealStep by remember {
        mutableIntStateOf(if (collapsed) SidebarCompactRevealAll else 0)
    }
    var revealInitialized by remember { mutableStateOf(false) }

    LaunchedEffect(collapsed) {
        if (!revealInitialized) {
            revealInitialized = true
            widthCollapsed = collapsed
            layoutCollapsed = collapsed
            revealStep = if (collapsed) 0 else SidebarRevealAll
            compactRevealStep = if (collapsed) SidebarCompactRevealAll else 0
            return@LaunchedEffect
        }

        if (collapsed) {
            if (!layoutCollapsed) {
                compactRevealStep = 0
                val hadExpandedContent = revealStep > 0
                for (step in sidebarRevealSteps(revealStep, 0)) {
                    revealStep = step
                    delay(SidebarRevealStaggerMillis.toLong())
                }
                if (hadExpandedContent) {
                    delay((SidebarExpandedExitMillis - SidebarRevealStaggerMillis).toLong())
                }

                val widthWasExpanded = !widthCollapsed
                widthCollapsed = true
                if (widthWasExpanded) {
                    delay(SidebarWidthAnimationMillis.toLong())
                }
                layoutCollapsed = true
            }

            for (step in sidebarRevealSteps(compactRevealStep, SidebarCompactRevealAll)) {
                compactRevealStep = step
                delay(SidebarRevealStaggerMillis.toLong())
            }
        } else {
            if (layoutCollapsed) {
                val hadCompactContent = compactRevealStep > 0
                for (step in sidebarRevealSteps(compactRevealStep, 0)) {
                    compactRevealStep = step
                    delay(SidebarRevealStaggerMillis.toLong())
                }
                if (hadCompactContent) {
                    delay((SidebarUtilityFadeOutMillis - SidebarRevealStaggerMillis).toLong())
                }
                layoutCollapsed = false
            }

            val widthWasCollapsed = widthCollapsed
            widthCollapsed = false
            if (widthWasCollapsed) {
                delay(SidebarWidthAnimationMillis.toLong())
            }

            for (step in sidebarRevealSteps(revealStep, SidebarRevealAll)) {
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
                .background(c.navigationBackground),
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
                statusVisible = revealStep >= SidebarRevealStatus,
                settingsVisible = revealStep >= SidebarRevealSettings,
                themeVisible = revealStep >= SidebarRevealTheme,
                compactRevealStep = compactRevealStep,
                onOpenPalette = onOpenPalette,
                onOpenSettings = onOpenSettings,
                onToggleTheme = onToggleTheme,
            )
        }

        Box(
            modifier = Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(c.navigationBorder),
        )
    }
}

@Composable
private fun SidebarHeader(
    collapsed: Boolean,
    brandVisible: Boolean,
    onToggleCollapsed: () -> Unit,
) {
    val c = SafeDbTheme.colors
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
                tint = c.onNavigationMuted,
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
                .padding(horizontal = 16.dp),
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
                        color = c.onNavigation,
                    )
                    Text(
                        "read-only explorer",
                        style = MaterialTheme.typography.labelSmall,
                        color = c.onNavigationMuted,
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
    statusVisible: Boolean,
    settingsVisible: Boolean,
    themeVisible: Boolean,
    compactRevealStep: Int,
    onOpenPalette: () -> Unit,
    onOpenSettings: () -> Unit,
    onToggleTheme: () -> Unit,
) {
    val c = SafeDbTheme.colors
    Column(
        modifier = Modifier.padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (collapsed) {
            SidebarFade(
                visible = compactRevealStep >= SidebarCompactRevealCommand,
                modifier = Modifier.size(32.dp),
            ) { enabled ->
                SidebarIconButton(
                    icon = Icons.Filled.Search,
                    contentDescription = "Search commands",
                    enabled = enabled,
                    onClick = onOpenPalette,
                )
            }
            SidebarFade(
                visible = compactRevealStep >= SidebarCompactRevealStatus,
                modifier = Modifier.size(32.dp),
            ) {
                SidebarStatusIndicator()
            }
            SidebarFade(
                visible = compactRevealStep >= SidebarCompactRevealSettings,
                modifier = Modifier.size(32.dp),
            ) { enabled ->
                SidebarIconButton(
                    icon = Icons.Filled.Settings,
                    contentDescription = "Settings",
                    enabled = enabled,
                    onClick = onOpenSettings,
                )
            }
            SidebarFade(
                visible = compactRevealStep >= SidebarCompactRevealTheme,
                modifier = Modifier.size(32.dp),
            ) { enabled ->
                SidebarIconButton(
                    icon = if (isDark) Icons.Filled.WbSunny else Icons.Outlined.DarkMode,
                    contentDescription = "Toggle theme",
                    enabled = enabled,
                    onClick = onToggleTheme,
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SidebarFade(
                    visible = statusVisible,
                    modifier = Modifier.weight(1f),
                ) {
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
                                color = c.onNavigation,
                            )
                            Text(
                                "No-lock · Indexed joins",
                                style = MaterialTheme.typography.labelSmall,
                                color = c.onNavigationMuted,
                            )
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    SidebarFade(
                        visible = settingsVisible,
                        modifier = Modifier.size(32.dp),
                    ) { enabled ->
                        SidebarIconButton(
                            icon = Icons.Filled.Settings,
                            contentDescription = "Settings",
                            enabled = enabled,
                            onClick = onOpenSettings,
                        )
                    }
                    SidebarFade(
                        visible = themeVisible,
                        modifier = Modifier.size(32.dp),
                    ) { enabled ->
                        SidebarIconButton(
                            icon = if (isDark) Icons.Filled.WbSunny else Icons.Outlined.DarkMode,
                            contentDescription = "Toggle theme",
                            enabled = enabled,
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
    val c = SafeDbTheme.colors
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(c.actionPrimary),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "sDB",
            color = c.onActionPrimary,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun SidebarStatusIndicator() {
    val c = SafeDbTheme.colors
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(2.dp))
            .semantics { contentDescription = "Safe Read Mode" },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(50))
                .background(c.success),
        )
    }
}

@Composable
private fun SidebarIconButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val c = SafeDbTheme.colors
    val background by animateColorAsState(
        if (enabled && hovered) c.navigationHover else Color.Transparent,
    )

    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(background)
            .hoverable(interactionSource, enabled = enabled)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = if (enabled && hovered) c.onNavigation else c.onNavigationMuted,
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
            selected -> c.navigationSelected
            hovered -> c.navigationHover
            else -> Color.Transparent
        },
    )
    val content by animateColorAsState(
        when {
            selected -> c.actionPrimary
            hovered -> c.onNavigation
            else -> c.onNavigationMuted
        },
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(background)
            .hoverable(interactionSource)
            .clickable(onClick = onClick)
            .padding(end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(if (selected) c.actionPrimary else Color.Transparent),
        )
        Spacer(Modifier.width(9.dp))
        Icon(
            item.icon,
            contentDescription = if (collapsed) item.label else null,
            tint = content,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(11.dp))
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

@Composable
private fun SidebarFade(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable (enabled: Boolean) -> Unit,
) {
    val opacity by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (visible) SidebarUtilityFadeInMillis else SidebarUtilityFadeOutMillis,
        ),
        label = "sidebarUtilityOpacity",
    )
    val semanticsModifier = if (visible) {
        Modifier
    } else {
        Modifier.clearAndSetSemantics {}
    }

    Box(
        modifier = modifier
            .graphicsLayer { alpha = opacity }
            .then(semanticsModifier),
    ) {
        content(visible)
    }
}

internal enum class SidebarUtilityItem {
    Command,
    Status,
    Settings,
    Theme,
}

internal fun shouldShowSettingsPanel(requested: Boolean, initialLoading: Boolean): Boolean =
    requested && !initialLoading

internal fun sidebarCompactUtilityItemsAtStep(step: Int): List<SidebarUtilityItem> =
    SidebarUtilityItem.entries.take(step.coerceIn(0, SidebarUtilityItem.entries.size))

internal fun sidebarExpandedUtilityItemsAtStep(step: Int): List<SidebarUtilityItem> =
    listOf(
        SidebarUtilityItem.Status,
        SidebarUtilityItem.Settings,
        SidebarUtilityItem.Theme,
    ).filterIndexed { index, _ ->
        step >= SidebarRevealStatus + index
    }

internal fun sidebarRevealSteps(from: Int, to: Int): List<Int> = when {
    from < to -> ((from + 1)..to).toList()
    from > to -> ((from - 1) downTo to).toList()
    else -> emptyList()
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
private const val SidebarExpandedExitMillis = 120
private const val SidebarUtilityFadeInMillis = 120
private const val SidebarUtilityFadeOutMillis = 80
private const val SidebarRevealHeader = 1
private const val SidebarRevealFirstNav = 2
private const val SidebarRevealStatus = 6
private const val SidebarRevealSettings = 7
private const val SidebarRevealTheme = 8
private const val SidebarRevealAll = SidebarRevealTheme
private const val SidebarCompactRevealCommand = 1
private const val SidebarCompactRevealStatus = 2
private const val SidebarCompactRevealSettings = 3
private const val SidebarCompactRevealTheme = 4
private const val SidebarCompactRevealAll = SidebarCompactRevealTheme
