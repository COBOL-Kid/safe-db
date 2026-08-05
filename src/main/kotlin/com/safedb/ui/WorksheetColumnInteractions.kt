package com.safedb.ui

import com.safedb.explore.WorksheetColumnLayout
import com.safedb.explore.WorksheetValueRef

internal fun setWorksheetColumnVisibility(
    layout: List<WorksheetColumnLayout>,
    ref: WorksheetValueRef,
    visible: Boolean,
): List<WorksheetColumnLayout> = layout.map { entry ->
    if (entry.ref == ref) entry.copy(visible = visible) else entry
}

internal fun moveVisibleWorksheetColumn(
    layout: List<WorksheetColumnLayout>,
    fromVisibleIndex: Int,
    toVisibleIndex: Int,
): List<WorksheetColumnLayout> {
    val visibleSlots = layout.indices.filter { layout[it].visible }
    if (
        fromVisibleIndex !in visibleSlots.indices ||
            toVisibleIndex !in visibleSlots.indices ||
            fromVisibleIndex == toVisibleIndex
    ) {
        return layout
    }
    val reordered =
        visibleSlots.map(layout::get).toMutableList().apply {
            add(toVisibleIndex, removeAt(fromVisibleIndex))
        }
    return layout.toMutableList().apply {
        visibleSlots.forEachIndexed { index, slot -> this[slot] = reordered[index] }
    }
}
