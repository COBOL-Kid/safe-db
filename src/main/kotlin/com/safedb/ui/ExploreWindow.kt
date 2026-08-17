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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
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
import com.safedb.ui.theme.ScreenHeaderHorizontalPadding
import com.safedb.ui.theme.ToolbarHeaderVerticalPadding
import com.safedb.viewmodel.ExploreOrigin
import com.safedb.viewmodel.ExploreViewModel
import com.safedb.viewmodel.RecipesViewModel
import java.io.File
import javax.swing.JFileChooser

@Composable
fun ExploreWindowContent(
    viewModel: ExploreViewModel,
    currentSpec: QuerySpec?,
    onClose: () -> Unit,
    origin: ExploreOrigin = ExploreOrigin.Builder,
    onRefreshSample: (() -> Unit)? = null,
    sampleRefreshEnabled: Boolean = false,
    recipesViewModel: RecipesViewModel,
    connections: List<ConnectionDef> = emptyList(),
    onRunRecipe: (ExploreRecipe, ConnectionDef) -> Unit = { _, _ -> },
    activeConnectionId: String? = viewModel.session.connectionId,
    modifier: Modifier = Modifier,
) {
    val session = viewModel.session
    val preview = viewModel.preview
    val activeMode = viewModel.workspace.activeMode
    val activePreviewLoading = viewModel.isLoading(activeMode)
    val config = viewModel.config
    val fields =
        remember(session.sample.columns, session.baseSpec.tables) {
            buildExploreFieldOptions(session.sample, session.baseSpec.tables)
        }
    val stale = viewModel.isStale(currentSpec, activeConnectionId)
    val connectionStale = activeConnectionId != session.connectionId
    var drillResult by remember { mutableStateOf<QueryResult?>(null) }
    var pivotRailVisible by remember { mutableStateOf(true) }
    var worksheetRailVisible by remember { mutableStateOf(true) }
    var visualizationRailVisible by remember { mutableStateOf(true) }

    drillResult?.let { result ->
        ExploreDrillDialog(
            result = result,
            onExport = {
                chooseExportFile("explore-details", "csv")?.let {
                    viewModel.saveResultCsv(result, it)
                }
            },
            onDismiss = { drillResult = null },
        )
    }

    Column(modifier = modifier.fillMaxSize().background(SafeDbTheme.colors.workspaceBackground)) {
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .background(SafeDbTheme.colors.workspaceHeader)
                    .padding(
                        horizontal = ScreenHeaderHorizontalPadding,
                        vertical = ToolbarHeaderVerticalPadding,
                    ),
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
            }
            exploreTruncationExplanation(session.sample.truncated)?.let { explanation ->
                ExploreTruncationBadge(explanation)
            }
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
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
            ExploreStaleSampleWarning(
                stale = stale,
                origin = origin,
                sampleRefreshEnabled = sampleRefreshEnabled,
                connectionStale = connectionStale,
                onRefreshSample = onRefreshSample,
            )
            ExplorePreviewErrorBanner(viewModel.previewError(activeMode))
            when (activeMode) {
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
                                    if (!viewModel.isLoading(ExploreMode.Pivot)) {
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
                                enabled = !viewModel.isLoading(ExploreMode.Pivot),
                            ) {
                                chooseExportFile(session.connectionLabel, "csv")
                                    ?.let(viewModel::savePreviewCsv)
                            }
                        }
                    }
                ExploreMode.Worksheet ->
                    ExploreWorksheet(
                        sample = session.sample,
                        config = viewModel.worksheetConfig,
                        preview = viewModel.worksheetPreview,
                        onConfigChange = { next -> viewModel.updateWorksheet { next } },
                        onColumnLayoutChange = viewModel::updateWorksheetColumnLayout,
                        onToggleGroup = viewModel::toggleWorksheetGroup,
                        configReplacementRevision = viewModel.worksheetConfigReplacementRevision,
                        railVisible = worksheetRailVisible,
                        onRailVisibilityChange = { worksheetRailVisible = it },
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        exportBar = {
                            ExploreExportBar(
                                viewModel,
                                enabled =
                                    !viewModel.isLoading(ExploreMode.Worksheet) &&
                                        viewModel.hasVisibleWorksheetColumns(),
                            ) {
                                chooseExportFile("${session.connectionLabel}-worksheet", "csv")
                                    ?.let(viewModel::saveWorksheetCsv)
                            }
                        },
                    )
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
                                            if (!viewModel.isLoading(ExploreMode.Visualization)) {
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
                                            !viewModel.isLoading(ExploreMode.Visualization),
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
@OptIn(ExperimentalMaterial3Api::class)
private fun ExploreTruncationBadge(explanation: String) {
    val colors = SafeDbTheme.colors
    TooltipBox(
        positionProvider =
            TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = {
            PlainTooltip(
                maxWidth = 320.dp,
                containerColor = colors.warningContainer,
                contentColor = colors.onWarningContainer,
            ) {
                Text(explanation)
            }
        },
        state = rememberTooltipState(),
    ) {
        StatusChip(
            "Truncated",
            StatusChipKind.WARNING,
            modifier =
                Modifier.semantics(mergeDescendants = true) {
                    contentDescription = "Truncated. $explanation"
                },
        )
    }
}

@Composable
internal fun CollapsedExploreRail(mode: ExploreMode, onShow: () -> Unit) {
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
    origin: ExploreOrigin,
    sampleRefreshEnabled: Boolean,
    connectionStale: Boolean,
    onRefreshSample: (() -> Unit)?,
) {
    if (!stale) return
    // Refresh pulls from whichever surface opened this window, so the instructions have to name it
    // —
    // telling a SQL-origin user to re-run in Builder never produces a sample and leaves Refresh
    // off.
    val surface =
        when (origin) {
            ExploreOrigin.Builder -> "Builder"
            ExploreOrigin.Sql -> "SQL"
        }
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        MessageBanner(
            text =
                when {
                    connectionStale ->
                        "The active connection changed. Reopen Explore from the query you " +
                            "want to inspect; Refresh cannot update this sample."
                    sampleRefreshEnabled ->
                        "The $surface query changed. Refresh the Explore sample from the latest $surface results."
                    else ->
                        "The $surface query changed. Re-run the query in $surface, then refresh or reopen Explore."
                },
            kind = BannerKind.WARNING,
            action =
                if (!connectionStale && sampleRefreshEnabled && onRefreshSample != null) {
                    { PrimaryButton(onClick = onRefreshSample) { Text("Refresh sample") } }
                } else null,
        )
    }
}

@Composable
private fun ExplorePreviewErrorBanner(error: String?) {
    if (error == null) return
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        MessageBanner(
            text = "This Explore view could not be refreshed: $error",
            kind = BannerKind.ERROR,
        )
    }
}

@Composable
private fun ExportBar(viewModel: ExploreViewModel, actions: @Composable () -> Unit) {
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
        actions()
    }
}

@Composable
private fun ExploreExportBar(
    viewModel: ExploreViewModel,
    enabled: Boolean,
    onExport: () -> Unit,
) {
    ExportBar(viewModel) {
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
private fun VisualizationExportBar(
    viewModel: ExploreViewModel,
    enabled: Boolean,
    onExportCsv: () -> Unit,
    onExportPng: () -> Unit,
) {
    ExportBar(viewModel) {
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
