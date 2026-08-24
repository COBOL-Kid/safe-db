package com.safedb.ui

import com.safedb.ui.components.shouldShowMenuScrollbar
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MenuScrollbarTest {
    @Test
    fun hidesScrollbarBeforeLayoutWhenMaxValueIsUnbounded() {
        assertFalse(shouldShowMenuScrollbar(viewportHeightPx = 0, scrollMaxValue = Int.MAX_VALUE))
        assertFalse(shouldShowMenuScrollbar(viewportHeightPx = 120, scrollMaxValue = Int.MAX_VALUE))
    }

    @Test
    fun hidesScrollbarWhenContentFits() {
        assertFalse(shouldShowMenuScrollbar(viewportHeightPx = 120, scrollMaxValue = 0))
    }

    @Test
    fun showsScrollbarWhenContentOverflows() {
        assertTrue(shouldShowMenuScrollbar(viewportHeightPx = 240, scrollMaxValue = 80))
    }
}
