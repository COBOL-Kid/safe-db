package com.safedb.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha

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
