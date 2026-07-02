package com.safedb.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.safedb.model.TransportSecurityMode

private data class TransportOption(
    val value: TransportSecurityMode,
    val label: String,
)

private val TRANSPORT_OPTIONS = listOf(
    TransportOption(TransportSecurityMode.VerifyIdentity, "SSL with hostname verification"),
    TransportOption(TransportSecurityMode.VerifyCa, "Verify CA"),
    TransportOption(TransportSecurityMode.EncryptOnly, "SSL encrypt only (no cert check)"),
    TransportOption(TransportSecurityMode.Disabled, "Disabled"),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConnectionAdvancedPanel(
    transportMode: TransportSecurityMode,
    onTransportModeChange: (TransportSecurityMode) -> Unit,
    caPem: String,
    onCaPemChange: (String) -> Unit,
    onManualChange: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Advanced connection settings",
                style = MaterialTheme.typography.titleSmall,
            )

            Text(
                text = "Transport security",
                style = MaterialTheme.typography.labelLarge,
            )

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (option in TRANSPORT_OPTIONS) {
                    FilterChip(
                        selected = transportMode == option.value,
                        onClick = {
                            onTransportModeChange(option.value)
                            onManualChange()
                        },
                        label = { Text(option.label) },
                    )
                }
            }

            if (transportMode == TransportSecurityMode.VerifyCa) {
                OutlinedTextField(
                    value = caPem,
                    onValueChange = {
                        onCaPemChange(it)
                        onManualChange()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("CA certificate (PEM)") },
                    minLines = 4,
                    textStyle = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
