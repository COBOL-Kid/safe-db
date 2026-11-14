package com.safedb.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import com.safedb.AppRoute
import com.safedb.AppState
import com.safedb.service.FakeSafeDbServiceSupport
import com.safedb.ui.theme.SafeDbTheme
import com.safedb.viewmodel.AppViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class SqlScreenEmptyStateTest {
    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun sqlRouteWithoutConnectionShowsChooseDatabaseEmptyState() {
        val viewModel = AppViewModel(FakeSafeDbServiceSupport(), Dispatchers.Unconfined)
        val appState = AppState()
        try {
            appState.navigate(AppRoute.Sql)
            withComposeScene(
                width = 1000,
                height = 900,
                content = {
                    SafeDbTheme(isDark = false) {
                        AppShellContent(
                            appState = appState,
                            viewModel = viewModel,
                            paletteOpen = false,
                            onPaletteOpenChange = {},
                            sqlParseResult = null,
                            sidebarCollapsed = false,
                            onSidebarCollapsedChange = {},
                        )
                    }
                },
            ) { scene ->
                scene.settle()
                assertNotNull(scene.findExact("Choose a database to query"))
                assertNotNull(scene.findExact("Open Connections"))
                assertNull(
                    scene.findExact("Select a saved connection to write a SELECT against it."),
                )
                assertNull(
                    scene.findExact(
                        "Write a SELECT; it runs through the same limits and risk checks",
                    ),
                )
            }
        } finally {
            viewModel.close()
        }
    }
}
