package com.safedb.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.safedb.explore.ExploreMode
import com.safedb.explore.ExploreRecipe
import com.safedb.explore.PivotFilter
import com.safedb.model.ConnectionDef
import com.safedb.model.QueryResult
import com.safedb.model.QuerySpec
import com.safedb.ui.components.BannerKind
import com.safedb.ui.components.MessageBanner
import com.safedb.ui.components.PrimaryButton
import com.safedb.ui.components.SecondaryButton
import com.safedb.ui.components.StatusChip
import com.safedb.ui.components.StatusChipKind
import com.safedb.ui.theme.SafeDbTheme
import com.safedb.viewmodel.ExploreViewModel
import com.safedb.viewmodel.RecipesViewModel
import java.io.File
import javax.swing.JFileChooser

@Composable
fun ExploreWindowContent(
    viewModel: ExploreViewModel,
    currentSpec: QuerySpec,
    onClose: () -> Unit,
    onRefreshSample: (() -> Unit)? = null,
    sampleRefreshEnabled: Boolean = false,
    recipesViewModel: RecipesViewModel,
    connections: List<ConnectionDef> = emptyList(),
    onRunRecipe: (ExploreRecipe, ConnectionDef) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    val session = viewModel.session
    val preview = viewModel.preview
    val activePreviewLoading =
        when (viewModel.workspace.activeMode) {
            ExploreMode.Pivot -> viewModel.pivotPreviewState.loading
            ExploreMode.Worksheet -> viewModel.worksheetPreviewState.loading
            ExploreMode.Visualization -> viewModel.visualizationPreviewState.loading
        }
    val config = viewModel.config
    val fields =
        remember(session.sample.columns, session.baseSpec.tables) {
            buildExploreFieldOptions(session.sample, session.baseSpec.tables)
        }
    val stale = viewModel.isStale(currentSpec)
    var drillResult by remember { mutableStateOf<QueryResult?>(null) }
    var pivotRailVisible by remember { mutableStateOf(true) }
    var worksheetRailVisible by remember { mutableStateOf(true) }
    var visualizationRailVisible by remember { mutableStateOf(true) }

    drillResult?.let { result ->
        ExploreDrillDialog(
            result = result,
            onExport = {
                chooseCsvFile("explore-details")?.let { viewModel.saveResultCsv(result, it) }
            },
            onDismiss = { drillResult = null },
        )
    }

    Column(modifier = modifier.fillMaxSize().background(SafeDbTheme.colors.workspaceBackground)) {
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .background(SafeDbTheme.colors.workspaceHeader)
                    .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Explore",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "${session.connectionLabel} · Based on ${session.sample.rowCount} sampled rows",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    exploreWorkspaceSummary(viewModel),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (session.sample.truncated) StatusChip("Truncated", StatusChipKind.WARNING)
            if (activePreviewLoading) {
                Text(
                    "Refreshing…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ExploreModeSelector(
                selected = viewModel.workspace.activeMode,
                onSelect = viewModel::selectMode,
                modifier = Modifier.padding(horizontal = 10.dp),
            )
            ExploreRecipeActions(viewModel, recipesViewModel, connections, onRunRecipe)
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Close Explore")
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
            ExploreStaleSampleWarning(
                stale = stale,
                sampleRefreshEnabled = sampleRefreshEnabled,
                onRefreshSample = onRefreshSample,
            )
            when (viewModel.workspace.activeMode) {
                ExploreMode.Pivot ->
                    Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        if (pivotRailVisible) {
                            ExploreConfigPanel(
                                config = config,
                                fields = fields,
                                onConfigChange = { next -> viewModel.updateConfig { next } },
                                memberOptionsFor = viewModel::memberOptions,
                                onReset = viewModel::resetConfig,
                                resetEnabled = !viewModel.isDefaultConfig(),
                                onHide = { pivotRailVisible = false },
                                modifier = Modifier.width(320.dp).fillMaxHeight(),
                            )
                        } else {
                            CollapsedExploreRail(ExploreMode.Pivot) { pivotRailVisible = true }
                        }
                        Box(
                            modifier =
                                Modifier.width(1.dp)
                                    .fillMaxHeight()
                                    .background(MaterialTheme.colorScheme.outline)
                        )
                        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                            ExploreFilterStrip(
                                filters =
                                    config.filters.filterIsInstance<PivotFilter.Members>().filter {
                                        it.pinned
                                    },
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
                                    if (!viewModel.pivotPreviewState.loading) {
                                        drillResult =
                                            viewModel.sourceRowsFor(
                                                rowPath,
                                                columnPath,
                                                measureAlias,
                                            )
                                    }
                                },
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                            )
                            ExploreExportBar(
                                viewModel,
                                enabled = !viewModel.pivotPreviewState.loading,
                            ) {
                                chooseCsvFile(session.connectionLabel)
                                    ?.let(viewModel::savePreviewCsv)
                            }
                        }
                    }
                ExploreMode.Worksheet ->
                    Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        ExploreWorksheet(
                            sample = session.sample,
                            config = viewModel.worksheetConfig,
                            preview = viewModel.worksheetPreview,
                            onConfigChange = { next -> viewModel.updateWorksheet { next } },
                            onColumnLayoutChange = viewModel::updateWorksheetColumnLayout,
                            onToggleGroup = viewModel::toggleWorksheetGroup,
                            configReplacementRevision =
                                viewModel.worksheetConfigReplacementRevision,
                            railVisible = worksheetRailVisible,
                            onRailVisibilityChange = { worksheetRailVisible = it },
                            railFooter = { collapsed ->
                                val enabled =
                                    !viewModel.worksheetPreviewState.loading &&
                                        viewModel.hasVisibleWorksheetColumns()
                                val export: () -> Unit = export@{
                                    val path =
                                        chooseCsvFile("${session.connectionLabel}-worksheet")
                                            ?: return@export
                                    viewModel.saveWorksheetCsv(path)
                                }
                                if (collapsed) {
                                    IconButton(
                                        onClick = export,
                                        enabled = enabled && !viewModel.exporting,
                                    ) {
                                        Icon(
                                            Icons.Default.Download,
                                            contentDescription = "Export CSV",
                                        )
                                    }
                                } else {
                                    ExploreExportBar(
                                        viewModel,
                                        enabled = enabled,
                                        verticalPadding = 4.dp,
                                        showStatus = false,
                                        onExport = export,
                                    )
                                }
                            },
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        )
                        ExploreExportStatus(viewModel)
                    }
                ExploreMode.Visualization ->
                    Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            if (visualizationRailVisible) {
                                VisualizationConfigPanel(
                                    config = viewModel.visualizationConfig,
                                    sample = session.sample,
                                    tables = session.baseSpec.tables,
                                    fields = fields,
                                    memberOptionsFor = viewModel::memberOptions,
                                    onConfigChange = { next ->
                                        viewModel.updateVisualization { next }
                                    },
                                    onApplyTemplate = viewModel::applyVisualizationTemplate,
                                    onReset = viewModel::resetVisualization,
                                    onHide = { visualizationRailVisible = false },
                                    modifier = Modifier.width(320.dp).fillMaxHeight(),
                                )
                            } else {
                                CollapsedExploreRail(ExploreMode.Visualization) {
                                    visualizationRailVisible = true
                                }
                            }
                            Box(
                                modifier =
                                    Modifier.width(1.dp)
                                        .fillMaxHeight()
                                        .background(MaterialTheme.colorScheme.outline)
                            )
                            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                Column(
                                    modifier = Modifier.weight(1f).fillMaxWidth().padding(16.dp)
                                ) {
                                    viewModel.visualizationPreview.warnings.firstOrNull()?.let {
                                        warning ->
                                        MessageBanner(text = warning, kind = BannerKind.WARNING)
                                    }
                                    VisualizationChart(
                                        preview = viewModel.visualizationPreview,
                                        config = viewModel.visualizationConfig,
                                        sampleRowCount = session.sample.rowCount,
                                        sampleTruncated = session.sample.truncated,
                                        onMarkClick = { markId ->
                                            if (!viewModel.visualizationPreviewState.loading) {
                                                drillResult =
                                                    viewModel.sourceRowsForVisualizationMark(markId)
                                            }
                                        },
                                        modifier = Modifier.weight(1f).fillMaxWidth(),
                                    )
                                }
                                val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
                                val themePalette = SafeDbTheme.palette
                                VisualizationExportBar(
                                    viewModel = viewModel,
                                    enabled =
                                        viewModel.visualizationPreview.ready &&
                                            !viewModel.visualizationPreviewState.loading,
                                    onExportCsv = {
                                        chooseExportFile(
                                                "${session.connectionLabel}-chart-data",
                                                "csv",
                                            )
                                            ?.let(viewModel::saveVisualizationCsv)
                                    },
                                    onExportPng = {
                                        chooseExportFile("${session.connectionLabel}-chart", "png")
                                            ?.let {
                                                viewModel.saveVisualizationPng(
                                                    it,
                                                    isDark,
                                                    themePalette,
                                                )
                                            }
                                    },
                                )
                            }
                        }
                    }
            }
        }
    }
}

