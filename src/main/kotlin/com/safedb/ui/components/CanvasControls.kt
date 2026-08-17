package com.safedb.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.safedb.model.ConnectionDef
import kotlin.math.roundToInt

@Composable
internal fun ConnectionPicker(
    connection: ConnectionDef?,
    connections: List<ConnectionDef>,
    onConnectionSelected: (ConnectionDef) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Box(modifier) {
        SecondaryButton(
            onClick = { menuOpen = true },
            enabled = connections.isNotEmpty(),
            modifier = Modifier.width(216.dp),
        ) {
            Text(
                connection?.name ?: "Choose connection",
                modifier = Modifier.weight(1f, fill = false),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(Icons.Default.ExpandMore, contentDescription = null)
        }
        SafeDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            connections.forEach { option ->
                MenuActionRow(
                    text = option.name,
                    supportingText = "${option.dialect} · ${option.database}",
                    selected = option.id == connection?.id,
                    onClick = {
                        menuOpen = false
                        onConnectionSelected(option)
                    },
                )
            }
        }
    }
}

@Composable
internal fun SchemaPicker(
    selectedSchema: String?,
    schemaOptions: List<String>,
    onSchemaSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Box(modifier) {
        SecondaryButton(
            onClick = { menuOpen = true },
            enabled = schemaOptions.isNotEmpty(),
            modifier = Modifier.width(166.dp),
        ) {
            Text(
                selectedSchema ?: "Choose schema",
                modifier = Modifier.weight(1f, fill = false),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(Icons.Default.ExpandMore, contentDescription = null)
        }
        SafeDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            schemaOptions.forEach { option ->
                MenuActionRow(
                    text = option,
                    selected = option == selectedSchema,
                    onClick = {
                        menuOpen = false
                        onSchemaSelected(option)
                    },
                )
            }
        }
    }
}

@Composable
internal fun CanvasZoomControls(
    zoom: Float,
    minZoom: Float,
    maxZoom: Float,
    fitDescription: String,
    resetDescription: String,
    onZoomOut: () -> Unit,
    onZoomIn: () -> Unit,
    onFit: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
    ) {
        Row(
            modifier = Modifier.height(38.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CanvasControl(Icons.Default.Remove, "Zoom out", zoom > minZoom, onZoomOut)
            Text(
                "${(zoom * 100).roundToInt()}%",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.width(44.dp),
                textAlign = TextAlign.Center,
            )
            CanvasControl(Icons.Default.Add, "Zoom in", zoom < maxZoom, onZoomIn)
            Spacer(Modifier.width(3.dp))
            CanvasControl(Icons.Default.CenterFocusStrong, fitDescription, true, onFit)
            CanvasControl(Icons.Default.RestartAlt, resetDescription, true, onReset)
        }
    }
}

@Composable
private fun CanvasControl(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    ControlTooltip(description) {
        Icon(
            icon,
            contentDescription = description,
            tint =
                if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            modifier =
                Modifier.size(30.dp)
                    .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                    .padding(6.dp),
        )
    }
}

@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
private fun ControlTooltip(text: String, content: @Composable () -> Unit) {
    TooltipBox(
        positionProvider =
            TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(text) } },
        state = rememberTooltipState(),
        content = content,
    )
}
