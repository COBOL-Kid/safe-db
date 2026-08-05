package com.safedb.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.safedb.model.QueryResult
import com.safedb.ui.components.PrimaryButton
import com.safedb.ui.components.SecondaryButton

@Composable
internal fun ExploreDrillDialog(result: QueryResult, onExport: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(4.dp),
        title = {
            Column {
                Text("Source rows", style = MaterialTheme.typography.titleMedium)
                Text(
                    "${result.rowCount} matching row${if (result.rowCount == 1) "" else "s"} from the Explore sample",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = { ResultsTable(result = result, modifier = Modifier.width(860.dp).height(480.dp)) },
        confirmButton = { PrimaryButton(onClick = onExport) { Text("Export rows") } },
        dismissButton = { SecondaryButton(onClick = onDismiss) { Text("Close") } },
    )
}
