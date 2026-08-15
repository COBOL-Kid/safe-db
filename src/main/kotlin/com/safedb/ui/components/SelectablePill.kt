package com.safedb.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.safedb.ui.theme.PillShape
import com.safedb.ui.theme.SafeDbTheme

@Composable
fun SelectablePill(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        shape = PillShape,
        color =
            if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainerLow,
        border =
            BorderStroke(
                1.dp,
                if (selected) SafeDbTheme.colors.actionPrimary
                else MaterialTheme.colorScheme.outlineVariant,
            ),
        modifier = modifier.clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand),
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
