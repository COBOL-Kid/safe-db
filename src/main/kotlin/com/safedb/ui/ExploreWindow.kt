package com.safedb.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.safedb.explore.PivotFilter
import com.safedb.model.QueryResult
import com.safedb.model.QuerySpec
import com.safedb.ui.components.BannerKind
import com.safedb.ui.components.MessageBanner
import com.safedb.ui.components.PrimaryButton
import com.safedb.ui.components.SecondaryButton
import com.safedb.ui.components.StatusChip
import com.safedb.ui.components.StatusChipKind
import com.safedb.viewmodel.ExploreViewModel
import java.io.File
import javax.swing.JFileChooser

@Composable
fun ExploreWindowContent(
    viewModel: ExploreViewModel,
    currentSpec: QuerySpec,
    onClose: () -> Unit,
    onRefreshSample: (() -> Unit)? = null,
    sampleRefreshEnabled: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val session = viewModel.session
    val preview = viewModel.preview
    val config = viewModel.config
    val fields = remember(session.sample.columns, session.baseSpec.tables) {
        buildExploreFieldOptions(session.sample, session.baseSpec.tables)
    }
    val stale = viewModel.isStale(currentSpec)
    var drillResult by remember { mutableStateOf<QueryResult?>(null) }

    drillResult?.let { result ->
        ExploreDrillDialog(
            result = result,
            onExport = {
                chooseCsvFile("explore-details")?.let { viewModel.saveResultCsv(result, it) }
            },
            onDismiss = { drillResult = null },
        )
    }

    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Explore", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    "${session.connectionLabel} · Based on ${session.sample.rowCount} sampled rows",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    exploreConfigSummary(config),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (session.sample.truncated) StatusChip("Truncated", StatusChipKind.WARNING)
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Close Explore")
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            ExploreConfigPanel(
                config = config,
                fields = fields,
                sample = session.sample,
                onConfigChange = { next -> viewModel.updateConfig { next } },
                memberOptionsFor = viewModel::memberOptions,
                onApplyTemplate = viewModel::applyTemplate,
                configDirty = viewModel.isDirty(),
                onReset = viewModel::resetConfig,
                resetEnabled = !viewModel.isDefaultConfig(),
                modifier = Modifier.width(320.dp).fillMaxHeight(),
            )

            Box(modifier = Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outline))

            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                if (session.sample.truncated || stale) {
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        if (session.sample.truncated) {
                            MessageBanner(
                                text = "This view is based on a truncated sample, so totals may not represent the full result. Close Explore and raise the row limit in Builder for a larger sample.",
                                kind = BannerKind.WARNING,
                                action = {
                                    SecondaryButton(onClick = onClose) {
                                        Text("Close and adjust in Builder")
                                    }
                                },
                            )
                        }
                        if (stale) {
                            MessageBanner(
                                text = if (sampleRefreshEnabled) {
                                    "The builder query changed. Refresh the Explore sample from the latest Builder results."
                                } else {
                                    "The builder query changed. Re-run the query in Builder, then refresh or reopen Explore."
                                },
                                kind = BannerKind.WARNING,
                                action = if (sampleRefreshEnabled && onRefreshSample != null) {
                                    {
                                        PrimaryButton(onClick = onRefreshSample) {
                                            Text("Refresh sample")
                                        }
                                    }
                                } else {
                                    null
                                },
                            )
                        }
                    }
                }

                ExploreFilterStrip(
                    filters = config.filters.filterIsInstance<PivotFilter.Members>().filter { it.pinned },
                    optionsFor = viewModel::memberOptions,
                    onSelectionChange = viewModel::updateMemberFilter,
                )

                ExplorePivotTable(
                    preview = preview,
                    config = config,
                    onConfigChange = { next -> viewModel.updateConfig { next } },
                    onToggleRow = viewModel::toggleRowPath,
                    onToggleColumn = viewModel::toggleColumnPath,
                    onDrill = { rowPath, columnPath, measureAlias ->
                        drillResult = viewModel.sourceRowsFor(rowPath, columnPath, measureAlias)
                    },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )

                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    viewModel.exportMessage?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    viewModel.exportError?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    Box(modifier = Modifier.weight(1f))
                    if (viewModel.exportMessage != null || viewModel.exportError != null) {
                        SecondaryButton(onClick = viewModel::clearExportMessages) {
                            Text("Dismiss")
                        }
                    }
                    PrimaryButton(
                        modifier = Modifier.padding(start = 8.dp),
                        onClick = {
                            chooseCsvFile(session.connectionLabel)?.let(viewModel::savePreviewCsv)
                        },
                    ) {
                        Text("Export CSV")
                    }
                }
            }
        }
    }
}

private fun chooseCsvFile(connectionLabel: String): java.nio.file.Path? {
    val safeName = connectionLabel.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "explore" }
    val chooser = JFileChooser().apply {
        selectedFile = File("explore-$safeName.csv")
        dialogTitle = "Export Explore CSV"
    }
    val result = chooser.showSaveDialog(null)
    if (result != JFileChooser.APPROVE_OPTION) return null
    val file = chooser.selectedFile
    return if (file.extension.equals("csv", ignoreCase = true)) file.toPath() else File("${file.path}.csv").toPath()
}
