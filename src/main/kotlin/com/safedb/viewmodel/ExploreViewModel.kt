package com.safedb.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.safedb.explore.ExploreConfig
import com.safedb.explore.ExplorePreviewResult
import com.safedb.explore.ExploreSession
import com.safedb.explore.applyExplore
import com.safedb.explore.exploreSpecHash
import com.safedb.export.writeQueryResultCsv
import com.safedb.model.QuerySpec
import java.nio.file.Files
import java.nio.file.Path

class ExploreViewModel(
    val session: ExploreSession,
) {
    var config by mutableStateOf(ExploreConfig.defaultFor(session.sample))
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

    fun isStale(currentSpec: QuerySpec): Boolean =
        exploreSpecHash(currentSpec) != session.baseSpecHash

    fun savePreviewCsv(path: Path) {
        runCatching {
            Files.newOutputStream(path).use { output ->
                writeQueryResultCsv(preview.result, output)
            }
        }.fold(
            onSuccess = {
                exportError = null
                exportMessage = "Exported ${preview.result.rowCount} row${if (preview.result.rowCount == 1) "" else "s"}"
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
