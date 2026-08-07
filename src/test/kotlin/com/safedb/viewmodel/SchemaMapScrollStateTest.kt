package com.safedb.viewmodel

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SchemaMapScrollStateTest {
    @Test
    fun axisCentersContentWhenItFitsInsideThePaddedViewport() {
        val state =
            schemaMapAxisScrollState(
                contentStart = 100f,
                contentEnd = 500f,
                viewportSize = 1_000f,
                zoom = 1f,
                pan = 999f,
            )

        assertEquals(1_000f, state.contentSize)
        assertEquals(0f, state.maxScrollOffset)
        assertEquals(0f, state.scrollOffset)
        assertEquals(200f, state.constrainPan(999f))
    }

    @Test
    fun overflowingAxisConvertsBetweenPanAndScrollbarOffset() {
        val state =
            schemaMapAxisScrollState(
                contentStart = 100f,
                contentEnd = 1_100f,
                viewportSize = 500f,
                zoom = 1f,
                pan = -164f,
            )

        assertEquals(1_072f, state.contentSize)
        assertEquals(572f, state.maxScrollOffset)
        assertEquals(100f, state.scrollOffset)
        assertEquals(-464f, state.panForScrollOffset(400f))
        assertEquals(400f, state.scrollOffsetForPan(-464f))
    }

    @Test
    fun overflowingAxisClampsPanAtBothContentEdges() {
        val state =
            schemaMapAxisScrollState(
                contentStart = 100f,
                contentEnd = 1_100f,
                viewportSize = 500f,
                zoom = 1f,
                pan = 0f,
            )

        assertEquals(-64f, state.constrainPan(1_000f))
        assertEquals(-636f, state.constrainPan(-5_000f))
    }

    @Test
    fun axisRangeRecomputesForZoomAndChangedContentBounds() {
        val fitted =
            schemaMapAxisScrollState(
                contentStart = 0f,
                contentEnd = 400f,
                viewportSize = 500f,
                zoom = 1f,
                pan = 50f,
            )
        val zoomed =
            schemaMapAxisScrollState(
                contentStart = 0f,
                contentEnd = 400f,
                viewportSize = 500f,
                zoom = 2f,
                pan = fitted.constrainPan(50f),
            )
        val moved =
            schemaMapAxisScrollState(
                contentStart = -100f,
                contentEnd = 600f,
                viewportSize = 500f,
                zoom = 2f,
                pan = zoomed.constrainPan(50f),
            )

        assertEquals(0f, fitted.maxScrollOffset)
        assertEquals(372f, zoomed.maxScrollOffset)
        assertEquals(972f, moved.maxScrollOffset)
        assertEquals(200f, moved.scrollOffset)
    }

    @Test
    fun wheelAndTrackpadDeltasMoveBothAxesIndependently() {
        val horizontal = overflowState(pan = 36f)
        val vertical = overflowState(pan = -64f)

        val panned =
            schemaMapPanForScrollDelta(
                horizontal = horizontal,
                vertical = vertical,
                delta = Offset(2f, 3f),
                shiftPressed = false,
                pixelsPerScrollUnit = 10f,
            )
        val shifted =
            schemaMapPanForScrollDelta(
                horizontal = horizontal,
                vertical = vertical,
                delta = Offset(0f, 3f),
                shiftPressed = true,
                pixelsPerScrollUnit = 10f,
            )

        assertEquals(Offset(16f, -94f), panned)
        assertEquals(Offset(6f, -64f), shifted)
    }

    @Test
    fun consumedChildScrollDoesNotPanTheMap() {
        val horizontal = overflowState(pan = 36f)
        val vertical = overflowState(pan = -64f)

        val target =
            schemaMapPanForScrollEvent(
                horizontal = horizontal,
                vertical = vertical,
                delta = Offset(0f, 3f),
                shiftPressed = false,
                consumed = true,
            )

        assertNull(target)
    }

    private fun overflowState(pan: Float): SchemaMapAxisScrollState =
        schemaMapAxisScrollState(
            contentStart = 0f,
            contentEnd = 1_000f,
            viewportSize = 500f,
            zoom = 1f,
            pan = pan,
        )
}
