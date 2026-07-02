package com.safedb.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun PromptDialog(
    open: Boolean,
    title: String,
    message: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "",
    confirmLabel: String = "Save",
    cancelLabel: String = "Cancel",
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    if (!open) return

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = {
            androidx.compose.foundation.layout.Column {
                Text(message, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    placeholder = { Text(placeholder) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            PrimaryButton(
                onClick = onConfirm,
                enabled = value.isNotBlank(),
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
