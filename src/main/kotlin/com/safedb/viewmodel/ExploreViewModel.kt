package com.safedb.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.safedb.explore.ExploreConfig
import com.safedb.explore.ExploreMode
import com.safedb.explore.ExplorePreviewResult
import com.safedb.explore.ExploreRecipe
import com.safedb.explore.ExploreSession
import com.safedb.explore.ExploreWorkspaceState
import com.safedb.explore.PivotFilter
import com.safedb.explore.VisualizationConfig
import com.safedb.explore.VisualizationPreview
import com.safedb.explore.WorksheetColumnLayout
import com.safedb.explore.WorksheetConfig
import com.safedb.explore.WorksheetPreview
import com.safedb.explore.applyExplore
import com.safedb.explore.applyVisualization
import com.safedb.explore.applyWorksheet
import com.safedb.explore.exploreSpecHash
import com.safedb.explore.pivotCellKey
import com.safedb.explore.pivotCellLineageKey
import com.safedb.explore.projectWorksheetTable
import com.safedb.explore.remapRecipe
import com.safedb.explore.resolveRecipeFields
import com.safedb.explore.resolveWorksheetColumnLayout
import com.safedb.explore.withoutTransientState
import com.safedb.export.writeQueryResultCsv
import com.safedb.export.writeVisualizationPng
import com.safedb.model.QueryResult
import com.safedb.model.QuerySpec
import com.safedb.model.ResultCell
import com.safedb.model.ThemePalette
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PreviewState<T>(val value: T, val loading: Boolean = false, val error: String? = null)

