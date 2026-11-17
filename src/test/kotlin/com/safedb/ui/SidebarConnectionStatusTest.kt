package com.safedb.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.safedb.AppRoute
import com.safedb.ui.theme.SafeDbTheme
import kotlin.test.Test
import kotlin.test.assertNull

class SidebarConnectionStatusTest {
    @Test
    fun `collapsed status light follows reachability`() {
        var connected by mutableStateOf(false)
        withComposeScene(
            width = 240,
            height = 620,
            content = {
                SafeDbTheme(isDark = false) {
                    Sidebar(
                        route = AppRoute.Home,
                        isDark = false,
                        collapsed = true,
                        connected = connected,
                        onCollapsedChange = {},
                        onNavigate = {},
                        onOpenSettings = {},
                        onOpenPalette = {},
                        onToggleTheme = {},
                    )
                }
            },
        ) { scene ->
            scene.requireExact("Not connected")
            assertNull(scene.findExact("Connected"))

            connected = true
            scene.settle()
            scene.requireExact("Connected")
            assertNull(scene.findExact("Not connected"))
        }
    }
}
