package com.safedb.ui.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

@Composable
fun ConfirmDialog(
    open: Boolean,
    title: String,
    message: String,
    confirmLabel: String = "Delete",
    cancelLabel: String = "Cancel",
    destructive: Boolean = false,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    if (!open) return

    AlertDialog(
        onDismissRequest = onCancel,
        shape = RoundedCornerShape(4.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = { Text(message, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            PrimaryButton(onClick = onConfirm, destructive = destructive) { Text(confirmLabel) }
        },
        dismissButton = { SecondaryButton(onClick = onCancel) { Text(cancelLabel) } },
    )
}