@Composable
private fun CollapsedExploreRail(mode: ExploreMode, onShow: () -> Unit) {
    Surface(
        modifier = Modifier.width(48.dp).fillMaxHeight(),
        color = SafeDbTheme.colors.workspacePanel,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            ModeIcon(mode, Modifier.padding(top = 14.dp).size(18.dp))
            IconButton(onClick = onShow) {
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = "Show ${mode.displayName()} sidebar",
                )
            }
        }
    }
}

@Composable
private fun ExploreModeSelector(
    selected: ExploreMode,
    onSelect: (ExploreMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(3.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(modifier = Modifier.padding(3.dp)) {
            ExploreMode.entries.forEach { mode ->
                Surface(
                    shape = RoundedCornerShape(3.dp),
                    color =
                        if (mode == selected) MaterialTheme.colorScheme.primaryContainer
                        else Color.Transparent,
                    modifier = Modifier.clickable { onSelect(mode) },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ModeIcon(mode, Modifier.size(16.dp))
                        Text(
                            mode.displayName(),
                            modifier = Modifier.padding(start = 5.dp),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight =
                                if (mode == selected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ExploreStaleSampleWarning(
    stale: Boolean,
    sampleRefreshEnabled: Boolean,
    onRefreshSample: (() -> Unit)?,
) {
    if (!stale) return
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        MessageBanner(
            text =
                if (sampleRefreshEnabled)
                    "The builder query changed. Refresh the Explore sample from the latest Builder results."
                else
                    "The builder query changed. Re-run the query in Builder, then refresh or reopen Explore.",
            kind = BannerKind.WARNING,
            action =
                if (sampleRefreshEnabled && onRefreshSample != null) {
                    { PrimaryButton(onClick = onRefreshSample) { Text("Refresh sample") } }
                } else null,
        )
    }
}

@Composable
private fun ExploreExportBar(
    viewModel: ExploreViewModel,
    enabled: Boolean,
    verticalPadding: Dp = 12.dp,
    showStatus: Boolean = true,
    onExport: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = verticalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showStatus) {
            viewModel.exportMessage?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            viewModel.exportError?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        Box(modifier = Modifier.weight(1f))
        if (showStatus && (viewModel.exportMessage != null || viewModel.exportError != null)) {
            SecondaryButton(onClick = viewModel::clearExportMessages) { Text("Dismiss") }
        }
        if (showStatus && viewModel.exporting)
            Text("Exporting…", style = MaterialTheme.typography.bodySmall)
        PrimaryButton(
            modifier = Modifier.padding(start = 8.dp),
            onClick = onExport,
            enabled = enabled && !viewModel.exporting,
        ) {
            Text("Export CSV")
        }
    }
}

@Composable
private fun ExploreExportStatus(viewModel: ExploreViewModel) {
    if (viewModel.exportMessage == null && viewModel.exportError == null && !viewModel.exporting)
        return
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        viewModel.exportMessage?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        viewModel.exportError?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (viewModel.exporting) {
            Text("Exporting…", style = MaterialTheme.typography.bodySmall)
        }
        Box(modifier = Modifier.weight(1f))
        if (viewModel.exportMessage != null || viewModel.exportError != null) {
            SecondaryButton(onClick = viewModel::clearExportMessages) { Text("Dismiss") }
        }
    }
}

@Composable
private fun VisualizationExportBar(
    viewModel: ExploreViewModel,
    enabled: Boolean,
    onExportCsv: () -> Unit,
    onExportPng: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        viewModel.exportMessage?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        viewModel.exportError?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Box(modifier = Modifier.weight(1f))
        if (viewModel.exportMessage != null || viewModel.exportError != null) {
            SecondaryButton(onClick = viewModel::clearExportMessages) { Text("Dismiss") }
        }
        if (viewModel.exporting) Text("Exporting…", style = MaterialTheme.typography.bodySmall)
        SecondaryButton(
            modifier = Modifier.padding(start = 8.dp),
            onClick = onExportCsv,
            enabled = enabled && !viewModel.exporting,
        ) {
            Text("Export chart data")
        }
        PrimaryButton(
            modifier = Modifier.padding(start = 8.dp),
            onClick = onExportPng,
            enabled = enabled && !viewModel.exporting,
        ) {
            Text("Export PNG")
        }
    }
}

private fun exploreWorkspaceSummary(viewModel: ExploreViewModel): String =
    when (viewModel.workspace.activeMode) {
        ExploreMode.Pivot -> exploreConfigSummary(viewModel.config)
        ExploreMode.Worksheet ->
            "${viewModel.worksheetConfig.groups.size} groups · ${viewModel.worksheetConfig.filters.size} filters · ${viewModel.worksheetConfig.calculations.size} calculations"
        ExploreMode.Visualization ->
            if (viewModel.visualizationConfig.isConfigured()) {
                "${viewModel.visualizationConfig.chartType.name} · ${viewModel.visualizationPreview.marks.size} plotted values"
            } else {
                "No chart configured"
            }
    }

private fun chooseCsvFile(connectionLabel: String): java.nio.file.Path? {
    val safeName =
        connectionLabel.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty {
            "explore"
        }
    val chooser =
        JFileChooser().apply {
            selectedFile = File("explore-$safeName.csv")
            dialogTitle = "Export Explore CSV"
        }
    val result = chooser.showSaveDialog(null)
    if (result != JFileChooser.APPROVE_OPTION) return null
    val file = chooser.selectedFile
    return if (file.extension.equals("csv", ignoreCase = true)) file.toPath()
    else File("${file.path}.csv").toPath()
}

private fun chooseExportFile(connectionLabel: String, extension: String): java.nio.file.Path? {
    val safeName =
        connectionLabel.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty {
            "explore"
        }
    val chooser =
        JFileChooser().apply {
            selectedFile = File("explore-$safeName.$extension")
            dialogTitle = "Export Explore ${extension.uppercase()}"
        }
    val result = chooser.showSaveDialog(null)
    if (result != JFileChooser.APPROVE_OPTION) return null
    val file = chooser.selectedFile
    return if (file.extension.equals(extension, ignoreCase = true)) file.toPath()
    else File("${file.path}.$extension").toPath()
}
