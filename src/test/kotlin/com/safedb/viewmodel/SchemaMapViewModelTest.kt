package com.safedb.viewmodel

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SchemaMapViewModelTest {
    @Test
    fun expansionAndOffsetsAreIsolatedByConnectionAndSchema() {
        val viewModel = SchemaMapViewModel()

        viewModel.activate("c1", "public")
        viewModel.toggleExpanded("public.orders")
        viewModel.moveNode("public.orders", Offset(12f, -4f))

        viewModel.activate("c1", "reporting")
        assertFalse(viewModel.isExpanded("public.orders"))
        assertEquals(Offset.Zero, viewModel.offsetFor("public.orders"))

        viewModel.activate("c1", "public")
        assertTrue(viewModel.isExpanded("public.orders"))
        assertEquals(Offset(12f, -4f), viewModel.offsetFor("public.orders"))
    }

    @Test
    fun zoomKeepsTheRequestedScreenAnchorStable() {
        val viewModel = SchemaMapViewModel()
        viewModel.activate("c1", "public")
        viewModel.updatePan(viewModel.pan + Offset(64f, 24f))
        val anchor = Offset(400f, 300f)
        val canvasPointBefore = (anchor - viewModel.pan) / viewModel.zoom

        viewModel.setZoom(1.5f, anchor)

        assertEquals(canvasPointBefore.x, ((anchor - viewModel.pan) / viewModel.zoom).x, 0.001f)
        assertEquals(canvasPointBefore.y, ((anchor - viewModel.pan) / viewModel.zoom).y, 0.001f)
    }

    @Test
    fun zoomIsClampedToSupportedRange() {
        val viewModel = SchemaMapViewModel()

        viewModel.setZoom(10f)
        assertEquals(CANVAS_MAX_ZOOM, viewModel.zoom)

        viewModel.setZoom(0.01f)
        assertEquals(CANVAS_MIN_ZOOM, viewModel.zoom)
    }

    @Test
    fun fitCentersContentInsideTheViewport() {
        val viewModel = SchemaMapViewModel()
        val bounds = Rect(100f, 50f, 900f, 450f)
        val viewport = Size(1_000f, 600f)

        viewModel.fit(bounds, viewport, padding = 40f)

        val displayedCenter = bounds.center * viewModel.zoom + viewModel.pan
        assertEquals(viewport.width / 2f, displayedCenter.x, 0.001f)
        assertEquals(viewport.height / 2f, displayedCenter.y, 0.001f)
    }

    @Test
    fun fitKeepsEveryEdgeOfALargeSchemaInsideThePaddedViewport() {
        val viewModel = SchemaMapViewModel()
        val bounds = Rect(-200f, 100f, 7_800f, 4_100f)
        val viewport = Size(1_000f, 600f)
        val padding = 40f

        viewModel.fit(bounds, viewport, padding)

        val displayedLeft = bounds.left * viewModel.zoom + viewModel.pan.x
        val displayedTop = bounds.top * viewModel.zoom + viewModel.pan.y
        val displayedRight = bounds.right * viewModel.zoom + viewModel.pan.x
        val displayedBottom = bounds.bottom * viewModel.zoom + viewModel.pan.y
        assertTrue(viewModel.zoom < 0.4f)
        assertTrue(displayedLeft >= padding - 0.001f)
        assertTrue(displayedTop >= padding - 0.001f)
        assertTrue(displayedRight <= viewport.width - padding + 0.001f)
        assertTrue(displayedBottom <= viewport.height - padding + 0.001f)
    }

    @Test
    fun scrollDrivenPanUpdateCanChangeOneAxisWithoutMovingTheOther() {
        val viewModel = SchemaMapViewModel()
        viewModel.activate("c1", "public")

        viewModel.updatePan(Offset(-240f, viewModel.pan.y))

        assertEquals(Offset(-240f, CANVAS_DEFAULT_PADDING), viewModel.pan)
    }

    @Test
    fun resetClearsOnlyTheActiveContextsManualLayout() {
        val viewModel = SchemaMapViewModel()
        viewModel.activate("c1", "public")
        viewModel.moveNode("public.orders", Offset(8f, 9f))
        viewModel.setZoom(1.5f)

        viewModel.resetLayout()

        assertEquals(Offset.Zero, viewModel.offsetFor("public.orders"))
        assertEquals(1f, viewModel.zoom)
        assertEquals(Offset(CANVAS_DEFAULT_PADDING, CANVAS_DEFAULT_PADDING), viewModel.pan)
    }

    @Test
    fun initialFitIsRequestedOncePerContextActivation() {
        val viewModel = SchemaMapViewModel()

        viewModel.activate("c1", "public")
        assertTrue(viewModel.consumeInitialFitRequest("c1", "public"))
        assertFalse(viewModel.consumeInitialFitRequest("c1", "public"))

        viewModel.updatePan(Offset(-120f, -80f))
        viewModel.setZoom(1.4f)
        viewModel.activate("c1", "public")
        assertFalse(viewModel.consumeInitialFitRequest("c1", "public"))
        assertEquals(1.4f, viewModel.zoom)

        viewModel.activate("c1", "reporting")
        assertFalse(viewModel.consumeInitialFitRequest("c1", "public"))
        assertTrue(viewModel.consumeInitialFitRequest("c1", "reporting"))
    }
}
