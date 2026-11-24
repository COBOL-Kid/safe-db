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
    fun delegatesZoomAndFitToViewportState() {
        val viewModel = SchemaMapViewModel()
        viewModel.activate("c1", "public")

        viewModel.setZoom(10f)
        assertEquals(CANVAS_MAX_ZOOM, viewModel.zoom)

        val bounds = Rect(100f, 50f, 900f, 450f)
        val viewport = Size(1_000f, 600f)
        viewModel.fit(bounds, viewport, padding = 40f)
        val displayedCenter = bounds.center * viewModel.zoom + viewModel.pan
        assertEquals(viewport.width / 2f, displayedCenter.x, 0.001f)
        assertEquals(viewport.height / 2f, displayedCenter.y, 0.001f)
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

    @Test
    fun focusCentersNodeInViewportAndIgnoresNonPositiveViewport() {
        val viewModel = SchemaMapViewModel()
        viewModel.activate("c1", "public")
        val initialPan = viewModel.pan

        viewModel.focus(Rect(100f, 50f, 300f, 150f), Size(0f, 500f))
        assertEquals(initialPan, viewModel.pan)

        viewModel.setZoom(2f)
        viewModel.focus(Rect(100f, 50f, 300f, 150f), Size(800f, 600f))
        // center=(200,100) at zoom=2 -> pan=(400 - 400, 300 - 200) = (0, 100)
        assertEquals(Offset(0f, 100f), viewModel.pan)
    }
}
