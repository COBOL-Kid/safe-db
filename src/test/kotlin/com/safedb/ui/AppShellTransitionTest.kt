package com.safedb.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class AppShellTransitionTest {
    @Test
    fun `utility items keep the compact reveal order`() {
        assertEquals(
            listOf(
                SidebarUtilityItem.Command,
                SidebarUtilityItem.Status,
                SidebarUtilityItem.Settings,
                SidebarUtilityItem.Theme,
            ),
            SidebarUtilityItem.entries,
        )
    }

    @Test
    fun `compact controls reveal one at a time`() {
        assertEquals(emptyList(), sidebarCompactUtilityItemsAtStep(0))
        assertEquals(listOf(SidebarUtilityItem.Command), sidebarCompactUtilityItemsAtStep(1))
        assertEquals(
            listOf(SidebarUtilityItem.Command, SidebarUtilityItem.Status),
            sidebarCompactUtilityItemsAtStep(2),
        )
        assertEquals(
            listOf(
                SidebarUtilityItem.Command,
                SidebarUtilityItem.Status,
                SidebarUtilityItem.Settings,
                SidebarUtilityItem.Theme,
            ),
            sidebarCompactUtilityItemsAtStep(4),
        )
    }

    @Test
    fun `expanded controls reveal without the command field`() {
        assertEquals(emptyList(), sidebarExpandedUtilityItemsAtStep(7))
        assertEquals(listOf(SidebarUtilityItem.Status), sidebarExpandedUtilityItemsAtStep(8))
        assertEquals(
            listOf(
                SidebarUtilityItem.Status,
                SidebarUtilityItem.Settings,
                SidebarUtilityItem.Theme,
            ),
            sidebarExpandedUtilityItemsAtStep(10),
        )
    }

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
