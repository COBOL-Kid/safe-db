package com.safedb.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.safedb.model.QueryResult
import com.safedb.ui.components.PrimaryButton
import com.safedb.ui.components.SecondaryButton
import com.safedb.ui.theme.SafeDbTheme

@Composable
internal fun ExploreTemplatesDialog(
    sample: QueryResult,
    fields: List<ExploreFieldOption>,
    selectedTemplateId: ExploreBuiltinTemplateId?,
    onSelectTemplate: (ExploreBuiltinTemplateId) -> Unit,
    onApplyTemplate: (ExploreBuiltinTemplateId) -> Unit,
    onDismiss: () -> Unit,
) {
    val templates = listExploreTemplates(sample, fields)
    val selected = templates.firstOrNull { it.id == selectedTemplateId && it.available }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(12.dp),
        title = { Text("Templates", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(
                modifier = Modifier.widthIn(min = 520.dp, max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "Start from a built-in layout, then keep editing in Build view.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                templates.filterNot { it.isUserTemplate }.forEach { template ->
                    TemplateCard(
                        template = template,
                        selected = template.id == selectedTemplateId,
                        onClick = { if (template.available) onSelectTemplate(template.id) },
                    )
                }
                Text(
                    "My templates",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    "Save and reuse your own views here in a future update.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            PrimaryButton(
                onClick = { selected?.let { onApplyTemplate(it.id) } },
                enabled = selected != null,
            ) {
                Text("Apply template")
            }
        },
        dismissButton = { SecondaryButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun TemplateCard(
    template: ExploreTemplateListItem,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val borderColor = when {
        selected -> SafeDbTheme.colors.actionPrimary
        template.available -> MaterialTheme.colorScheme.outline
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    val containerColor = when {
        selected -> MaterialTheme.colorScheme.primaryContainer
        template.available -> MaterialTheme.colorScheme.surfaceContainerLow
        else -> MaterialTheme.colorScheme.surfaceContainerLowest
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (template.available) Modifier.clickable(onClick = onClick) else Modifier),
        shape = RoundedCornerShape(8.dp),
        color = containerColor,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                template.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (template.available) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                template.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                template.preview,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            template.unavailableReason?.let { reason ->
                Text(
                    reason,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
