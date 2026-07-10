package com.safedb

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
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
import com.safedb.secrets.SecretsManager
import com.safedb.ui.AppShell
import com.safedb.ui.ExploreWindowContent
import com.safedb.ui.theme.SafeDbTheme
import com.safedb.viewmodel.AppViewModel
import java.awt.Dimension
import kotlinx.coroutines.runBlocking

@Composable
fun App(appState: AppState, window: java.awt.Window) {
    val viewModel = remember(appState) { AppViewModel(appState.service) }
    val settings by viewModel.settings.settings.collectAsState()
    val exploreViewModel by viewModel.explore.collectAsState()
    val useDarkTheme = settings.theme == "dark"
    var paletteOpen by remember { mutableStateOf(false) }

    SafeDbTheme(
        isDark = useDarkTheme,
    ) {
        val bgColor = MaterialTheme.colorScheme.background
        SideEffect {
            window.background = java.awt.Color(bgColor.toArgb())
        }
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val shortcut = event.key == Key.K && (event.isCtrlPressed || event.isMetaPressed)
                    if (shortcut) {
                        paletteOpen = true
                        true
                    } else {
                        false
                    }
                },
            color = MaterialTheme.colorScheme.background,
        ) {
            com.safedb.ui.AppShell(
                appState = appState,
                viewModel = viewModel,
                paletteOpen = paletteOpen,
                onPaletteOpenChange = { paletteOpen = it },
            )
        }

        exploreViewModel?.let { explore ->
            val exploreWindowState = rememberWindowState(width = 1120.dp, height = 760.dp)
            Window(
                onCloseRequest = viewModel::closeExplore,
                title = "Explore - safe-db",
                state = exploreWindowState,
            ) {
                LaunchedEffect(window) {
                    window.minimumSize = Dimension(920, 560)
                }
                SafeDbTheme(isDark = useDarkTheme) {
                    val exploreBgColor = MaterialTheme.colorScheme.background
                    SideEffect {
                        window.background = java.awt.Color(exploreBgColor.toArgb())
                    }
                    Surface(color = MaterialTheme.colorScheme.background) {
                        ExploreWindowContent(
                            viewModel = explore,
                            currentSpec = viewModel.query.spec,
                            onClose = viewModel::closeExplore,
                        )
                    }
                }
            }
        }
    }
}

fun runApp(appState: AppState) = application {
    val windowState = rememberWindowState(width = 1280.dp, height = 832.dp)

    Window(
        onCloseRequest = {
            runBlocking { SecretsManager.lockCredentials() }
            exitApplication()
        },
        title = "safe-db",
        state = windowState,
    ) {
        LaunchedEffect(window) {
            window.minimumSize = Dimension(960, 600)
        }
        App(appState, window)
    }
}
