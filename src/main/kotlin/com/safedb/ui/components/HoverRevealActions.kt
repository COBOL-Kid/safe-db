package com.safedb.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha

/**
 * Reveals [content] only when the surrounding row is hovered (fades in). Mirrors the legacy
 * `opacity-0 group-hover:opacity-100` pattern for history row actions. Wrap both the row and this
 * content in a common hoverable container, or pass a shared [interactionSource].
 */
@Composable
fun HoverRevealActions(
    interactionSource: MutableInteractionSource,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val hovered by interactionSource.collectIsHoveredAsState()
    val alpha = if (hovered) 1f else 0f
    androidx.compose.foundation.layout.Row(
        modifier = modifier.alpha(alpha),
        content = { content() },
    )
}
