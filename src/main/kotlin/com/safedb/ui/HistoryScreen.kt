package com.safedb.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import com.safedb.ui.components.AppCard
import com.safedb.ui.components.ConfirmDialog
import com.safedb.ui.components.EmptyState
import com.safedb.ui.components.HoverRevealActions
import com.safedb.ui.components.BannerKind
import com.safedb.ui.components.MessageBanner
import com.safedb.ui.components.PrimaryButton
import com.safedb.ui.components.PromptDialog
import com.safedb.ui.components.SecondaryButton
import com.safedb.ui.components.StatusChip
import com.safedb.ui.components.StatusChipKind
import com.safedb.ui.theme.DataMono
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
    val historyError by viewModel.history.error.collectAsState()
    val savedQueryError by viewModel.savedQueries.error.collectAsState()
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
                SecondaryButton(onClick = { showClearConfirm = true }) {
                    Text("Clear History")
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        val visibleError = historyError ?: savedQueryError
        visibleError?.let { error ->
            MessageBanner(
                text = error,
                kind = BannerKind.ERROR,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 12.dp),
            )
        }

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            entries.isEmpty() -> EmptyState(
                icon = Icons.Filled.History,
                title = "No query history yet",
                subtitle = "Run your first query to see it here.",
            ) {
                PrimaryButton(onClick = { onNavigate(com.safedb.AppRoute.Builder) }) {
                    Text("Build a Query")
                }
            }
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
private fun HistoryEntryCard(
    entry: HistoryEntry,
    onRerun: () -> Unit,
    onSave: () -> Unit,
) {
    AppCard {
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
                    StatusChip(
                        text = if (entry.error != null) "failed" else "${entry.rowCount} rows",
                        kind = if (entry.error != null) StatusChipKind.ERROR else StatusChipKind.SUCCESS,
                    )
                    Text(
                        formatTime(entry.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    summarizeSpec(entry),
                    style = DataMono,
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
                        Icon(
                            Icons.Filled.Save,
                            contentDescription = "Save as query",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                PrimaryButton(onClick = onRerun) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text("Rerun", modifier = Modifier.padding(start = 4.dp))
                }
            }
        }
    }
}
