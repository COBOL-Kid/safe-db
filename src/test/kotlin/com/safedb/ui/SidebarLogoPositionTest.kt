package com.safedb.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import com.safedb.AppRoute
import com.safedb.ui.theme.SafeDbTheme
import com.safedb.viewmodel.DatabaseReachability
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher

@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class SidebarLogoPositionTest {
    @Test
    fun `logo stays on the icon axis while the sidebar opens and closes`() {
        val dispatcher = StandardTestDispatcher()
        var collapsed by mutableStateOf(true)
        ImageComposeScene(
                width = 320,
                height = 420,
                density = Density(1f),
                coroutineContext = dispatcher,
            ) {
                SafeDbTheme(isDark = false) {
                    Sidebar(
                        route = AppRoute.Home,
                        isDark = false,
                        collapsed = collapsed,
                        reachability = DatabaseReachability.Disconnected,
                        onCollapsedChange = { collapsed = it },
                        onNavigate = {},
                        onOpenSettings = {},
                        onOpenPalette = {},
                        onToggleTheme = {},
                    )
                }
            }
            .use { scene ->
                var frameTimeNs = 0L

                fun stepFrame(): SidebarBoundsSample {
                    dispatcher.scheduler.advanceTimeBy(16)
                    dispatcher.scheduler.runCurrent()
                    frameTimeNs += 16_000_000L
                    scene.render(frameTimeNs).close()
                    val logo =
                        scene.requireByTag(SIDEBAR_LOGO_TAG, useUnmergedTree = true).boundsInRoot
                    val rail =
                        scene.requireByTag(SIDEBAR_RAIL_TAG, useUnmergedTree = true).boundsInRoot
                    return SidebarBoundsSample(logo.left, logo.top, rail.width)
                }

                val samples = mutableListOf<SidebarBoundsSample>()
                repeat(3) { samples += stepFrame() }
                val collapsedWidth = samples.last().railWidth
                assertTrue(
                    collapsedWidth in 70f..76f,
                    "collapsed rail width was $collapsedWidth",
                )

                collapsed = false
                repeat(45) { samples += stepFrame() }
                assertTrue(
                    samples.last().railWidth >= 230f,
                    "sidebar did not finish opening; last rail width ${samples.last().railWidth}",
                )

                collapsed = true
                repeat(60) { samples += stepFrame() }
                assertEquals(
                    collapsedWidth,
                    samples.last().railWidth,
                    0.5f,
                    "sidebar did not finish closing",
                )

                val logoPositions = samples.map { it.logoLeft to it.logoTop }.distinct()
                assertEquals(
                    1,
                    logoPositions.size,
                    "logo moved across ${samples.size} frames: $logoPositions",
                )
            }
    }
}

private data class SidebarBoundsSample(
    val logoLeft: Float,
    val logoTop: Float,
    val railWidth: Float,
)
