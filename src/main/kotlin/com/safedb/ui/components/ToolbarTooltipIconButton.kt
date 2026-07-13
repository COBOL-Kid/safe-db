package com.safedb.ui.components

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
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
    val colors = IconButtonDefaults.filledTonalIconButtonColors(
        containerColor = if (highlighted) {
            safeDbColors.warningContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        contentColor = if (highlighted) {
            safeDbColors.warning
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    )

    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { androidx.compose.material3.Text(label) } },
        state = rememberTooltipState(),
    ) {
        FilledTonalIconButton(
            onClick = onClick,
            colors = colors,
            modifier = modifier.pointerHoverIcon(PointerIcon.Hand),
        ) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(18.dp))
        }
    }
}
