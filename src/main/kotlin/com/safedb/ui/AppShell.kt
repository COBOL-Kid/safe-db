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
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.WbSunny
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.safedb.AppRoute
import com.safedb.AppState
import com.safedb.SchemaSelectionIntent
import com.safedb.SchemaSelectionSource
import com.safedb.model.ConnectionDef
import com.safedb.model.QuerySpec
import com.safedb.model.Settings
import com.safedb.query.sql.SqlParseResult
import com.safedb.resolveConnectionSchemaSelection
import com.safedb.ui.components.CommandPalette
import com.safedb.ui.components.ConfirmDialog
import com.safedb.ui.theme.ChipShape
import com.safedb.ui.theme.SafeDbTheme
import com.safedb.viewmodel.AppViewModel
import com.safedb.viewmodel.ExploreOrigin
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay

private data class ConnectionSchemaHandlers(
    val onConnectionSelected: (ConnectionDef) -> Unit,
    val onSchemaSelected: (String) -> Unit,
    val onUnavailableSchemaSelection: (SchemaSelectionIntent) -> Unit,
    val onDismissSchemaHistoryError: () -> Unit,
)

private data class PendingConnectionSwitch(
    val target: ConnectionDef,
    val thenNavigate: AppRoute?,
)

private fun connectionSchemaHandlers(
    appState: AppState,
    viewModel: AppViewModel,
    settings: Settings,
    activeConnectionId: String?,
): ConnectionSchemaHandlers =
    ConnectionSchemaHandlers(
        onConnectionSelected = { connection ->
            appState.setActiveConnection(
                connection.id,
                resolveConnectionSchemaSelection(connection.id, settings),
            )
        },
        onSchemaSelected = { schema ->
            activeConnectionId?.let { connectionId ->
                appState.setActiveSchema(schema)
                viewModel.settings.rememberLastSchema(connectionId, schema)
            }
        },
        onUnavailableSchemaSelection = { selection ->
            activeConnectionId?.let { connectionId ->
                if (selection.source == SchemaSelectionSource.ConnectionHistory) {
                    viewModel.settings.forgetLastSchema(connectionId)
                }
            }
        },
        onDismissSchemaHistoryError = viewModel.settings::clearSchemaHistoryError,
    )

@Composable
fun AppShell(
    appState: AppState,
    viewModel: AppViewModel,
    paletteOpen: Boolean,
    onPaletteOpenChange: (Boolean) -> Unit,
    sqlParseResult: SqlParseResult?,
    modifier: Modifier = Modifier,
) {
    var sidebarCollapsed by rememberSaveable { mutableStateOf(false) }
    AppShellContent(
        appState = appState,
        viewModel = viewModel,
        paletteOpen = paletteOpen,
        onPaletteOpenChange = onPaletteOpenChange,
        sqlParseResult = sqlParseResult,
        sidebarCollapsed = sidebarCollapsed,
        onSidebarCollapsedChange = { sidebarCollapsed = it },
        modifier = modifier,
    )
}

