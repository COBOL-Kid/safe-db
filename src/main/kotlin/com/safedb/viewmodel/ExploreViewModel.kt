package com.safedb.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.safedb.explore.ExploreConfig
import com.safedb.explore.ExplorePreviewResult
import com.safedb.explore.ExploreSession
import com.safedb.explore.PivotFilter
import com.safedb.explore.applyExplore
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
) {
    private val defaultConfig = ExploreConfig.defaultFor(session.sample, session.baseSpec.tables)

    var config by mutableStateOf(defaultConfig)
        private set
    var preview by mutableStateOf(applyExplore(session.sample, config))
        private set
    var exportError by mutableStateOf<String?>(null)
        private set
    var exportMessage by mutableStateOf<String?>(null)
        private set

    fun updateConfig(block: (ExploreConfig) -> ExploreConfig) {
        config = block(config)
        preview = applyExplore(session.sample, config)
        exportError = null
        exportMessage = null
    }

    fun resetConfig() {
        config = defaultConfig
        preview = applyExplore(session.sample, config)
        clearExportMessages()
    }

    fun isDefaultConfig(): Boolean = config == defaultConfig

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

private fun memberLabel(cell: com.safedb.model.ResultCell?): String = when (cell) {
    null, is com.safedb.model.ResultCell.Null -> "(blank)"
    is com.safedb.model.ResultCell.BoolCell -> cell.value.toString()
    is com.safedb.model.ResultCell.IntegerCell -> cell.value.toString()
    is com.safedb.model.ResultCell.FloatCell -> cell.value.toString()
    is com.safedb.model.ResultCell.TextCell -> cell.value.text
    is com.safedb.model.ResultCell.BinaryCell -> cell.value.base64
}
