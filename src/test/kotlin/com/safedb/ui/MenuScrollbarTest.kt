package com.safedb.ui

import com.safedb.ui.components.menuScrollbarHeightPx
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MenuScrollbarTest {
    @Test
    fun hidesScrollbarBeforeLayoutWhenMaxValueIsUnbounded() {
        assertNull(menuScrollbarHeightPx(viewportHeightPx = 0, scrollMaxValue = Int.MAX_VALUE))
        assertNull(menuScrollbarHeightPx(viewportHeightPx = 120, scrollMaxValue = Int.MAX_VALUE))
    }

    @Test
    fun hidesScrollbarWhenContentFits() {
        assertNull(menuScrollbarHeightPx(viewportHeightPx = 120, scrollMaxValue = 0))
    }

    @Test
    fun usesMeasuredViewportHeightWhenContentOverflows() {
        assertEquals(240, menuScrollbarHeightPx(viewportHeightPx = 240, scrollMaxValue = 80))
    }
}