@Composable
internal fun AppShellContent(
    appState: AppState,
    viewModel: AppViewModel,
    paletteOpen: Boolean,
    onPaletteOpenChange: (Boolean) -> Unit,
    sqlParseResult: SqlParseResult?,
    sidebarCollapsed: Boolean,
    onSidebarCollapsedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val route by appState.route.collectAsState()
    val settingsOpen by appState.settingsOpen.collectAsState()
    val initialLoading by viewModel.initialLoading.collectAsState()
    val activeConnectionId by appState.activeConnectionId.collectAsState()
    val schemaSelection by appState.schemaSelection.collectAsState()
    val settings by viewModel.settings.settings.collectAsState()
    val schemaHistoryError by viewModel.settings.schemaHistoryError.collectAsState()
    val connections by viewModel.connections.connections.collectAsState()
    val recipeApplyNotice by viewModel.recipeApplyNotice.collectAsState()
    val isDark = settings.theme == "dark"
    val activeConnection = connections.firstOrNull { it.id == activeConnectionId }
    val rawSchemaHandlers =
        remember(settings, activeConnectionId) {
            connectionSchemaHandlers(appState, viewModel, settings, activeConnectionId)
        }

    // Every route's connection picker goes through one confirm-and-clear path. A builder draft is
    // bound to the connection it was built against, so letting any other surface switch underneath
    // it would leave a canvas that can be run against a database it was never written for.
    var pendingConnectionSwitch by remember { mutableStateOf<PendingConnectionSwitch?>(null) }

    fun switchConnection(target: ConnectionDef, thenNavigate: AppRoute? = null) {
        viewModel.query.clear()
        viewModel.dismissRecipeApplyNotice()
        pendingConnectionSwitch = null
        rawSchemaHandlers.onConnectionSelected(target)
        thenNavigate?.let(appState::navigate)
    }

    fun selectConnection(target: ConnectionDef, thenNavigate: AppRoute? = null) {
        val plan =
            planConnectionSwitch(
                builderConnectionSwitchDecision(
                    activeConnectionId = activeConnectionId,
                    targetConnectionId = target.id,
                    hasDraft = viewModel.query.canvasTables.isNotEmpty(),
                ),
                thenNavigate,
            )
        when {
            plan.awaitConfirm ->
                pendingConnectionSwitch = PendingConnectionSwitch(target, plan.navigateOnCommit)
            plan.switchNow -> switchConnection(target, plan.navigateOnCommit)
            else -> plan.navigateOnCommit?.let(appState::navigate)
        }
    }

    val schemaHandlers =
        rawSchemaHandlers.copy(onConnectionSelected = { target -> selectConnection(target) })

    pendingConnectionSwitch?.let { pending ->
        ConfirmDialog(
            open = true,
            title = "Switch connection?",
            message =
                "Switching to ${pending.target.name} clears the current query canvas and results. " +
                    "Saved queries are not affected.",
            confirmLabel = "Switch and clear",
            onConfirm = { switchConnection(pending.target, pending.thenNavigate) },
            onCancel = { pendingConnectionSwitch = null },
        )
    }

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
        onConnectionSelected = { target ->
            selectConnection(target, thenNavigate = AppRoute.Builder)
        },
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
            onCollapsedChange = onSidebarCollapsedChange,
            onNavigate = appState::navigate,
            onOpenSettings = appState::openSettings,
            onOpenPalette = { onPaletteOpenChange(true) },
            onToggleTheme = viewModel.settings::toggleTheme,
        )

        Surface(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            color = SafeDbTheme.colors.workspaceBackground,
        ) {
            when (route) {
                AppRoute.Home ->
                    HomeScreen(
                        viewModel = viewModel,
                        onNavigate = appState::navigate,
                        onOpenSavedQuery = { savedQuery ->
                            restoreQuery(savedQuery.connectionId, savedQuery.spec)
                        },
                    )
                AppRoute.Connections ->
                    ConnectionsScreen(
                        viewModel = viewModel.connections,
                        onActivate = { id ->
                            val connection = connections.firstOrNull { it.id == id }
                            if (connection != null) {
                                selectConnection(connection, thenNavigate = AppRoute.Builder)
                            }
                        },
                        onDeleted = { id ->
                            appState.clearActiveConnectionIf(id)
                            viewModel.settings.clearDefaultIfConnection(id)
                        },
                        onConnectionChanged = { id ->
                            appState.clearActiveConnectionIf(id)
                            viewModel.schema.invalidateConnection(id)
                            viewModel.settings.clearSchemaSelectionsForConnection(id)
                        },
                        onSaved = viewModel.connections::refresh,
                    )
                AppRoute.Builder ->
                    BuilderScreen(
                        connection = activeConnection,
                        connections = connections,
                        queryViewModel = viewModel.query,
                        savedQueriesViewModel = viewModel.savedQueries,
                        recipesViewModel = viewModel.recipes,
                        schemaViewModel = viewModel.schema,
                        schemaSelection = schemaSelection,
                        schemaHistoryError = schemaHistoryError,
                        settings = settings,
                        sqlBusy = viewModel.sqlEditor.occupiesQuerySlot,
                        onConnectionSelected = schemaHandlers.onConnectionSelected,
                        onSchemaSelected = schemaHandlers.onSchemaSelected,
                        onUnavailableSchemaSelection = schemaHandlers.onUnavailableSchemaSelection,
                        onDismissSchemaHistoryError = schemaHandlers.onDismissSchemaHistoryError,
                        onOpenExplore = {
                            val sample = viewModel.query.currentSample(activeConnection?.id)
                            if (activeConnection != null && sample != null) {
                                viewModel.openExplore(activeConnection, sample.spec, sample.result)
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
                                    viewModel.openExploreRecipe(
                                        targetConnection,
                                        sample.spec,
                                        sample.result,
                                        recipe,
                                    )
                                }
                            }
                        },
                        recipeApplyNotice = recipeApplyNotice,
                        onDismissRecipeApplyNotice = viewModel::dismissRecipeApplyNotice,
                    )
                AppRoute.Sql ->
                    SqlScreen(
                        connection = activeConnection,
                        connections = connections,
                        sqlViewModel = viewModel.sqlEditor,
                        schemaViewModel = viewModel.schema,
                        schemaSelection = schemaSelection,
                        schemaHistoryError = schemaHistoryError,
                        settings = settings,
                        parseResult = sqlParseResult,
                        builderBusy = viewModel.query.occupiesQuerySlot,
                        onConnectionSelected = schemaHandlers.onConnectionSelected,
                        onSchemaSelected = schemaHandlers.onSchemaSelected,
                        onUnavailableSchemaSelection = schemaHandlers.onUnavailableSchemaSelection,
                        onDismissSchemaHistoryError = schemaHandlers.onDismissSchemaHistoryError,
                        onOpenExplore = { sample ->
                            val target = connections.firstOrNull { it.id == sample.connectionId }
                            if (target != null) {
                                viewModel.openExplore(
                                    target,
                                    sample.spec,
                                    sample.result,
                                    ExploreOrigin.Sql,
                                )
                            }
                        },
                        onOpenConnections = { appState.navigate(AppRoute.Connections) },
                    )
                AppRoute.Map ->
                    SchemaMapScreen(
                        connection = activeConnection,
                        connections = connections,
                        mapViewModel = viewModel.schemaMap,
                        schemaViewModel = viewModel.schema,
                        schemaSelection = schemaSelection,
                        schemaHistoryError = schemaHistoryError,
                        onConnectionSelected = schemaHandlers.onConnectionSelected,
                        onSchemaSelected = schemaHandlers.onSchemaSelected,
                        onUnavailableSchemaSelection = schemaHandlers.onUnavailableSchemaSelection,
                        onDismissSchemaHistoryError = schemaHandlers.onDismissSchemaHistoryError,
                        onRetry = viewModel.schema::clear,
                        onOpenConnections = { appState.navigate(AppRoute.Connections) },
                    )
                AppRoute.History ->
                    HistoryScreen(
                        viewModel = viewModel,
                        onRerun = { entry -> restoreQuery(entry.connectionId, entry.spec) },
                        onNavigate = appState::navigate,
                    )
            }
        }
    }
}

