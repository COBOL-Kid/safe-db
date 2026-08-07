package com.safedb.viewmodel

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BuilderCanvasViewportTest {
    @Test
    fun zoomKeepsTheRequestedScreenAnchorStable() {
        val viewport = BuilderCanvasViewport()
        viewport.updatePan(Offset(-120f, -80f))
        val anchor = Offset(420f, 280f)
        val contentPointBefore = (anchor - viewport.pan) / viewport.zoom

        viewport.setZoom(1.6f, anchor)

        val contentPointAfter = (anchor - viewport.pan) / viewport.zoom
        assertEquals(contentPointBefore.x, contentPointAfter.x, 0.001f)
        assertEquals(contentPointBefore.y, contentPointAfter.y, 0.001f)
    }

    @Test
    fun zoomIsClampedToTheBuilderRange() {
        val viewport = BuilderCanvasViewport()

        viewport.setZoom(10f)
        assertEquals(BUILDER_CANVAS_MAX_ZOOM, viewport.zoom)

        viewport.setZoom(0f)
        assertEquals(BUILDER_CANVAS_MIN_ZOOM, viewport.zoom)
    }

    @Test
    fun fitKeepsContentBelowReservedControlsAndInsidePadding() {
        val viewport = BuilderCanvasViewport()
        val content = Rect(100f, 50f, 900f, 450f)
        val window = Size(1_000f, 700f)
        val reservedTop = 200f
        val padding = 40f

        viewport.fit(content, window, reservedTop, padding)

        val left = content.left * viewport.zoom + viewport.pan.x
        val top = content.top * viewport.zoom + viewport.pan.y
        val right = content.right * viewport.zoom + viewport.pan.x
        val bottom = content.bottom * viewport.zoom + viewport.pan.y
        assertTrue(left >= padding - 0.001f)
        assertTrue(top >= reservedTop + padding - 0.001f)
        assertTrue(right <= window.width - padding + 0.001f)
        assertTrue(bottom <= window.height - padding + 0.001f)
    }

    @Test
    fun resetRestoresTheDefaultViewWithoutTouchingContent() {
        val viewport = BuilderCanvasViewport()
        viewport.setZoom(1.5f, Offset(300f, 200f))
        viewport.updatePan(Offset(-80f, -40f))

        viewport.reset()

        assertEquals(1f, viewport.zoom)
        assertEquals(Offset.Zero, viewport.pan)
    }
}
