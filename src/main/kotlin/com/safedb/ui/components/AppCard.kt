package com.safedb.ui.components

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import com.safedb.ui.theme.CardShape
import com.safedb.ui.theme.SafeDbTheme

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

    val lifted = hoverLift && hovered
    val borderColor by
        animateColorAsState(
            if (lifted) {
                SafeDbTheme.colors.actionPrimary
            } else {
                MaterialTheme.colorScheme.outline
            }
        )
    val backgroundColor by
        animateColorAsState(
            if (lifted) MaterialTheme.colorScheme.surfaceContainerLow else containerColor
        )
    val border = BorderStroke(1.dp, borderColor)

    if (onClick != null) {
        Surface(
            modifier = modifier.pointerHoverIcon(PointerIcon.Hand),
            shape = CardShape,
            color = backgroundColor,
            border = border,
            shadowElevation = 0.dp,
            tonalElevation = 0.dp,
            interactionSource = interactionSource,
            onClick = onClick,
            content = { Column(content = content) },
        )
    } else {
        Surface(
            modifier = modifier.hoverable(interactionSource),
            shape = CardShape,
            color = backgroundColor,
            border = border,
            shadowElevation = 0.dp,
            tonalElevation = 0.dp,
            content = { Column(content = content) },
        )
    }
}
