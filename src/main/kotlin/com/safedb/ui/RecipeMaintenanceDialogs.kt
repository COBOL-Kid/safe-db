package com.safedb.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.safedb.explore.ExploreRecipe
import com.safedb.ui.components.ConfirmDialog
import com.safedb.ui.components.PrimaryButton
import com.safedb.ui.components.SecondaryButton

@Composable
internal fun RecipeMaintenanceDialogs(
    deleting: ExploreRecipe?,
    renaming: ExploreRecipe?,
    renameValue: String,
    onRenameValueChange: (String) -> Unit,
    onDelete: (ExploreRecipe) -> Unit,
    onDeleteDismiss: () -> Unit,
    onRename: (ExploreRecipe, String) -> Unit,
    onRenameDismiss: () -> Unit,
) {
    ConfirmDialog(
        open = deleting != null,
        title = "Delete recipe?",
        message = deleting?.let { "Delete “${it.name}”? This cannot be undone." }.orEmpty(),
        confirmLabel = "Delete",
        onConfirm = { deleting?.let(onDelete) },
        onCancel = onDeleteDismiss,
    )
    renaming?.let { recipe ->
        AlertDialog(
            onDismissRequest = onRenameDismiss,
            title = { Text("Rename recipe") },
            text = {
                OutlinedTextField(
                    value = renameValue,
                    onValueChange = onRenameValueChange,
                    label = { Text("Name") },
                    singleLine = true,
                )
            },
            confirmButton = {
                PrimaryButton(
                    onClick = { onRename(recipe, renameValue.trim()) },
                    enabled = renameValue.isNotBlank(),
                ) {
                    Text("Rename")
                }
            },
            dismissButton = { SecondaryButton(onClick = onRenameDismiss) { Text("Cancel") } },
        )
    }
}
