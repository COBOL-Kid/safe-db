package com.safedb.viewmodel

import androidx.compose.foundation.v2.ScrollbarAdapter
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size

internal const val CANVAS_MIN_ZOOM = 0.01f
internal const val CANVAS_MAX_ZOOM = 2f
internal const val CANVAS_ZOOM_STEP = 0.1f
internal const val CANVAS_DEFAULT_PADDING = 36f

internal class CanvasViewportState(
    private val initialPan: Offset = Offset.Zero,
    private val minZoom: Float = CANVAS_MIN_ZOOM,
    private val maxZoom: Float = CANVAS_MAX_ZOOM,
) {
    var zoom by mutableFloatStateOf(1f)
        private set

    var pan by mutableStateOf(initialPan)
        private set

    fun updatePan(target: Offset) {
        pan = target
    }

    fun setZoom(target: Float, anchor: Offset = Offset.Zero) {
        val next = target.coerceIn(minZoom, maxZoom)
        if (next == zoom) return
        val ratio = next / zoom
        pan = anchor - (anchor - pan) * ratio
        zoom = next
    }

    fun fit(
        contentBounds: Rect,
        viewport: Size,
        padding: Float = CANVAS_DEFAULT_PADDING,
    ) {
        if (contentBounds.width <= 0f || contentBounds.height <= 0f) return
        if (viewport.width <= 0f || viewport.height <= 0f) return
        val usableWidth = (viewport.width - padding * 2f).coerceAtLeast(1f)
        val usableHeight = (viewport.height - padding * 2f).coerceAtLeast(1f)
        zoom =
            minOf(usableWidth / contentBounds.width, usableHeight / contentBounds.height)
                .coerceIn(minZoom, maxZoom)
        pan = Offset(viewport.width / 2f, viewport.height / 2f) - contentBounds.center * zoom
    }

    fun reset() {
        zoom = 1f
        pan = initialPan
    }
}

internal data class CanvasAxisScrollState(
    val contentStart: Float,
    val zoom: Float,
    val leadingInset: Float,
    val contentSize: Float,
    val viewportSize: Float,
    val scrollOffset: Float,
) {
    val maxScrollOffset: Float
        get() = (contentSize - viewportSize).coerceAtLeast(0f)

    fun panForScrollOffset(target: Float): Float =
        leadingInset - contentStart * zoom - target.coerceIn(0f, maxScrollOffset)

    fun scrollOffsetForPan(target: Float): Float =
        (leadingInset - contentStart * zoom - target).coerceIn(0f, maxScrollOffset)

    fun constrainPan(target: Float): Float = panForScrollOffset(scrollOffsetForPan(target))
}

internal class CanvasScrollbarAdapter(
    private val state: () -> CanvasAxisScrollState,
    private val onScrollTo: (Float) -> Unit,
) : ScrollbarAdapter {
    override val scrollOffset: Double
        get() = state().scrollOffset.toDouble()

    override val contentSize: Double
        get() = state().contentSize.toDouble()

    override val viewportSize: Double
        get() = state().viewportSize.toDouble()

    override suspend fun scrollTo(scrollOffset: Double) {
        onScrollTo(scrollOffset.toFloat())
    }
}

internal fun canvasAxisScrollState(
    contentStart: Float,
    contentEnd: Float,
    viewportSize: Float,
    zoom: Float,
    pan: Float,
    padding: Float = CANVAS_DEFAULT_PADDING,
): CanvasAxisScrollState {
    val safeViewport = viewportSize.coerceAtLeast(0f)
    val safeZoom = zoom.coerceAtLeast(0f)
    val scaledContentSize = (contentEnd - contentStart).coerceAtLeast(0f) * safeZoom
    val paddedContentSize = scaledContentSize + padding * 2f
    val contentFits = safeViewport > 0f && paddedContentSize <= safeViewport
    val leadingInset = if (contentFits) (safeViewport - scaledContentSize) / 2f else padding
    val scrollableContentSize =
        if (contentFits) safeViewport else paddedContentSize.coerceAtLeast(safeViewport)
    val maxScrollOffset = (scrollableContentSize - safeViewport).coerceAtLeast(0f)
    val scrollOffset = (leadingInset - contentStart * safeZoom - pan).coerceIn(0f, maxScrollOffset)
    return CanvasAxisScrollState(
        contentStart = contentStart,
        zoom = safeZoom,
        leadingInset = leadingInset,
        contentSize = scrollableContentSize,
        viewportSize = safeViewport,
        scrollOffset = scrollOffset,
    )
}

internal fun canvasConstrainedPan(
    target: Offset,
    horizontal: CanvasAxisScrollState,
    vertical: CanvasAxisScrollState,
): Offset = Offset(horizontal.constrainPan(target.x), vertical.constrainPan(target.y))

internal fun canvasPanForScrollDelta(
    horizontal: CanvasAxisScrollState,
    vertical: CanvasAxisScrollState,
    delta: Offset,
    shiftPressed: Boolean,
    pixelsPerScrollUnit: Float = 40f,
): Offset {
    val horizontalUnits =
        if (shiftPressed) {
            if (delta.x != 0f) delta.x else delta.y
        } else {
            delta.x
        }
    val verticalUnits = if (shiftPressed) 0f else delta.y
    return Offset(
        horizontal.panForScrollOffset(
            horizontal.scrollOffset + horizontalUnits * pixelsPerScrollUnit
        ),
        vertical.panForScrollOffset(vertical.scrollOffset + verticalUnits * pixelsPerScrollUnit),
    )
}

internal fun canvasPanForScrollEvent(
    horizontal: CanvasAxisScrollState,
    vertical: CanvasAxisScrollState,
    delta: Offset,
    shiftPressed: Boolean,
    consumed: Boolean,
    pixelsPerScrollUnit: Float = 40f,
): Offset? =
    if (consumed) {
        null
    } else {
        canvasPanForScrollDelta(
            horizontal = horizontal,
            vertical = vertical,
            delta = delta,
            shiftPressed = shiftPressed,
            pixelsPerScrollUnit = pixelsPerScrollUnit,
        )
    }
