package com.safedb.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
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
    var expanded by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.7f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Advanced connection settings",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Hide advanced connection settings" else "Show advanced connection settings",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }

            if (expanded) {
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
                        TransportSegmentButton(
                            text = option.label,
                            selected = transportMode == option.value,
                            onClick = {
                                onTransportModeChange(option.value)
                                onManualChange()
                            },
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
}

@Composable
private fun TransportSegmentButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val shape = RoundedCornerShape(8.dp)
    val action = com.safedb.ui.theme.SafeDbTheme.colors.actionPrimary
    val onAction = com.safedb.ui.theme.SafeDbTheme.colors.onActionPrimary
    val background = when {
        selected -> action
        hovered -> MaterialTheme.colorScheme.surfaceContainerLow
        else -> MaterialTheme.colorScheme.surface
    }
    val borderColor = when {
        selected -> action
        hovered -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
        else -> MaterialTheme.colorScheme.outline
    }

    Box(
        modifier = Modifier
            .clip(shape)
            .background(background)
            .border(1.dp, borderColor, shape)
            .hoverable(interactionSource)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) onAction else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
