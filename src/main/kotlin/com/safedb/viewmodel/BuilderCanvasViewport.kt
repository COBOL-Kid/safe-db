package com.safedb.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size

internal const val BUILDER_CANVAS_MIN_ZOOM = 0.01f
internal const val BUILDER_CANVAS_MAX_ZOOM = 2f
internal const val BUILDER_CANVAS_ZOOM_STEP = 0.1f
internal const val BUILDER_CANVAS_FIT_PADDING = 36f

/** Session-only viewport state for the current Builder draft. */
internal class BuilderCanvasViewport {
    var zoom by mutableFloatStateOf(1f)
        private set

    var pan by mutableStateOf(Offset.Zero)
        private set

    fun setZoom(target: Float, anchor: Offset = Offset.Zero) {
        val next = target.coerceIn(BUILDER_CANVAS_MIN_ZOOM, BUILDER_CANVAS_MAX_ZOOM)
        if (next == zoom) return
        val ratio = next / zoom
        pan = anchor - (anchor - pan) * ratio
        zoom = next
    }

    fun updatePan(target: Offset) {
        pan = target
    }

    fun fit(
        contentBounds: Rect,
        viewport: Size,
        reservedTop: Float = 0f,
        padding: Float = BUILDER_CANVAS_FIT_PADDING,
    ) {
        if (contentBounds.width <= 0f || contentBounds.height <= 0f) return
        if (viewport.width <= 0f || viewport.height <= 0f) return
        val safeTop = reservedTop.coerceIn(0f, viewport.height)
        val usableWidth = (viewport.width - padding * 2f).coerceAtLeast(1f)
        val usableHeight = (viewport.height - safeTop - padding * 2f).coerceAtLeast(1f)
        zoom =
            minOf(usableWidth / contentBounds.width, usableHeight / contentBounds.height)
                .coerceIn(BUILDER_CANVAS_MIN_ZOOM, BUILDER_CANVAS_MAX_ZOOM)
        val availableCenter =
            Offset(viewport.width / 2f, safeTop + (viewport.height - safeTop) / 2f)
        pan = availableCenter - contentBounds.center * zoom
    }

    fun reset() {
        zoom = 1f
        pan = Offset.Zero
    }
}
