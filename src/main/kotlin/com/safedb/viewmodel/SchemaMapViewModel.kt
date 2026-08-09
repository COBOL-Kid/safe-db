package com.safedb.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size

internal const val SCHEMA_MAP_MIN_ZOOM = CANVAS_MIN_ZOOM
internal const val SCHEMA_MAP_MAX_ZOOM = CANVAS_MAX_ZOOM
internal const val SCHEMA_MAP_DEFAULT_INSET = CANVAS_DEFAULT_PADDING

internal data class SchemaMapContext(val connectionId: String, val schema: String)

internal class SchemaMapViewModel {
    private var activeContext by mutableStateOf<SchemaMapContext?>(null)
    private var expandedByContext by mutableStateOf<Map<SchemaMapContext, Set<String>>>(emptyMap())
    private var offsetsByContext by
        mutableStateOf<Map<SchemaMapContext, Map<String, Offset>>>(emptyMap())
    private var initialFitPending by mutableStateOf(false)

    var query by mutableStateOf("")
    private val viewport =
        CanvasViewportState(initialPan = Offset(SCHEMA_MAP_DEFAULT_INSET, SCHEMA_MAP_DEFAULT_INSET))

    val zoom: Float
        get() = viewport.zoom

    val pan: Offset
        get() = viewport.pan

    fun activate(connectionId: String?, schema: String?) {
        val next =
            if (connectionId.isNullOrBlank() || schema.isNullOrBlank()) null
            else SchemaMapContext(connectionId, schema)
        if (next == activeContext) return
        activeContext = next
        initialFitPending = next != null
        query = ""
        viewport.reset()
    }

    fun isActive(connectionId: String, schema: String): Boolean =
        activeContext == SchemaMapContext(connectionId, schema)

    fun consumeInitialFitRequest(connectionId: String, schema: String): Boolean {
        if (!initialFitPending || !isActive(connectionId, schema)) return false
        initialFitPending = false
        return true
    }

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
        viewport.panBy(screenDelta)
    }

    fun updatePan(target: Offset) {
        viewport.updatePan(target)
    }

    fun setZoom(target: Float, anchor: Offset = Offset.Zero) {
        viewport.setZoom(target, anchor)
    }

    fun fit(contentBounds: Rect, viewport: Size, padding: Float = SCHEMA_MAP_DEFAULT_INSET) {
        this.viewport.fit(contentBounds, viewport, padding)
    }

    fun focus(nodeBounds: Rect, viewport: Size) {
        if (viewport.width <= 0f || viewport.height <= 0f) return
        val center = nodeBounds.center
        this.viewport.updatePan(
            Offset(viewport.width / 2f - center.x * zoom, viewport.height / 2f - center.y * zoom)
        )
    }

    fun resetLayout() {
        activeContext?.let { context -> offsetsByContext = offsetsByContext - context }
        viewport.reset()
    }
}

private fun Offset?.orZero(): Offset = this ?: Offset.Zero
