package com.safedb.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.safedb.ui.theme.InputShape

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
        shape = RoundedCornerShape(12.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(message, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    placeholder = { Text(placeholder) },
                    singleLine = true,
                    shape = InputShape,
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
