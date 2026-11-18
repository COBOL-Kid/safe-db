package com.safedb.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import com.safedb.AppRoute
import com.safedb.ui.theme.SafeDbTheme
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SidebarConnectionStatusTest {
    @Test
    fun `collapsed status light follows reachability`() {
        var connected by mutableStateOf(false)
        withSidebar(collapsed = true, connected = { connected }) { scene ->
            scene.requireExact("Not connected")
            assertNull(scene.findExact("Connected"))

            connected = true
            scene.settle()
            scene.requireExact("Connected")
            assertNull(scene.findExact("Not connected"))
        }
    }

    @Test
    fun `status tooltip shows after a prior hover in both sidebar modes`() {
        listOf(true, false).forEach { collapsed ->
            var connected by mutableStateOf(false)
            withSidebar(collapsed = collapsed, connected = { connected }) { scene ->
                scene.hoverStatus()
                scene.assertStatusTooltipVisible("Not connected")
                scene.leaveStatus()
                scene.settle()

                connected = true
                scene.settle()
                scene.hoverStatus()
                scene.assertStatusTooltipVisible("Connected")
                assertNull(scene.findExact("Not connected"))
            }
        }
    }
}

private fun withSidebar(
    collapsed: Boolean,
    connected: () -> Boolean,
    body: (ImageComposeScene) -> Unit,
) {
    withComposeScene(
        width = 240,
        height = 620,
        content = {
            SafeDbTheme(isDark = false) {
                Sidebar(
                    route = AppRoute.Home,
                    isDark = false,
                    collapsed = collapsed,
                    connected = connected(),
                    onCollapsedChange = {},
                    onNavigate = {},
                    onOpenSettings = {},
                    onOpenPalette = {},
                    onToggleTheme = {},
                )
            }
        },
        body = body,
    )
}

@OptIn(ExperimentalComposeUiApi::class)
private fun ImageComposeScene.hoverStatus() {
    val anchor = requireByTag(SIDEBAR_STATUS_TAG)
    sendPointerEvent(PointerEventType.Move, Offset(4f, 4f))
    sendPointerEvent(PointerEventType.Enter, Offset(4f, 4f))
    settle()
    sendPointerEvent(PointerEventType.Move, anchor.boundsInRoot.center)
    sendPointerEvent(PointerEventType.Enter, anchor.boundsInRoot.center)
    settle()
    settle()
}

@OptIn(ExperimentalComposeUiApi::class)
private fun ImageComposeScene.leaveStatus() {
    sendPointerEvent(PointerEventType.Exit, Offset(4f, 4f))
    sendPointerEvent(PointerEventType.Move, Offset(4f, 4f))
    settle()
}

private fun ImageComposeScene.assertStatusTooltipVisible(label: String) {
    val matches = nodesLabeled(label)
    assertTrue(
        matches.size >= 2,
        "status tooltip '$label' missing after hover: ${
            allSemanticsNodes().map { it.label() }.filter { it.isNotEmpty() }
        }",
    )
}
