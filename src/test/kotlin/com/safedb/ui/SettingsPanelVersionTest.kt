package com.safedb.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import com.safedb.service.FakeSafeDbServiceSupport
import com.safedb.ui.theme.SafeDbTheme
import com.safedb.viewmodel.SettingsViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class SettingsPanelVersionTest {
    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun settingsMenuShowsThePackagedAppVersion() {
        val viewModel =
            SettingsViewModel(FakeSafeDbServiceSupport(), CoroutineScope(Dispatchers.Unconfined))
        ImageComposeScene(width = 1000, height = 900, density = Density(1f)) {
                SafeDbTheme(isDark = false) {
                    SettingsPanel(
                        open = true,
                        viewModel = viewModel,
                        connections = emptyList(),
                        onDefaultLocationChanged = { _, _ -> },
                        onDefaultLocationCleared = {},
                        onClose = {},
                    )
                }
            }
            .use { scene ->
                scene.render(0L)
                assertNotNull(scene.findExact("Version 1.0.1"))
            }
    }
}
