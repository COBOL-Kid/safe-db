package com.safedb.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.safedb.ui.theme.SafeDbTheme

@Composable
private fun flatElevation() = ButtonDefaults.elevatedButtonElevation(
    defaultElevation = 0.dp,
    pressedElevation = 0.dp,
    hoveredElevation = 0.dp,
    focusedElevation = 0.dp,
    disabledElevation = 0.dp,
)

/**
 * Primary action button — flat neutral fill (contrast-flips between light/dark),
 * no Material elevation. Blue stays an accent, not a primary fill.
 *
 * Pass [destructive] to render in the error color (for delete/confirm-destructive).
 */
@Composable
fun PrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = if (destructive) {
        ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
        )
    } else {
        val c = SafeDbTheme.colors
        ButtonDefaults.buttonColors(
            containerColor = c.actionPrimary,
            contentColor = c.onActionPrimary,
        )
    }
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = colors,
        elevation = flatElevation(),
        contentPadding = contentPadding,
        content = content,
    )
}

/**
 * Secondary/outline button — bordered, transparent fill, flat (no elevation).
 */
@Composable
fun SecondaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        elevation = flatElevation(),
        content = content,
    )
}
