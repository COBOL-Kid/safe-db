package com.safedb.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
        title = { Text(title) },
        text = { Text(message, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            PrimaryButton(
                onClick = onConfirm,
                destructive = destructive,
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            SecondaryButton(onClick = onCancel) {
                Text(cancelLabel)
            }
        },
    )
}