class ExploreViewModel(
    val session: ExploreSession,
    initialConfig: ExploreConfig? = null,
    initialWorkspace: ExploreWorkspaceState? = null,
    private val computationScope: CoroutineScope? = null,
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val defaultConfig = ExploreConfig.defaultFor(session.sample, session.baseSpec.tables)

    var workspace by
        mutableStateOf(
            initialWorkspace ?: ExploreWorkspaceState(pivot = initialConfig ?: defaultConfig)
        )
        private set

    val config: ExploreConfig
        get() = workspace.pivot

    val worksheetConfig: WorksheetConfig
        get() = workspace.worksheet

    val visualizationConfig: VisualizationConfig
        get() = workspace.visualization

    var preview by mutableStateOf(applyExplore(session.sample, workspace.pivot))
        private set

    var worksheetPreview by
        mutableStateOf(applyWorksheet(session.sample, workspace.worksheet, session.baseSpec.tables))
        private set

    var visualizationPreview by
        mutableStateOf(
            applyVisualization(session.sample, workspace.visualization, session.baseSpec.tables)
        )
        private set

    var pivotPreviewState by mutableStateOf(PreviewState(preview))
        private set

    var worksheetPreviewState by mutableStateOf(PreviewState(worksheetPreview))
        private set

    var visualizationPreviewState by mutableStateOf(PreviewState(visualizationPreview))
        private set

    private val pivotTask = PreviewTask<ExplorePreviewResult>()
    private val worksheetTask = PreviewTask<WorksheetPreview>()
    private val visualizationTask = PreviewTask<VisualizationPreview>()
    private val dirtyModes = mutableSetOf<ExploreMode>()
    private val memberOptionsCache = mutableMapOf<String, List<MemberOption>>()
    var appliedRecipeId by mutableStateOf<String?>(null)
        private set

    var worksheetConfigReplacementRevision by mutableStateOf(0)
        private set

    var pendingRecipe by mutableStateOf<ExploreRecipe?>(null)
        private set

    private var appliedRecipeBaseline: ExploreWorkspaceState? = null
    var exportError by mutableStateOf<String?>(null)
        private set

    var exportMessage by mutableStateOf<String?>(null)
        private set

    var exporting by mutableStateOf(false)
        private set

    fun updateConfig(block: (ExploreConfig) -> ExploreConfig) {
        workspace = workspace.copy(pivot = block(workspace.pivot))
        refreshMode(ExploreMode.Pivot)
        exportError = null
        exportMessage = null
    }

    fun updateWorksheet(block: (WorksheetConfig) -> WorksheetConfig) {
        workspace = workspace.copy(worksheet = block(workspace.worksheet))
        refreshMode(ExploreMode.Worksheet)
        clearExportMessages()
    }

    fun updateWorksheetColumnLayout(layout: List<WorksheetColumnLayout>) {
        workspace = workspace.copy(worksheet = workspace.worksheet.copy(columnLayout = layout))
        clearExportMessages()
    }

    fun hasVisibleWorksheetColumns(): Boolean =
        resolveWorksheetColumnLayout(worksheetPreview.columns, worksheetConfig.columnLayout).any {
            it.visible
        }

    fun updateVisualization(block: (VisualizationConfig) -> VisualizationConfig) {
        workspace = workspace.copy(visualization = block(workspace.visualization))
        refreshMode(ExploreMode.Visualization)
        clearExportMessages()
    }

    fun selectMode(mode: ExploreMode) {
        workspace = workspace.copy(activeMode = mode)
        if (mode in dirtyModes) refreshMode(mode)
        clearExportMessages()
    }

    fun resetConfig() {
        workspace = workspace.copy(pivot = defaultConfig)
        refreshMode(ExploreMode.Pivot)
        clearExportMessages()
    }

    fun resetVisualization() {
        workspace = workspace.copy(visualization = VisualizationConfig())
        refreshMode(ExploreMode.Visualization)
        clearExportMessages()
    }

    fun isDefaultConfig(): Boolean =
        workspace.pivot.withoutTransientState() == defaultConfig.withoutTransientState()

    fun isDefaultWorksheet(): Boolean =
        workspace.worksheet.withoutTransientState() == WorksheetConfig()

    fun isDefaultVisualization(): Boolean = workspace.visualization == VisualizationConfig()

    fun isDirty(): Boolean = !isDefaultConfig()

    fun applyTemplate(templateConfig: ExploreConfig) {
        workspace = workspace.copy(activeMode = ExploreMode.Pivot, pivot = templateConfig)
        refreshMode(ExploreMode.Pivot)
        clearExportMessages()
    }

    fun applyVisualizationTemplate(templateConfig: VisualizationConfig) {
        workspace =
            workspace.copy(activeMode = ExploreMode.Visualization, visualization = templateConfig)
        refreshMode(ExploreMode.Visualization)
        clearExportMessages()
    }

    fun applyRecipe(recipe: ExploreRecipe) {
        val worksheetWasReplaced =
            recipe.worksheet?.let { replacement -> replacement != workspace.worksheet } == true
        workspace =
            workspace.copy(
                activeMode = recipe.defaultMode,
                pivot = recipe.pivot ?: workspace.pivot,
                worksheet = recipe.worksheet ?: workspace.worksheet,
                visualization = recipe.visualization ?: workspace.visualization,
            )
        if (worksheetWasReplaced) worksheetConfigReplacementRevision += 1
        dirtyModes += setOf(ExploreMode.Pivot, ExploreMode.Worksheet, ExploreMode.Visualization)
        refreshMode(workspace.activeMode)
        appliedRecipeId = recipe.id
        appliedRecipeBaseline = workspace.recipeSnapshot()
        clearExportMessages()
    }

    fun requestRecipe(recipe: ExploreRecipe) {
        val mapping = resolveRecipeFields(recipe, session.sample, session.baseSpec)
        if (mapping.unresolved.isEmpty()) {
            applyRecipe(remapRecipe(recipe, mapping.resolved))
        } else {
            pendingRecipe = recipe
        }
    }

    fun applyPendingRecipe(mapping: Map<String, String>) {
        val recipe = pendingRecipe ?: return
        pendingRecipe = null
        applyRecipe(remapRecipe(recipe, mapping))
    }

    fun dismissPendingRecipe() {
        pendingRecipe = null
    }

    fun recipeDirty(): Boolean =
        appliedRecipeBaseline?.let { baseline -> workspace.recipeSnapshot() != baseline } ?: false

    fun clearAppliedRecipe() {
        appliedRecipeId = null
        appliedRecipeBaseline = null
    }

    fun inheritRecipeTrackingFrom(previous: ExploreViewModel) {
        appliedRecipeId = previous.appliedRecipeId
        appliedRecipeBaseline = previous.appliedRecipeBaseline
    }

    fun toggleRowPath(pathKey: String) {
        updateConfig { it.copy(collapsedRowPaths = it.collapsedRowPaths.toggle(pathKey)) }
    }

    fun toggleColumnPath(pathKey: String) {
        updateConfig { it.copy(collapsedColumnPaths = it.collapsedColumnPaths.toggle(pathKey)) }
    }

    fun toggleWorksheetGroup(pathKey: String) {
        updateWorksheet { current ->
            current.copy(collapsedGroupPaths = current.collapsedGroupPaths.toggle(pathKey))
        }
    }

    fun memberOptions(column: String): List<MemberOption> {
        return memberOptionsCache.getOrPut(column) {
            val index = session.sample.columns.indexOfFirst { it.name == column }
            if (index < 0) return@getOrPut emptyList()
            val cells = linkedMapOf<String, Pair<ResultCell?, Int>>()
            session.sample.rows.forEach { row ->
                val cell = row.getOrNull(index)
                val key = pivotCellKey(cell)
                val current = cells[key]
                cells[key] = (current?.first ?: cell) to ((current?.second ?: 0) + 1)
            }
            cells
                .map { (key, value) -> MemberOption(key, memberLabel(value.first), value.second) }
                .sortedBy { it.label }
        }
    }

    private fun refreshMode(mode: ExploreMode) {
        dirtyModes += mode
        if (workspace.activeMode != mode) return
        val scope = computationScope
        if (scope == null) {
            computeModeNow(mode)
            return
        }
        when (mode) {
            ExploreMode.Pivot -> {
                val config = workspace.pivot
                pivotTask.schedule(
                    scope,
                    computeDispatcher,
                    { applyExplore(session.sample, config) },
                    { pivotPreviewState = pivotPreviewState.copy(loading = true, error = null) },
                    { result ->
                        preview = result
                        pivotPreviewState = PreviewState(result)
                        dirtyModes -= mode
                    },
                    { error ->
                        pivotPreviewState = pivotPreviewState.copy(loading = false, error = error)
                    },
                )
            }
            ExploreMode.Worksheet -> {
                val config = workspace.worksheet
                worksheetTask.schedule(
                    scope,
                    computeDispatcher,
                    { applyWorksheet(session.sample, config, session.baseSpec.tables) },
                    {
                        worksheetPreviewState =
                            worksheetPreviewState.copy(loading = true, error = null)
                    },
                    { result ->
                        worksheetPreview = result
                        worksheetPreviewState = PreviewState(result)
                        dirtyModes -= mode
                    },
                    { error ->
                        worksheetPreviewState =
                            worksheetPreviewState.copy(loading = false, error = error)
                    },
                )
            }
            ExploreMode.Visualization -> {
                val config = workspace.visualization
                visualizationTask.schedule(
                    scope,
                    computeDispatcher,
                    { applyVisualization(session.sample, config, session.baseSpec.tables) },
                    {
                        visualizationPreviewState =
                            visualizationPreviewState.copy(loading = true, error = null)
                    },
                    { result ->
                        visualizationPreview = result
                        visualizationPreviewState = PreviewState(result)
                        dirtyModes -= mode
                    },
                    { error ->
                        visualizationPreviewState =
                            visualizationPreviewState.copy(loading = false, error = error)
                    },
                )
            }
        }
    }

    private fun computeModeNow(mode: ExploreMode) {
        when (mode) {
            ExploreMode.Pivot -> {
                preview = applyExplore(session.sample, workspace.pivot)
                pivotPreviewState = PreviewState(preview)
            }
            ExploreMode.Worksheet -> {
                worksheetPreview =
                    applyWorksheet(session.sample, workspace.worksheet, session.baseSpec.tables)
                worksheetPreviewState = PreviewState(worksheetPreview)
            }
            ExploreMode.Visualization -> {
                visualizationPreview =
                    applyVisualization(
                        session.sample,
                        workspace.visualization,
                        session.baseSpec.tables,
                    )
                visualizationPreviewState = PreviewState(visualizationPreview)
            }
        }
        dirtyModes -= mode
    }

    fun close() {
        pivotTask.cancel()
        worksheetTask.cancel()
        visualizationTask.cancel()
    }

    fun updateMemberFilter(filterId: String, includedKeys: Set<String>) {
        updateConfig { current ->
            current.copy(
                filters =
                    current.filters.map { filter ->
                        if (filter is PivotFilter.Members && filter.id == filterId) {
                            filter.copy(includedKeys = includedKeys)
                        } else {
                            filter
                        }
                    }
            )
        }
    }

    fun sourceRowsFor(rowPath: String, columnPath: String, measureAlias: String): QueryResult {
        val indexes =
            preview.layout.cellLineage[pivotCellLineageKey(rowPath, columnPath, measureAlias)]
                .orEmpty()
        val rows = indexes.mapNotNull(session.sample.rows::getOrNull)
        return QueryResult(
            columns = session.sample.columns,
            rows = rows,
            rowCount = rows.size,
            truncated = session.sample.truncated,
            warnings = session.sample.warnings,
        )
    }

    fun sourceRowsForVisualizationMark(markId: String): QueryResult {
        val indexes =
            visualizationPreview.marks.firstOrNull { it.id == markId }?.sourceRowIndices.orEmpty()
        val rows = indexes.mapNotNull(session.sample.rows::getOrNull)
        return QueryResult(
            columns = session.sample.columns,
            rows = rows,
            rowCount = rows.size,
            truncated = session.sample.truncated,
            warnings = session.sample.warnings,
        )
    }

    fun isStale(currentSpec: QuerySpec): Boolean =
        exploreSpecHash(currentSpec) != session.baseSpecHash

    fun savePreviewCsv(path: Path) {
        val formattedRows = preview.layout.formattedRows
        val result =
            if (formattedRows.size == preview.result.rows.size) {
                preview.result.copy(rows = formattedRows.map { row -> row.map(ResultCell::text) })
            } else {
                preview.result
            }
        saveResultCsv(result, path)
    }

    fun saveWorksheetCsv(path: Path) {
        val projection = projectWorksheetTable(worksheetPreview, worksheetConfig.columnLayout)
        if (projection.columns.isEmpty()) {
            exportMessage = null
            exportError = "Show at least one worksheet column before exporting."
            return
        }
        val rows =
            projection.rows.map { row ->
                buildList {
                    if (projection.hasRowLabels) {
                        add(row.rowLabel?.let(ResultCell::text) ?: ResultCell.Null)
                    }
                    row.cells.forEach { cell ->
                        add(cell.error?.let { ResultCell.text("Error: $it") } ?: cell.value)
                    }
                }
            }
        saveResultCsv(
            QueryResult(
                columns =
                    buildList {
                        if (projection.hasRowLabels) {
                            add(com.safedb.model.ResultColumn("Group", "text"))
                        }
                        projection.columns.mapTo(this) {
                            com.safedb.model.ResultColumn(it.label, it.dataType)
                        }
                    },
                rows = rows,
                rowCount = rows.size,
                truncated = session.sample.truncated,
                warnings = worksheetPreview.warnings,
            ),
            path,
        )
    }

    fun saveVisualizationCsv(path: Path) {
        val result = visualizationPreview.exportResult
        if (result == null) {
            exportMessage = null
            exportError = "Complete the chart before exporting."
            return
        }
        saveResultCsv(result, path)
    }

    fun saveVisualizationPng(
        path: Path,
        isDark: Boolean,
        palette: ThemePalette = ThemePalette.DEFAULT,
    ) {
        executeExport("Exported chart PNG") {
            writeVisualizationPng(
                preview = visualizationPreview,
                config = visualizationConfig,
                sampleRowCount = session.sample.rowCount,
                sampleTruncated = session.sample.truncated,
                isDark = isDark,
                palette = palette,
                path = path,
            )
        }
    }

    fun saveResultCsv(result: QueryResult, path: Path) {
        executeExport("Exported ${result.rowCount} row${if (result.rowCount == 1) "" else "s"}") {
            Files.newOutputStream(path).use { output -> writeQueryResultCsv(result, output) }
        }
    }

    private fun executeExport(successMessage: String, block: () -> Unit) {
        val scope = computationScope
        if (scope == null) {
            completeExport(runCatching(block), successMessage)
            return
        }
        scope.launch {
            exporting = true
            exportError = null
            exportMessage = null
            val outcome = withContext(ioDispatcher) { runCatching(block) }
            completeExport(outcome, successMessage)
        }
    }

    private fun completeExport(outcome: Result<Unit>, successMessage: String) {
        exporting = false
        outcome.fold(
            onSuccess = {
                exportError = null
                exportMessage = successMessage
            },
            onFailure = {
                exportMessage = null
                exportError = it.message ?: it.toString()
            },
        )
    }

    fun clearExportMessages() {
        exportError = null
        exportMessage = null
    }
}

