package com.safedb.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.safedb.ui.theme.ChipShape
import com.safedb.ui.theme.SafeDbTheme

enum class StatusChipKind {
    NEUTRAL, SUCCESS, WARNING, ERROR, INFO, UQ
}

@Composable
fun StatusChip(
    text: String,
    kind: StatusChipKind = StatusChipKind.NEUTRAL,
    modifier: Modifier = Modifier,
) {
    val (container, content) = chipColors(kind)
    Surface(
        modifier = modifier,
        shape = ChipShape,
        color = container,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Text(
            text = text,
            color = content,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun chipColors(kind: StatusChipKind): Pair<Color, Color> {
    val c = SafeDbTheme.colors
    val s = MaterialTheme.colorScheme
    return when (kind) {
        StatusChipKind.NEUTRAL -> s.secondaryContainer to s.onSecondaryContainer
        StatusChipKind.SUCCESS -> c.successContainer to c.onSuccessContainer
        StatusChipKind.WARNING -> c.warningContainer to c.onWarningContainer
        StatusChipKind.ERROR -> s.errorContainer to s.onErrorContainer
        StatusChipKind.INFO -> c.infoContainer to c.onInfoContainer
        StatusChipKind.UQ -> c.uqContainer to c.onUqContainer
    }
}
