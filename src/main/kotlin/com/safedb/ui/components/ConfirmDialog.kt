package com.safedb.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.safedb.ui.theme.DialogShape

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
        shape = DialogShape,
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
