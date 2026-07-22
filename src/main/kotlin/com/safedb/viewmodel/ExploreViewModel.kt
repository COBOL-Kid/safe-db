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
import com.safedb.explore.WorksheetConfig
import com.safedb.explore.WorksheetPreview
import com.safedb.explore.applyExplore
import com.safedb.explore.applyWorksheet
import com.safedb.explore.remapRecipe
import com.safedb.explore.resolveRecipeFields
import com.safedb.explore.withoutTransientState
import com.safedb.explore.exploreSpecHash
import com.safedb.explore.pivotCellKey
import com.safedb.explore.pivotCellLineageKey
import com.safedb.export.writeQueryResultCsv
import com.safedb.model.QueryResult
import com.safedb.model.ResultCell
import com.safedb.model.QuerySpec
import java.nio.file.Files
import java.nio.file.Path

class ExploreViewModel(
    val session: ExploreSession,
    initialConfig: ExploreConfig? = null,
    initialWorkspace: ExploreWorkspaceState? = null,
) {
    private val defaultConfig = ExploreConfig.defaultFor(session.sample, session.baseSpec.tables)

    var workspace by mutableStateOf(
        initialWorkspace ?: ExploreWorkspaceState(pivot = initialConfig ?: defaultConfig),
    )
        private set
    val config: ExploreConfig get() = workspace.pivot
    val worksheetConfig: WorksheetConfig get() = workspace.worksheet

    var preview by mutableStateOf(applyExplore(session.sample, workspace.pivot))
        private set
    var worksheetPreview by mutableStateOf(applyWorksheet(session.sample, workspace.worksheet, session.baseSpec.tables))
        private set
    var appliedRecipeId by mutableStateOf<String?>(null)
        private set
    var pendingRecipe by mutableStateOf<ExploreRecipe?>(null)
        private set
    private var appliedRecipeBaseline: ExploreWorkspaceState? = null
    var exportError by mutableStateOf<String?>(null)
        private set
    var exportMessage by mutableStateOf<String?>(null)
        private set

    fun updateConfig(block: (ExploreConfig) -> ExploreConfig) {
        workspace = workspace.copy(pivot = block(workspace.pivot))
        preview = applyExplore(session.sample, workspace.pivot)
        exportError = null
        exportMessage = null
    }

    fun updateWorksheet(block: (WorksheetConfig) -> WorksheetConfig) {
        workspace = workspace.copy(worksheet = block(workspace.worksheet))
        worksheetPreview = applyWorksheet(session.sample, workspace.worksheet, session.baseSpec.tables)
        clearExportMessages()
    }

    fun selectMode(mode: ExploreMode) {
        workspace = workspace.copy(activeMode = mode)
        clearExportMessages()
    }

    fun resetConfig() {
        workspace = workspace.copy(pivot = defaultConfig)
        preview = applyExplore(session.sample, workspace.pivot)
        clearExportMessages()
    }

    fun isDefaultConfig(): Boolean = workspace.pivot.withoutTransientState() == defaultConfig.withoutTransientState()

    fun isDirty(): Boolean = !isDefaultConfig()

    fun applyTemplate(templateConfig: ExploreConfig) {
        workspace = workspace.copy(activeMode = ExploreMode.Pivot, pivot = templateConfig)
        preview = applyExplore(session.sample, workspace.pivot)
        clearExportMessages()
    }

    fun applyRecipe(recipe: ExploreRecipe) {
        workspace = workspace.copy(
            activeMode = recipe.defaultMode,
            pivot = recipe.pivot ?: workspace.pivot,
            worksheet = recipe.worksheet ?: workspace.worksheet,
            visualization = recipe.visualization ?: workspace.visualization,
        )
        preview = applyExplore(session.sample, workspace.pivot)
        worksheetPreview = applyWorksheet(session.sample, workspace.worksheet, session.baseSpec.tables)
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

    fun recipeDirty(): Boolean = appliedRecipeBaseline?.let { baseline -> workspace.recipeSnapshot() != baseline } ?: false

    fun clearAppliedRecipe() {
        appliedRecipeId = null
        appliedRecipeBaseline = null
    }

    fun inheritRecipeTrackingFrom(previous: ExploreViewModel) {
        appliedRecipeId = previous.appliedRecipeId
        appliedRecipeBaseline = previous.appliedRecipeBaseline
    }

    fun toggleRowPath(pathKey: String) {
        updateConfig {
            it.copy(
                collapsedRowPaths = it.collapsedRowPaths.toggle(pathKey),
            )
        }
    }

    fun toggleColumnPath(pathKey: String) {
        updateConfig {
            it.copy(
                collapsedColumnPaths = it.collapsedColumnPaths.toggle(pathKey),
            )
        }
    }

    fun toggleWorksheetGroup(pathKey: String) {
        updateWorksheet { current ->
            current.copy(collapsedGroupPaths = current.collapsedGroupPaths.toggle(pathKey))
        }
    }

    fun memberOptions(column: String): List<MemberOption> {
        val index = session.sample.columns.indexOfFirst { it.name == column }
        if (index < 0) return emptyList()
        return session.sample.rows
            .map { row -> row.getOrNull(index) }
            .groupingBy(::pivotCellKey)
            .eachCount()
            .map { (key, count) ->
                val cell = session.sample.rows.firstNotNullOfOrNull { row ->
                    row.getOrNull(index)?.takeIf { pivotCellKey(it) == key }
                }
                MemberOption(key, memberLabel(cell), count)
            }
            .sortedBy { it.label }
    }

    fun updateMemberFilter(filterId: String, includedKeys: Set<String>) {
        updateConfig { current ->
            current.copy(
                filters = current.filters.map { filter ->
                    if (filter is PivotFilter.Members && filter.id == filterId) {
                        filter.copy(includedKeys = includedKeys)
                    } else {
                        filter
                    }
                },
            )
        }
    }

    fun sourceRowsFor(rowPath: String, columnPath: String, measureAlias: String): QueryResult {
        val indexes = preview.layout.cellLineage[pivotCellLineageKey(rowPath, columnPath, measureAlias)].orEmpty()
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
        val result = if (formattedRows.size == preview.result.rows.size) {
            preview.result.copy(
                rows = formattedRows.map { row -> row.map(ResultCell::text) },
            )
        } else {
            preview.result
        }
        saveResultCsv(result, path)
    }

    fun saveWorksheetCsv(path: Path) {
        val rows = worksheetPreview.rows.map { row ->
            row.cells.map { cell -> cell.error?.let { ResultCell.text("Error: $it") } ?: cell.value }
        }
        saveResultCsv(
            QueryResult(
                columns = worksheetPreview.columns.map { com.safedb.model.ResultColumn(it.label, it.dataType) },
                rows = rows,
                rowCount = rows.size,
                truncated = session.sample.truncated,
                warnings = worksheetPreview.warnings,
            ),
            path,
        )
    }

    fun saveResultCsv(result: QueryResult, path: Path) {
        runCatching {
            Files.newOutputStream(path).use { output ->
                writeQueryResultCsv(result, output)
            }
        }.fold(
            onSuccess = {
                exportError = null
                exportMessage = "Exported ${result.rowCount} row${if (result.rowCount == 1) "" else "s"}"
            },
            onFailure = { error ->
                exportMessage = null
                exportError = error.message ?: error.toString()
            },
        )
    }

    fun clearExportMessages() {
        exportError = null
        exportMessage = null
    }
}

data class MemberOption(
    val key: String,
    val label: String,
    val count: Int,
)

private fun Set<String>.toggle(value: String): Set<String> = if (value in this) this - value else this + value

private fun ExploreWorkspaceState.recipeSnapshot(): ExploreWorkspaceState = copy(
    pivot = pivot.withoutTransientState(),
    worksheet = worksheet.withoutTransientState(),
)

private fun memberLabel(cell: com.safedb.model.ResultCell?): String = when (cell) {
    null, is com.safedb.model.ResultCell.Null -> "(blank)"
    is com.safedb.model.ResultCell.BoolCell -> cell.value.toString()
    is com.safedb.model.ResultCell.IntegerCell -> cell.value.toString()
    is com.safedb.model.ResultCell.FloatCell -> cell.value.toString()
    is com.safedb.model.ResultCell.TextCell -> cell.value.text
    is com.safedb.model.ResultCell.BinaryCell -> cell.value.base64
}
