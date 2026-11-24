package com.safedb.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.safedb.explore.VisualizationConfig
import com.safedb.explore.VisualizationTemplate
import com.safedb.explore.VisualizationTemplateBuildResult
import com.safedb.ui.components.PrimaryButton
import com.safedb.ui.components.SecondaryButton
import com.safedb.ui.theme.SafeDbTheme

@Composable
internal fun VisualizationTemplateDialog(
    templates: List<VisualizationTemplate>,
    onApply: (VisualizationConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableStateOf<VisualizationTemplate?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Visualization templates") },
        text = {
            Column(
                modifier =
                    Modifier.widthIn(min = 620.dp)
                        .heightIn(max = 590.dp)
                        .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                templates.forEach { template ->
                    val ready = template.result is VisualizationTemplateBuildResult.Ready
                    TemplateCard(template, selected?.id == template.id, enabled = ready) {
                        if (ready) selected = template
                    }
                }
            }
        },
        confirmButton = {
            PrimaryButton(
                onClick = {
                    val config =
                        (selected?.result as? VisualizationTemplateBuildResult.Ready)?.config
                            ?: return@PrimaryButton
                    onApply(config)
                },
                enabled = selected?.result is VisualizationTemplateBuildResult.Ready,
            ) {
                Text("Apply template")
            }
        },
        dismissButton = { SecondaryButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
internal fun TemplateCard(
    template: VisualizationTemplate,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val reason = (template.result as? VisualizationTemplateBuildResult.Unavailable)?.reason
    Surface(
        modifier =
            Modifier.fillMaxWidth()
                .alpha(if (enabled) 1f else 0.55f)
                .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        shape = RoundedCornerShape(3.dp),
        color =
            if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainerLow,
        border =
            BorderStroke(
                1.dp,
                if (selected) SafeDbTheme.colors.actionPrimary
                else MaterialTheme.colorScheme.outlineVariant,
            ),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                template.name,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                template.description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                reason ?: template.preview,
                style = MaterialTheme.typography.labelSmall,
                color =
                    if (reason != null) MaterialTheme.colorScheme.error
                    else SafeDbTheme.colors.actionPrimary,
            )
        }
    }
}
