package com.safedb.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class BuilderScreenStateTest {
    @Test
    fun downArrowRestoresMaximizedResultsPaneToMinimumHeight() {
        val state = toggleResultsPane(ResultsPaneMode.Maximized, height = 240f)

        assertEquals(ResultsPaneMode.Normal, state.mode)
        assertEquals(128f, state.height)
    }

    @Test
    fun upArrowMaximizesResultsPaneWithoutChangingItsResizeHeight() {
        val state = toggleResultsPane(ResultsPaneMode.Normal, height = 320f)

        assertEquals(ResultsPaneMode.Maximized, state.mode)
        assertEquals(320f, state.height)
    }
}
