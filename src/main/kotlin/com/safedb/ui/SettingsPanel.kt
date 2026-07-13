package com.safedb.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.safedb.ui.theme.DataMono
import com.safedb.model.Dialect
import com.safedb.model.Settings
import com.safedb.ui.components.PrimaryButton
import com.safedb.ui.components.SecondaryButton
import com.safedb.viewmodel.SettingsViewModel

@Composable
fun SettingsPanel(
    open: Boolean,
    viewModel: SettingsViewModel,
    onClose: () -> Unit,
) {
    if (!open) return

    val settings by viewModel.settings.collectAsState()
    val saveError by viewModel.saveError.collectAsState()
    var newSchema by remember { mutableStateOf("") }
    val thresholdInputs = remember { mutableStateMapOf<Dialect, String>() }

    LaunchedEffect(open, settings) {
        if (open) {
            for (dialect in Dialect.entries) {
                thresholdInputs[dialect] = settings.costThreshold(dialect).toLong().toString()
            }
            newSchema = ""
            viewModel.clearSaveError()
        }
    }

    AlertDialog(
        onDismissRequest = onClose,
        shape = RoundedCornerShape(12.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        title = { Text("Settings", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Column {
                    Text(
                        "EXPLAIN cost thresholds",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    for (dialect in Dialect.entries) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                SettingsViewModel.dialectLabel(dialect),
                                modifier = Modifier.weight(0.4f),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            OutlinedTextField(
                                value = thresholdInputs[dialect] ?: "",
                                onValueChange = { thresholdInputs[dialect] = it },
                                singleLine = true,
                                modifier = Modifier.weight(0.6f),
                            )
                        }
                    }
                    PrimaryButton(
                        onClick = {
                            val thresholds = Dialect.entries.associateWith { dialect ->
                                thresholdInputs[dialect]?.toDoubleOrNull() ?: Settings.DEFAULT_COST_THRESHOLD
                            }
                            viewModel.saveThresholds(thresholds)
                        },
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        Text("Save thresholds")
                    }
                    saveError?.let { error ->
                        Text(
                            error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    Text(
                        "Queries above this estimated cost require confirmation.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                HorizontalDivider()

                Column {
                    Text("Blocked schemas", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = newSchema,
                            onValueChange = { newSchema = it },
                            placeholder = { Text("schema name") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        SecondaryButton(
                            onClick = {
                                viewModel.addBlockedSchema(newSchema)
                                newSchema = ""
                            },
                        ) {
                            Text("Add")
                        }
                    }
                    if (settings.blockedSchemas.isEmpty()) {
                        Text(
                            "No custom blocked schemas.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    } else {
                        Column(
                            modifier = Modifier.padding(top = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            for (schema in settings.blockedSchemas) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(schema, style = DataMono, color = MaterialTheme.colorScheme.onSurface)
                                    TextButton(onClick = { viewModel.removeBlockedSchema(schema) }) {
                                        Text("Remove")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            SecondaryButton(onClick = onClose) {
                Text("Close")
            }
        },
    )
}
