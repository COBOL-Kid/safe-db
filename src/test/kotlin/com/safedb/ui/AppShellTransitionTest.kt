package com.safedb.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class AppShellTransitionTest {
    @Test
    fun `reverse transitions fade controls out in reverse reveal order`() {
        assertEquals(listOf(3, 2, 1, 0), sidebarRevealSteps(from = 4, to = 0))
        assertEquals(listOf(8, 7, 6, 5), sidebarRevealSteps(from = 9, to = 5))
    }

    @Test
    fun `interrupted transitions continue from their current step`() {
        assertEquals(listOf(3, 4), sidebarRevealSteps(from = 2, to = 4))
        assertEquals(listOf(1, 0), sidebarRevealSteps(from = 2, to = 0))
        assertEquals(emptyList(), sidebarRevealSteps(from = 4, to = 4))
    }

    @Test
    fun `settings requested during startup opens only after initial loading settles`() {
        assertEquals(false, shouldShowSettingsPanel(requested = false, initialLoading = true))
        assertEquals(false, shouldShowSettingsPanel(requested = true, initialLoading = true))
        assertEquals(true, shouldShowSettingsPanel(requested = true, initialLoading = false))
    }
}
