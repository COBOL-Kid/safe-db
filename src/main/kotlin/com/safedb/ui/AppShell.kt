package com.safedb.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.safedb.AppRoute
import com.safedb.AppState
import com.safedb.SchemaSelectionIntent
import com.safedb.SchemaSelectionSource
import com.safedb.model.ConnectionDef
import com.safedb.model.QuerySpec
import com.safedb.model.Settings
import com.safedb.query.sql.SqlParseResult
import com.safedb.resolveConnectionSchemaSelection
import com.safedb.resolveQuerySchemaSelection
import com.safedb.ui.components.CommandPalette
import com.safedb.ui.components.ConfirmDialog
import com.safedb.ui.theme.SafeDbTheme
import com.safedb.viewmodel.AppViewModel
import com.safedb.viewmodel.ExploreOrigin

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
    val openNewConnectionForm by appState.openNewConnectionForm.collectAsState()
    val schemaSelection by appState.schemaSelection.collectAsState()
    val settings by viewModel.settings.settings.collectAsState()
    val schemaHistoryError by viewModel.settings.schemaHistoryError.collectAsState()
    val connections by viewModel.connections.connections.collectAsState()
    val recipeApplyNotice by viewModel.recipeApplyNotice.collectAsState()
    val isDark = settings.isDark()
    val activeConnection = connections.firstOrNull { it.id == activeConnectionId }
    val databaseReachability by viewModel.databaseReachability.collectAsState()
    // Keyed on id plus credentials so a list reload of the same connection does not re-probe.
    LaunchedEffect(activeConnection?.id, activeConnection?.credentialFingerprint()) {
        viewModel.refreshDatabaseReachability(activeConnection)
    }
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

    ConnectionSwitchConfirmDialog(
        pending = pendingConnectionSwitch,
        onConfirm = { switch -> switchConnection(switch.target, switch.thenNavigate) },
        onCancel = { pendingConnectionSwitch = null },
    )

    fun restoreQuery(connectionId: String, spec: QuerySpec) {
        val restoredSelection = resolveQuerySchemaSelection(spec)
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
            reachability = databaseReachability,
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
                        onNewConnection = appState::requestNewConnectionForm,
                        onOpenSavedQuery = { savedQuery ->
                            restoreQuery(savedQuery.connectionId, savedQuery.spec)
                        },
                    )
                AppRoute.Connections ->
                    ConnectionsScreen(
                        viewModel = viewModel.connections,
                        openNewConnectionForm = openNewConnectionForm,
                        onOpenNewConnectionFormConsumed = appState::consumeNewConnectionFormRequest,
                        activeConnectionId = activeConnectionId,
                        defaultConnectionId = settings.defaultConnectionId,
                        onDeleted = { id ->
                            appState.clearActiveConnectionIf(id)
                            viewModel.settings.clearDefaultIfConnection(id)
                        },
                        onConnectionChanged = { id ->
                            appState.clearActiveConnectionIf(id)
                            viewModel.schema.invalidateConnection(id)
                            viewModel.settings.clearSchemaSelectionsForConnection(id)
                        },
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
                                viewModel.openExplore(
                                    activeConnection,
                                    sample.spec,
                                    sample.result,
                                    sampleRunId = sample.runId,
                                )
                            }
                        },
                        onOpenSettings = appState::openSettings,
                        onApplyRecipe = { recipe, targetConnection ->
                            val querySpec = recipe.querySpec
                            if (querySpec != null) {
                                appState.setActiveConnection(
                                    targetConnection.id,
                                    resolveQuerySchemaSelection(querySpec),
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
                                        sampleRunId = sample.runId,
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
                                    sampleRunId = sample.runId,
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

internal fun shouldShowSettingsPanel(requested: Boolean, initialLoading: Boolean): Boolean =
    requested && !initialLoading

internal data class ConnectionSchemaHandlers(
    val onConnectionSelected: (ConnectionDef) -> Unit,
    val onSchemaSelected: (String) -> Unit,
    val onUnavailableSchemaSelection: (SchemaSelectionIntent) -> Unit,
    val onDismissSchemaHistoryError: () -> Unit,
)

internal data class PendingConnectionSwitch(
    val target: ConnectionDef,
    val thenNavigate: AppRoute?,
)

internal fun connectionSchemaHandlers(
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
internal fun ConnectionSwitchConfirmDialog(
    pending: PendingConnectionSwitch?,
    onConfirm: (PendingConnectionSwitch) -> Unit,
    onCancel: () -> Unit,
) {
    pending?.let { switch ->
        ConfirmDialog(
            open = true,
            title = "Switch connection?",
            message =
                "Switching to ${switch.target.name} clears the current query canvas and results. " +
                    "Saved queries are not affected.",
            confirmLabel = "Switch and clear",
            onConfirm = { onConfirm(switch) },
            onCancel = onCancel,
        )
    }
}