@Composable
internal fun Sidebar(
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
    val navItems =
        listOf(
            NavItem(AppRoute.Home, "Home", Icons.Outlined.Home),
            NavItem(AppRoute.Connections, "Connections", Icons.Outlined.Storage),
            NavItem(AppRoute.Builder, "Query Builder", Icons.Outlined.AccountTree),
            NavItem(AppRoute.Sql, "SQL", Icons.Outlined.Code),
            NavItem(AppRoute.Map, "Map", Icons.Outlined.Hub),
            NavItem(AppRoute.History, "History", Icons.Outlined.History),
        )
    // Keep the content mode stable while the rail changes width. This leaves a clean gap
    // between the outgoing and incoming fade sequences instead of cross-fading both layouts.
    var widthCollapsed by remember { mutableStateOf(collapsed) }
    var layoutCollapsed by remember { mutableStateOf(collapsed) }
    val sidebarWidth by
        animateDpAsState(
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
                    delay(SidebarRevealStaggerMillis.milliseconds)
                }
                if (hadExpandedContent) {
                    delay((SidebarExpandedExitMillis - SidebarRevealStaggerMillis).milliseconds)
                }

                val widthWasExpanded = !widthCollapsed
                widthCollapsed = true
                if (widthWasExpanded) {
                    delay(SidebarWidthAnimationMillis.milliseconds)
                }
                layoutCollapsed = true
            }

            for (step in sidebarRevealSteps(compactRevealStep, SidebarCompactRevealAll)) {
                compactRevealStep = step
                delay(SidebarRevealStaggerMillis.milliseconds)
            }
        } else {
            if (layoutCollapsed) {
                val hadCompactContent = compactRevealStep > 0
                for (step in sidebarRevealSteps(compactRevealStep, 0)) {
                    compactRevealStep = step
                    delay(SidebarRevealStaggerMillis.milliseconds)
                }
                if (hadCompactContent) {
                    delay((SidebarUtilityFadeOutMillis - SidebarRevealStaggerMillis).milliseconds)
                }
                layoutCollapsed = false
            }

            val widthWasCollapsed = widthCollapsed
            widthCollapsed = false
            if (widthWasCollapsed) {
                delay(SidebarWidthAnimationMillis.milliseconds)
            }

            for (step in sidebarRevealSteps(revealStep, SidebarRevealAll)) {
                revealStep = step
                delay(SidebarRevealStaggerMillis.milliseconds)
            }
        }
    }

    Row(modifier = Modifier.fillMaxHeight()) {
        Column(
            modifier =
                Modifier.width(sidebarWidth).fillMaxHeight().background(c.navigationBackground)
        ) {
            SidebarHeader(
                collapsed = layoutCollapsed,
                brandVisible = revealStep >= SidebarRevealHeader,
                onToggleCollapsed = { onCollapsedChange(!collapsed) },
            )

            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
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

        Box(modifier = Modifier.width(1.dp).fillMaxHeight().background(c.navigationBorder))
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
            modifier =
                Modifier.fillMaxWidth()
                    .height(SidebarHeaderHeight)
                    .clickable(onClick = onToggleCollapsed)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .semantics { contentDescription = "Expand sidebar" },
            contentAlignment = Alignment.Center,
        ) {
            LogoMark()
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = c.onNavigationMuted,
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 4.dp).size(14.dp),
            )
        }
    } else {
        Row(
            modifier =
                Modifier.fillMaxWidth().height(SidebarHeaderHeight).padding(horizontal = 16.dp),
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
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (collapsed) {
            SidebarFade(
                visible = compactRevealStep >= SidebarCompactRevealCommand,
                modifier = Modifier.size(32.dp),
            ) { enabled ->
                SidebarIconButton(
                    icon = Icons.Outlined.Search,
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
                    icon = Icons.Outlined.Settings,
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
                    icon = if (isDark) Icons.Outlined.WbSunny else Icons.Outlined.DarkMode,
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
                SidebarFade(visible = statusVisible, modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(start = 4.dp),
                    ) {
                        Box(
                            modifier =
                                Modifier.size(7.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(SafeDbTheme.colors.success)
                        )
                        // Single-line: during the width animation this slot can shrink to ~0
                        // wide, and wrapped text would grow tall enough to squeeze the last
                        // nav rows (Map/History icons visibly shift up).
                        Column {
                            Text(
                                "Safe Read Mode",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium,
                                color = c.onNavigation,
                                maxLines = 1,
                                overflow = TextOverflow.Clip,
                            )
                            Text(
                                "No-lock · Indexed joins",
                                style = MaterialTheme.typography.labelSmall,
                                color = c.onNavigationMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Clip,
                            )
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    SidebarFade(visible = settingsVisible, modifier = Modifier.size(32.dp)) {
                        enabled ->
                        SidebarIconButton(
                            icon = Icons.Outlined.Settings,
                            contentDescription = "Settings",
                            enabled = enabled,
                            onClick = onOpenSettings,
                        )
                    }
                    SidebarFade(visible = themeVisible, modifier = Modifier.size(32.dp)) { enabled
                        ->
                        SidebarIconButton(
                            icon = if (isDark) Icons.Outlined.WbSunny else Icons.Outlined.DarkMode,
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
        modifier = Modifier.size(34.dp).clip(ChipShape).background(c.actionPrimary),
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
        modifier =
            Modifier.size(32.dp).clip(ChipShape).semantics {
                contentDescription = "Safe Read Mode"
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(modifier = Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(c.success))
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
    val background by
        animateColorAsState(if (enabled && hovered) c.navigationHover else Color.Transparent)

    Box(
        modifier =
            Modifier.size(32.dp)
                .clip(ChipShape)
                .background(background)
                .hoverable(interactionSource, enabled = enabled)
                .clickable(enabled = enabled, onClick = onClick)
                .then(if (enabled) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier),
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
    val background by
        animateColorAsState(
            when {
                selected -> c.navigationSelected
                hovered -> c.navigationHover
                else -> Color.Transparent
            }
        )
    val content by
        animateColorAsState(
            when {
                selected -> c.actionPrimary
                hovered -> c.onNavigation
                else -> c.onNavigationMuted
            }
        )

    // Fixed-width rail plus a full-size fade overlay so label show/hide cannot move icons.
    Box(
        modifier =
            Modifier.fillMaxWidth()
                .height(44.dp)
                .background(background)
                .hoverable(interactionSource)
                .clickable(onClick = onClick)
                .pointerHoverIcon(PointerIcon.Hand)
    ) {
        Row(
            modifier =
                Modifier.align(Alignment.CenterStart)
                    .width(navButtonLabelStartOffset())
                    .fillMaxHeight(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start,
        ) {
            Box(
                modifier =
                    Modifier.width(NavRailIndicatorWidth)
                        .fillMaxHeight()
                        .background(if (selected) c.actionPrimary else Color.Transparent)
            )
            Spacer(Modifier.width(NavRailIconStartGap))
            Icon(
                item.icon,
                contentDescription = if (collapsed) item.label else null,
                tint = content,
                modifier = Modifier.size(NavRailIconSize),
            )
        }
        Box(
            modifier =
                Modifier.align(Alignment.CenterStart)
                    .fillMaxSize()
                    .padding(start = navButtonLabelStartOffset(), end = 12.dp)
                    .clipToBounds(),
            contentAlignment = Alignment.CenterStart,
        ) {
            SidebarFade(visible = labelVisible) {
                Text(
                    item.label,
                    color = content,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                )
            }
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
    val opacity by
        animateFloatAsState(
            targetValue = if (visible) 1f else 0f,
            animationSpec =
                tween(
                    durationMillis =
                        if (visible) SidebarUtilityFadeInMillis else SidebarUtilityFadeOutMillis
                ),
            label = "sidebarUtilityOpacity",
        )
    val semanticsModifier =
        if (visible) {
            Modifier
        } else {
            Modifier.clearAndSetSemantics {}
        }

    Box(modifier = modifier.graphicsLayer { alpha = opacity }.then(semanticsModifier)) {
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
    listOf(SidebarUtilityItem.Status, SidebarUtilityItem.Settings, SidebarUtilityItem.Theme)
        .filterIndexed { index, _ -> step >= SidebarRevealStatus + index }

internal fun sidebarRevealSteps(from: Int, to: Int): List<Int> =
    when {
        from < to -> ((from + 1)..to).toList()
        from > to -> ((from - 1) downTo to).toList()
        else -> emptyList()
    }

private data class NavItem(val route: AppRoute, val label: String, val icon: ImageVector)

private val ExpandedSidebarWidth = 232.dp
private val CollapsedSidebarWidth = 72.dp
private val SidebarHeaderHeight = 78.dp
private val NavRailIndicatorWidth = 3.dp
private val NavRailIconStartGap = 9.dp
private val NavRailIconSize = 18.dp
private val NavRailLabelGap = 11.dp

internal fun navButtonIconStartOffset(): Dp = NavRailIndicatorWidth + NavRailIconStartGap

internal fun navButtonLabelStartOffset(): Dp =
    NavRailIndicatorWidth + NavRailIconStartGap + NavRailIconSize + NavRailLabelGap

private const val SidebarWidthAnimationMillis = 240
private const val SidebarRevealStaggerMillis = 55
private const val SidebarExpandedExitMillis = 120
private const val SidebarUtilityFadeInMillis = 120
private const val SidebarUtilityFadeOutMillis = 80
private const val SidebarRevealHeader = 1
private const val SidebarRevealFirstNav = 2
private const val SidebarRevealStatus = 8
private const val SidebarRevealSettings = 9
private const val SidebarRevealTheme = 10
private const val SidebarRevealAll = SidebarRevealTheme
private const val SidebarCompactRevealCommand = 1
private const val SidebarCompactRevealStatus = 2
private const val SidebarCompactRevealSettings = 3
private const val SidebarCompactRevealTheme = 4
private const val SidebarCompactRevealAll = SidebarCompactRevealTheme
