package com.safedb.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.safedb.ui.theme.CardShape

/**
 * Bordered card with a subtle hover-lift: the border darkens slightly and a
 * soft shadow appears when hovered. Pass [onClick] to make the whole card
 * clickable; leave null for a static card.
 */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    hoverLift: Boolean = true,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    content: @Composable ColumnScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()

    val border = if (hoverLift && hovered) {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.9f))
    } else {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    }
    val shadow = if (hoverLift && hovered) 2.dp else 0.dp

    if (onClick != null) {
        Surface(
            modifier = modifier,
            shape = CardShape,
            color = containerColor,
            border = border,
            shadowElevation = shadow,
            tonalElevation = 0.dp,
            interactionSource = interactionSource,
            onClick = onClick,
            content = { Column(content = content) },
        )
    } else {
        Surface(
            modifier = modifier.hoverable(interactionSource),
            shape = CardShape,
            color = containerColor,
            border = border,
            shadowElevation = shadow,
            tonalElevation = 0.dp,
            content = { Column(content = content) },
        )
    }
}
