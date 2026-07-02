package com.safedb.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.safedb.AppRoute
import com.safedb.model.HistoryEntry
import com.safedb.model.SavedQuery
import com.safedb.ui.components.ConfirmDialog
import com.safedb.ui.components.PromptDialog
import com.safedb.ui.util.formatTime
import com.safedb.ui.util.summarizeSpec
import com.safedb.viewmodel.AppViewModel
import java.time.Instant
import java.util.UUID

@Composable
fun HistoryScreen(
    viewModel: AppViewModel,
    onRerun: (HistoryEntry) -> Unit,
    onNavigate: (com.safedb.AppRoute) -> Unit,
) {
    val entries by viewModel.history.entries.collectAsState()
    val loading by viewModel.history.loading.collectAsState()
    var showClearConfirm by remember { mutableStateOf(false) }
    var showSavePrompt by remember { mutableStateOf(false) }
    var saveQueryName by remember { mutableStateOf("") }
    var saveEntry by remember { mutableStateOf<HistoryEntry?>(null) }

    ConfirmDialog(
        open = showClearConfirm,
        title = "Clear history?",
        message = "Clear all query history? This cannot be undone.",
        destructive = true,
        confirmLabel = "Clear",
        onConfirm = {
            viewModel.history.clear { showClearConfirm = false }
        },
        onCancel = { showClearConfirm = false },
    )

    PromptDialog(
        open = showSavePrompt,
        title = "Save query",
        message = "Choose a name for this query.",
        value = saveQueryName,
        onValueChange = { saveQueryName = it },
        placeholder = "Query name",
        onConfirm = {
            val entry = saveEntry ?: return@PromptDialog
            if (saveQueryName.isBlank()) return@PromptDialog
            viewModel.savedQueries.save(
                SavedQuery(
                    id = UUID.randomUUID().toString(),
                    name = saveQueryName.trim(),
                    connectionId = entry.connectionId,
                    spec = entry.spec,
                    createdAt = Instant.now().epochSecond.toString(),
                ),
            ) {
                showSavePrompt = false
                saveEntry = null
            }
        },
        onCancel = {
            showSavePrompt = false
            saveEntry = null
        },
    )

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp, vertical = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("History", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    "Your recent and saved queries.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (entries.isNotEmpty()) {
                OutlinedButton(onClick = { showClearConfirm = true }) {
                    Text("Clear History")
                }
            }
        }

        HorizontalDivider()

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            entries.isEmpty() -> EmptyHistory(onBuildQuery = { onNavigate(com.safedb.AppRoute.Builder) })
            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(entries, key = { it.id }) { entry ->
                    HistoryEntryCard(
                        entry = entry,
                        onRerun = { onRerun(entry) },
                        onSave = {
                            saveEntry = entry
                            saveQueryName = "${entry.connectionName} query"
                            showSavePrompt = true
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyHistory(onBuildQuery: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(
                modifier = Modifier.size(56.dp),
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.History, contentDescription = null)
                }
            }
            Text(
                "No query history yet",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 16.dp),
            )
            Text(
                "Run your first query to see it here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onBuildQuery, modifier = Modifier.padding(top = 20.dp)) {
                Text("Build a Query")
            }
        }
    }
}

@Composable
private fun HistoryEntryCard(
    entry: HistoryEntry,
    onRerun: () -> Unit,
    onSave: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(entry.connectionName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    StatusChip(entry)
                    Text(
                        formatTime(entry.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    summarizeSpec(entry),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
                entry.error?.let { error ->
                    Text(
                        error,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                if (entry.warnings.isNotEmpty()) {
                    Text(
                        entry.warnings.take(2).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (entry.error == null) {
                    IconButton(onClick = onSave) {
                        Icon(Icons.Filled.Save, contentDescription = "Save as query")
                    }
                }
                Button(onClick = onRerun) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text("Rerun", modifier = Modifier.padding(start = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun StatusChip(entry: HistoryEntry) {
    val (label, color) = if (entry.error != null) {
        "failed" to MaterialTheme.colorScheme.errorContainer
    } else {
        "${entry.rowCount} rows" to MaterialTheme.colorScheme.primaryContainer
    }
    Surface(shape = RoundedCornerShape(4.dp), color = color) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
