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
import com.safedb.viewmodel.DatabaseReachability
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SidebarConnectionStatusTest {
    @Test
    fun `collapsed status light follows reachability`() {
        var reachability by mutableStateOf(DatabaseReachability.Disconnected)
        withSidebar(collapsed = true, reachability = { reachability }) { scene ->
            scene.requireExact("Not connected")
            assertNull(scene.findExact("Connected"))

            reachability = DatabaseReachability.Checking
            scene.settle()
            scene.requireExact("Checking connection…")
            assertNull(scene.findExact("Not connected"))

            reachability = DatabaseReachability.Connected
            scene.settle()
            scene.requireExact("Connected")
            assertNull(scene.findExact("Checking connection…"))
        }
    }

    @Test
    fun `status tooltip shows after a prior hover in both sidebar modes`() {
        listOf(true, false).forEach { collapsed ->
            var reachability by mutableStateOf(DatabaseReachability.Disconnected)
            withSidebar(collapsed = collapsed, reachability = { reachability }) { scene ->
                scene.hoverStatus()
                scene.assertStatusTooltipVisible("Not connected")
                scene.leaveStatus()
                scene.settle()

                reachability = DatabaseReachability.Checking
                scene.settle()
                scene.hoverStatus()
                scene.assertStatusTooltipVisible("Checking connection…")
                scene.leaveStatus()
                scene.settle()

                reachability = DatabaseReachability.Connected
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
    reachability: () -> DatabaseReachability,
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
                    reachability = reachability(),
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
