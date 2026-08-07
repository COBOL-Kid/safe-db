package com.safedb.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size

internal const val SCHEMA_MAP_MIN_ZOOM = 0.01f
internal const val SCHEMA_MAP_MAX_ZOOM = 2f
internal const val SCHEMA_MAP_DEFAULT_INSET = 36f

internal data class SchemaMapContext(val connectionId: String, val schema: String)

internal data class SchemaMapAxisScrollState(
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

internal fun schemaMapAxisScrollState(
    contentStart: Float,
    contentEnd: Float,
    viewportSize: Float,
    zoom: Float,
    pan: Float,
    padding: Float = SCHEMA_MAP_DEFAULT_INSET,
): SchemaMapAxisScrollState {
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
    return SchemaMapAxisScrollState(
        contentStart = contentStart,
        zoom = safeZoom,
        leadingInset = leadingInset,
        contentSize = scrollableContentSize,
        viewportSize = safeViewport,
        scrollOffset = scrollOffset,
    )
}

internal fun schemaMapConstrainedPan(
    target: Offset,
    horizontal: SchemaMapAxisScrollState,
    vertical: SchemaMapAxisScrollState,
): Offset = Offset(horizontal.constrainPan(target.x), vertical.constrainPan(target.y))

internal fun schemaMapPanForScrollDelta(
    horizontal: SchemaMapAxisScrollState,
    vertical: SchemaMapAxisScrollState,
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

/** Session-only interaction state for the schema map. */
internal class SchemaMapViewModel {
    private var activeContext by mutableStateOf<SchemaMapContext?>(null)
    private var expandedByContext by mutableStateOf<Map<SchemaMapContext, Set<String>>>(emptyMap())
    private var offsetsByContext by
        mutableStateOf<Map<SchemaMapContext, Map<String, Offset>>>(emptyMap())

    var query by mutableStateOf("")
    var zoom by mutableFloatStateOf(1f)
        private set

    var pan by mutableStateOf(Offset(SCHEMA_MAP_DEFAULT_INSET, SCHEMA_MAP_DEFAULT_INSET))
        private set

    fun activate(connectionId: String?, schema: String?) {
        val next =
            if (connectionId.isNullOrBlank() || schema.isNullOrBlank()) null
            else SchemaMapContext(connectionId, schema)
        if (next == activeContext) return
        activeContext = next
        query = ""
        zoom = 1f
        pan = Offset(SCHEMA_MAP_DEFAULT_INSET, SCHEMA_MAP_DEFAULT_INSET)
    }

    fun isActive(connectionId: String, schema: String): Boolean =
        activeContext == SchemaMapContext(connectionId, schema)

    fun isExpanded(nodeId: String): Boolean =
        activeContext?.let { context -> nodeId in expandedByContext[context].orEmpty() } == true

    fun toggleExpanded(nodeId: String) {
        val context = activeContext ?: return
        val expanded = expandedByContext[context].orEmpty()
        val next = if (nodeId in expanded) expanded - nodeId else expanded + nodeId
        expandedByContext = expandedByContext + (context to next)
    }

    fun offsetFor(nodeId: String): Offset =
        activeContext?.let { context -> offsetsByContext[context]?.get(nodeId) } ?: Offset.Zero

    fun moveNode(nodeId: String, canvasDelta: Offset) {
        val context = activeContext ?: return
        val offsets = offsetsByContext[context].orEmpty()
        offsetsByContext =
            offsetsByContext +
                (context to (offsets + (nodeId to (offsets[nodeId].orZero() + canvasDelta))))
    }

    fun panBy(screenDelta: Offset) {
        pan += screenDelta
    }

    fun updatePan(target: Offset) {
        pan = target
    }

    fun setZoom(target: Float, anchor: Offset = Offset.Zero) {
        val next = target.coerceIn(SCHEMA_MAP_MIN_ZOOM, SCHEMA_MAP_MAX_ZOOM)
        if (next == zoom) return
        val ratio = next / zoom
        pan = anchor - (anchor - pan) * ratio
        zoom = next
    }

    fun fit(contentBounds: Rect, viewport: Size, padding: Float = SCHEMA_MAP_DEFAULT_INSET) {
        if (contentBounds.width <= 0f || contentBounds.height <= 0f) return
        if (viewport.width <= 0f || viewport.height <= 0f) return
        val usableWidth = (viewport.width - padding * 2f).coerceAtLeast(1f)
        val usableHeight = (viewport.height - padding * 2f).coerceAtLeast(1f)
        zoom =
            minOf(usableWidth / contentBounds.width, usableHeight / contentBounds.height)
                .coerceIn(SCHEMA_MAP_MIN_ZOOM, SCHEMA_MAP_MAX_ZOOM)
        pan =
            Offset(
                (viewport.width - contentBounds.width * zoom) / 2f - contentBounds.left * zoom,
                (viewport.height - contentBounds.height * zoom) / 2f - contentBounds.top * zoom,
            )
    }

    fun focus(nodeBounds: Rect, viewport: Size) {
        if (viewport.width <= 0f || viewport.height <= 0f) return
        val center = nodeBounds.center
        pan = Offset(viewport.width / 2f - center.x * zoom, viewport.height / 2f - center.y * zoom)
    }

    fun resetLayout() {
        activeContext?.let { context -> offsetsByContext = offsetsByContext - context }
        zoom = 1f
        pan = Offset(SCHEMA_MAP_DEFAULT_INSET, SCHEMA_MAP_DEFAULT_INSET)
    }
}

private fun Offset?.orZero(): Offset = this ?: Offset.Zero
