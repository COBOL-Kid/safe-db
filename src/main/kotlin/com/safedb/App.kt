package com.safedb

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
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
import com.safedb.query.sql.SqlParseResult
import com.safedb.secrets.SecretsManager
import com.safedb.service.SafeDbService
import com.safedb.ui.AppShell
import com.safedb.ui.ExploreWindowContent
import com.safedb.ui.LocalAppWindow
import com.safedb.ui.rememberSqlParseResult
import com.safedb.ui.theme.SafeDbTheme
import com.safedb.viewmodel.AppViewModel
import java.awt.Dimension
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent

private fun windowActivityListener(
    onChange: () -> Unit,
    onMainWindowClose: (() -> Unit)? = null,
): WindowAdapter =
    object : WindowAdapter() {
        override fun windowActivated(e: WindowEvent?) {
            onChange()
        }

        override fun windowDeactivated(e: WindowEvent?) {
            onChange()
        }

        override fun windowIconified(e: WindowEvent?) {
            onChange()
        }

        override fun windowClosing(e: WindowEvent?) {
            onMainWindowClose?.invoke()
        }

        override fun windowClosed(e: WindowEvent?) {
            onMainWindowClose?.invoke()
        }
    }

fun runApp(appState: AppState, service: SafeDbService) = application {
    val viewModel = remember(appState, service) { AppViewModel(service) }
    DisposableEffect(viewModel) { onDispose(viewModel::close) }

    val mainWindowRef = remember { mutableStateOf<java.awt.Window?>(null) }
    val exploreWindowRef = remember { mutableStateOf<java.awt.Window?>(null) }
    val republishWindowActivity = {
        viewModel.setWindowActive(
            mainWindowRef.value?.isActive == true || exploreWindowRef.value?.isActive == true,
        )
    }
    var exiting by remember { mutableStateOf(false) }
    val closeAll = {
        if (!exiting) {
            exiting = true
            viewModel.closeExplore()
            runCatching { SecretsManager.lockCredentials() }
            exitApplication()
        }
    }

    val settings by viewModel.settings.settings.collectAsState()
    val exploreViewModel by viewModel.explore.collectAsState()
    val pendingRecipeRun by viewModel.pendingRecipeRun.collectAsState()
    val connections by viewModel.connections.connections.collectAsState()
    val initialLoading by viewModel.initialLoading.collectAsState()
    val activeConnectionId by appState.activeConnectionId.collectAsState()
    val useDarkTheme = settings.isDark()
    val themePalette = settings.palette()
    // Derived on text so cursor/selection changes do not recompose the root App composable.
    val sqlText by
        remember(viewModel.sqlEditor) { derivedStateOf { viewModel.sqlEditor.text.text } }
    // Parsed here rather than in SqlScreen so an open Explore window keeps seeing the current
    // editor spec even while the user is on another route.
    val sqlParseResult =
        rememberSqlParseResult(
            sqlText = sqlText,
            connection = connections.firstOrNull { it.id == activeConnectionId },
            schemaViewModel = viewModel.schema,
        )
    val windowState =
        rememberWindowState(
            width = APP_DEFAULT_WINDOW_WIDTH_DP.dp,
            height = APP_DEFAULT_WINDOW_HEIGHT_DP.dp,
        )
    val exploreWindowState = rememberWindowState(width = 1120.dp, height = 760.dp)

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

    Window(
        onCloseRequest = closeAll,
        title = "Safe-DB",
        icon = rememberAppWindowIcon(),
        state = windowState,
    ) {
        LaunchedEffect(window) {
            window.minimumSize = Dimension(APP_MIN_WINDOW_WIDTH_PX, APP_MIN_WINDOW_HEIGHT_PX)
        }
        DisposableEffect(viewModel, window) {
            val mainWindow = window
            mainWindowRef.value = mainWindow
            republishWindowActivity()
            val listener =
                windowActivityListener(
                    onChange = republishWindowActivity,
                    onMainWindowClose = closeAll,
                )
            mainWindow.addWindowListener(listener)
            onDispose {
                mainWindow.removeWindowListener(listener)
                if (mainWindowRef.value === mainWindow) {
                    mainWindowRef.value = null
                }
            }
        }
        var paletteOpen by remember { mutableStateOf(false) }
        SafeDbTheme(isDark = useDarkTheme, palette = themePalette) {
            val bgColor = MaterialTheme.colorScheme.background
            SideEffect { window.background = java.awt.Color(bgColor.toArgb()) }
            CompositionLocalProvider(LocalAppWindow provides window) {
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
                        sqlParseResult = sqlParseResult,
                    )
                }
            }
        }
    }

    exploreViewModel?.let { explore ->
        val exploreOrigin by viewModel.exploreOrigin.collectAsState()
        val sqlSpec = (sqlParseResult as? SqlParseResult.Success)?.spec
        val originContext =
            viewModel.exploreOriginContext(
                activeConnectionId = activeConnectionId,
                sqlSpec = sqlSpec,
                connections = connections,
                origin = exploreOrigin,
            )
        Window(
            onCloseRequest = viewModel::closeExplore,
            title = "Explore - Safe-DB",
            icon = rememberAppWindowIcon(),
            state = exploreWindowState,
        ) {
            LaunchedEffect(window) { window.minimumSize = Dimension(920, 560) }
            LaunchedEffect(window, explore) {
                if (exploreWindowState.isMinimized) {
                    exploreWindowState.isMinimized = false
                    withFrameNanos {}
                }
                window.toFront()
                window.requestFocus()
            }
            DisposableEffect(viewModel, window) {
                val exploreWindow = window
                exploreWindowRef.value = exploreWindow
                republishWindowActivity()
                val listener = windowActivityListener(republishWindowActivity)
                exploreWindow.addWindowListener(listener)
                onDispose {
                    exploreWindow.removeWindowListener(listener)
                    if (exploreWindowRef.value === exploreWindow) {
                        exploreWindowRef.value = null
                    }
                    // Closing Explore while main is inactive must pause probes.
                    republishWindowActivity()
                }
            }
            CompositionLocalProvider(LocalAppWindow provides window) {
                SafeDbTheme(isDark = useDarkTheme, palette = themePalette) {
                    val exploreBgColor = MaterialTheme.colorScheme.background
                    SideEffect { window.background = java.awt.Color(exploreBgColor.toArgb()) }
                    Surface(color = MaterialTheme.colorScheme.background) {
                        ExploreWindowContent(
                            viewModel = explore,
                            currentSpec = originContext.currentSpec,
                            activeConnectionId = activeConnectionId,
                            latestSampleRunId = originContext.latestSampleRunId,
                            onClose = viewModel::closeExplore,
                            origin = exploreOrigin,
                            onRefreshSample =
                                if (originContext.sampleRefreshEnabled) {
                                    {
                                        viewModel.refreshActiveExploreSample(
                                            activeConnectionId = appState.activeConnectionId.value,
                                            sqlSpec = sqlSpec,
                                            connections = connections,
                                        )
                                    }
                                } else {
                                    null
                                },
                            recipesViewModel = viewModel.recipes,
                            connections = connections,
                            onRunRecipe = { recipe, connection ->
                                viewModel.runRecipeFromExplore(
                                    connection = connection,
                                    recipe = recipe,
                                    onSelectConnection = { selection ->
                                        appState.setActiveConnection(connection.id, selection)
                                    },
                                    onRestored = { appState.navigate(AppRoute.Builder) },
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

// Minimum window size is physical pixels passed to Dimension(...); tests render at density 1, so
// these values match the scene width/height they exercise.
internal const val APP_MIN_WINDOW_WIDTH_PX = 960
internal const val APP_MIN_WINDOW_HEIGHT_PX = 600
internal const val APP_DEFAULT_WINDOW_WIDTH_DP = 1280
internal const val APP_DEFAULT_WINDOW_HEIGHT_DP = 832
