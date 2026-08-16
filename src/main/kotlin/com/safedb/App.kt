package com.safedb

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.safedb.model.FilterGroup
import com.safedb.model.QuerySpec
import com.safedb.query.sql.SqlParseResult
import com.safedb.secrets.SecretsManager
import com.safedb.service.SafeDbService
import com.safedb.ui.AppShell
import com.safedb.ui.ExploreWindowContent
import com.safedb.ui.parsedSqlSpec
import com.safedb.ui.theme.SafeDbTheme
import com.safedb.viewmodel.AppViewModel
import com.safedb.viewmodel.ExploreOrigin
import java.awt.Dimension
import kotlinx.coroutines.runBlocking

@Composable
fun App(appState: AppState, service: SafeDbService, mainWindow: java.awt.Window) {
    val viewModel = remember(appState, service) { AppViewModel(service) }
    DisposableEffect(viewModel) { onDispose(viewModel::close) }
    val settings by viewModel.settings.settings.collectAsState()
    val exploreViewModel by viewModel.explore.collectAsState()
    val pendingRecipeRun by viewModel.pendingRecipeRun.collectAsState()
    val connections by viewModel.connections.connections.collectAsState()
    val initialLoading by viewModel.initialLoading.collectAsState()
    val activeConnectionId by appState.activeConnectionId.collectAsState()
    val useDarkTheme = settings.theme == "dark"
    val themePalette = settings.palette()
    var paletteOpen by remember { mutableStateOf(false) }

    LaunchedEffect(initialLoading) {
        if (initialLoading) return@LaunchedEffect
        val defaultLocation = resolveDefaultQueryLocation(settings, connections)
        if (defaultLocation != null) {
            appState.activateDefaultConnection(defaultLocation.connectionId, defaultLocation.schema)
        } else {
            appState.clearDefaultConnection()
        }
    }

    LaunchedEffect(activeConnectionId) {
        viewModel.query.onActiveConnectionChanged(activeConnectionId)
        viewModel.sqlEditor.onActiveConnectionChanged(activeConnectionId)
    }

    LaunchedEffect(settings.queryRiskGate) {
        viewModel.query.onQueryRiskGateChanged(settings.queryRiskGate)
        viewModel.sqlEditor.onQueryRiskGateChanged(settings.queryRiskGate)
    }

    LaunchedEffect(
        pendingRecipeRun,
        viewModel.query.results,
        viewModel.query.running,
        viewModel.query.error,
        activeConnectionId,
    ) {
        viewModel.onQuerySettled(activeConnectionId, connections)
    }

    SafeDbTheme(isDark = useDarkTheme, palette = themePalette) {
        val bgColor = MaterialTheme.colorScheme.background
        SideEffect { mainWindow.background = java.awt.Color(bgColor.toArgb()) }
        Surface(
            modifier =
                Modifier.fillMaxSize().onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val shortcut =
                        event.key == Key.K && (event.isCtrlPressed || event.isMetaPressed)
                    if (shortcut) {
                        paletteOpen = true
                        true
                    } else {
                        false
                    }
                },
            color = MaterialTheme.colorScheme.background,
        ) {
            AppShell(
                appState = appState,
                viewModel = viewModel,
                paletteOpen = paletteOpen,
                onPaletteOpenChange = { paletteOpen = it },
            )
        }

        exploreViewModel?.let { explore ->
            val exploreWindowState = rememberWindowState(width = 1120.dp, height = 760.dp)
            val exploreOrigin by viewModel.exploreOrigin.collectAsState()
            // The session refreshes from whichever screen produced it, so staleness and the
            // refreshed sample must come from that screen's current state.
            val activeDialect = connections.firstOrNull { it.id == activeConnectionId }?.dialect
            val sqlSpec =
                if (exploreOrigin == ExploreOrigin.Sql) {
                    val sqlText = viewModel.sqlEditor.text.text
                    remember(
                        sqlText,
                        activeDialect,
                        viewModel.schema.schema,
                        viewModel.schema.selectedSchema,
                    ) {
                        (parsedSqlSpec(
                                sqlText,
                                activeDialect,
                                viewModel.schema.schema,
                                viewModel.schema.selectedSchema,
                            )
                                as? SqlParseResult.Success)
                            ?.spec
                    }
                } else {
                    null
                }
            val currentSpec =
                when (exploreOrigin) {
                    ExploreOrigin.Builder -> viewModel.query.spec
                    // An unparseable editor matches no executed query; hash as clearly stale.
                    ExploreOrigin.Sql ->
                        sqlSpec ?: QuerySpec(filters = FilterGroup(id = "g0"), limit = 0)
                }
            val originSample =
                when (exploreOrigin) {
                    ExploreOrigin.Builder -> viewModel.query.currentSample(activeConnectionId)
                    ExploreOrigin.Sql ->
                        viewModel.sqlEditor.currentSample(activeConnectionId, sqlSpec)
                }
            val sampleRefreshEnabled =
                activeConnectionId == explore.session.connectionId &&
                    originSample != null &&
                    connections.any { it.id == activeConnectionId }
            Window(
                onCloseRequest = viewModel::closeExplore,
                title = "Explore - Safe-DB",
                state = exploreWindowState,
            ) {
                LaunchedEffect(window) { window.minimumSize = Dimension(920, 560) }
                SafeDbTheme(isDark = useDarkTheme, palette = themePalette) {
                    val exploreBgColor = MaterialTheme.colorScheme.background
                    SideEffect { window.background = java.awt.Color(exploreBgColor.toArgb()) }
                    Surface(color = MaterialTheme.colorScheme.background) {
                        ExploreWindowContent(
                            viewModel = explore,
                            currentSpec = currentSpec,
                            onClose = viewModel::closeExplore,
                            origin = exploreOrigin,
                            onRefreshSample =
                                if (sampleRefreshEnabled) {
                                    refresh@{
                                        val originConnectionId = appState.activeConnectionId.value
                                        if (originConnectionId != explore.session.connectionId)
                                            return@refresh
                                        val connection = connections.firstOrNull { connection ->
                                            connection.id == originConnectionId
                                        }
                                        val latestSample =
                                            when (exploreOrigin) {
                                                ExploreOrigin.Builder ->
                                                    viewModel.query.currentSample(
                                                        originConnectionId
                                                    )
                                                ExploreOrigin.Sql ->
                                                    viewModel.sqlEditor.currentSample(
                                                        originConnectionId,
                                                        sqlSpec,
                                                    )
                                            }
                                        if (connection != null && latestSample != null) {
                                            viewModel.refreshExploreSample(
                                                connection,
                                                latestSample.spec,
                                                latestSample.result,
                                            )
                                        }
                                    }
                                } else {
                                    null
                                },
                            sampleRefreshEnabled = sampleRefreshEnabled,
                            recipesViewModel = viewModel.recipes,
                            connections = connections,
                            onRunRecipe = { recipe, connection ->
                                appState.setActiveConnection(
                                    connection.id,
                                    recipe.querySpec?.let(::resolveQuerySchemaSelection)
                                        ?: resolveConnectionSchemaSelection(
                                            connection.id,
                                            settings,
                                        ),
                                )
                                // runRecipe drives the builder query, so its confirmation dialog
                                // and
                                // errors only exist while Builder is composed. Launched from SQL
                                // the
                                // run would otherwise appear to hang on an invisible prompt.
                                if (recipe.querySpec != null) appState.navigate(AppRoute.Builder)
                                viewModel.closeExplore()
                                viewModel.runRecipe(connection, recipe)
                            },
                        )
                    }
                }
            }
        }
    }
}

fun runApp(appState: AppState, service: SafeDbService) = application {
    val windowState = rememberWindowState(width = 1280.dp, height = 832.dp)

    Window(
        onCloseRequest = {
            runBlocking { SecretsManager.lockCredentials() }
            exitApplication()
        },
        title = "Safe-DB",
        state = windowState,
    ) {
        LaunchedEffect(window) { window.minimumSize = Dimension(960, 600) }
        App(appState, service, window)
    }
}
