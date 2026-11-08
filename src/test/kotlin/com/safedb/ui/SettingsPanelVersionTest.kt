package com.safedb.ui

import com.safedb.loadAppVersion
import com.safedb.service.FakeSafeDbServiceSupport
import com.safedb.ui.theme.SafeDbTheme
import com.safedb.viewmodel.SettingsViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
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
        withComposeScene(
            width = 1000,
            height = 900,
            content = {
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
            },
        ) { scene ->
            scene.requireExact("Version ${loadAppVersion()}")
        }
    }
}
