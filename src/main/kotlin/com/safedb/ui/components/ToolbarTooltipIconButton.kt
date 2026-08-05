package com.safedb.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import com.safedb.ui.theme.ButtonShape
import com.safedb.ui.theme.SafeDbTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolbarTooltipIconButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    highlighted: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val safeDbColors = SafeDbTheme.colors
    val containerColor =
        if (highlighted) {
            safeDbColors.warningContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        }
    val contentColor =
        if (highlighted) {
            safeDbColors.warning
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    val borderColor =
        if (highlighted) {
            safeDbColors.warning.copy(alpha = 0.5f)
        } else {
            MaterialTheme.colorScheme.outlineVariant
        }

    TooltipBox(
        positionProvider =
            TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { androidx.compose.material3.Text(label) } },
        state = rememberTooltipState(),
    ) {
        Surface(
            onClick = onClick,
            modifier = modifier.size(40.dp).pointerHoverIcon(PointerIcon.Hand),
            shape = ButtonShape,
            color = containerColor,
            contentColor = contentColor,
            border = BorderStroke(1.dp, borderColor),
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = label, modifier = Modifier.size(18.dp))
            }
        }
    }
}
