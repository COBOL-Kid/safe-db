package com.safedb

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import com.safedb.viewmodel.AppViewModel
import java.awt.Dimension
import kotlinx.coroutines.runBlocking

@Composable
fun App(appState: AppState) {
    val viewModel = remember(appState) { AppViewModel(appState.service) }
    val settings by viewModel.settings.settings.collectAsState()
    val useDarkTheme = when (settings.theme) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    var paletteOpen by remember { mutableStateOf(false) }

    MaterialTheme(
        colorScheme = if (useDarkTheme) darkColorScheme() else lightColorScheme(),
    ) {
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
        ) {
            com.safedb.ui.AppShell(
                appState = appState,
                viewModel = viewModel,
                paletteOpen = paletteOpen,
                onPaletteOpenChange = { paletteOpen = it },
            )
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
        App(appState)
    }
}