data class MemberOption(val key: String, val label: String, val count: Int)

private fun Set<String>.toggle(value: String): Set<String> =
    if (value in this) this - value else this + value

private fun ExploreWorkspaceState.recipeSnapshot(): ExploreWorkspaceState =
    copy(pivot = pivot.withoutTransientState(), worksheet = worksheet.withoutTransientState())

private fun memberLabel(cell: com.safedb.model.ResultCell?): String =
    when (cell) {
        null,
        is com.safedb.model.ResultCell.Null -> "(blank)"
        is com.safedb.model.ResultCell.BoolCell -> cell.value.toString()
        is com.safedb.model.ResultCell.IntegerCell -> cell.value.toString()
        is com.safedb.model.ResultCell.FloatCell -> cell.value.toString()
        is com.safedb.model.ResultCell.TextCell -> cell.value.text
        is com.safedb.model.ResultCell.BinaryCell -> cell.value.base64
    }

/** Latest-result-wins scheduling shared by the independent Explore evaluators. */
private class PreviewTask<T> {
    private var generation = 0
    private var job: Job? = null

    fun schedule(
        scope: CoroutineScope,
        dispatcher: CoroutineDispatcher,
        compute: () -> T,
        loading: () -> Unit,
        success: (T) -> Unit,
        failure: (String) -> Unit,
    ) {
        val scheduledGeneration = ++generation
        job?.cancel()
        loading()
        job = scope.launch {
            delay(PREVIEW_DEBOUNCE_MS)
            val outcome = runCatching { withContext(dispatcher) { compute() } }
            if (scheduledGeneration != generation) return@launch
            outcome.onSuccess(success).onFailure { error ->
                failure(error.message ?: error.toString())
            }
        }
    }

    fun cancel() {
        generation++
        job?.cancel()
    }
}

private const val PREVIEW_DEBOUNCE_MS = 75L
