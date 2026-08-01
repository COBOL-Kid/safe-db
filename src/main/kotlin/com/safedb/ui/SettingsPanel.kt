package com.safedb.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.safedb.model.QueryRiskGate
import com.safedb.ui.components.SecondaryButton
import com.safedb.ui.components.SelectablePill
import com.safedb.ui.components.ColorSchemePicker
import com.safedb.ui.components.ModeToggle
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
    val loadError by viewModel.loadError.collectAsState()

    LaunchedEffect(open, settings) {
        if (open) {
            viewModel.clearSaveError()
            viewModel.clearLoadError()
        }
    }

    AlertDialog(
        onDismissRequest = onClose,
        shape = RoundedCornerShape(4.dp),
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
                loadError?.let { error ->
                    Text(
                        error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                saveError?.let { error ->
                    Text(
                        error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Column {
                    Text("Appearance", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Control Plane keeps the same sharp layout in every scheme.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
                    )
                    ModeToggle(
                        isDark = settings.theme == "dark",
                        onSelect = viewModel::setDarkMode,
                    )
                    Text(
                        "Color scheme",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 14.dp, bottom = 6.dp),
                    )
                    ColorSchemePicker(
                        selected = settings.palette(),
                        isDark = settings.theme == "dark",
                        onSelect = viewModel::setColorScheme,
                    )
                }

                HorizontalDivider()

                Column {
                    Text(
                        "Query risk gate",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        "Controls which descriptive risk levels make Run unavailable. It does not replace read-only checks, restricted schemas, row limits, or timeouts.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for ((gate, label) in listOf(
                            QueryRiskGate.Cautious to "Cautious",
                            QueryRiskGate.Standard to "Standard",
                            QueryRiskGate.Flexible to "Flexible",
                            QueryRiskGate.Disabled to "Off",
                        )) {
                            SelectablePill(
                                label = label,
                                selected = settings.queryRiskGate == gate,
                                onClick = { viewModel.setQueryRiskGate(gate) },
                            )
                        }
                    }
                    Text(
                        when (settings.queryRiskGate) {
                            QueryRiskGate.Cautious -> "Blocks Elevated concern and above."
                            QueryRiskGate.Standard -> "Blocks High concern and above."
                            QueryRiskGate.Flexible -> "Blocks Very high concern."
                            QueryRiskGate.Disabled -> "Risk calculation is not required before Run."
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
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
