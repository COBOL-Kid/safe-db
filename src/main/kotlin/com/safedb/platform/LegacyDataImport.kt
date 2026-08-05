package com.safedb.platform

import java.nio.file.Files
import java.nio.file.Path

/**
 * Prefer an existing safe-db data directory when present so upgrades can read the same connections,
 * settings, and query stores.
 */
object LegacyDataImport {
    private val DATA_MARKERS =
        listOf(
            "connections.json",
            "settings.json",
            "saved_queries.json",
            "query_history.json",
            "explore_recipes.json",
        )

    fun resolveDataDir(): Path {
        return resolveDataDir(candidateDirs(), DataDirectory::resolve)
    }

    internal fun resolveDataDir(candidates: List<Path>, fallback: () -> Path): Path =
        candidates.distinct().firstOrNull(::hasSafeDbData) ?: fallback()

    fun hasSafeDbData(dir: Path): Boolean = DATA_MARKERS.any { marker ->
        Files.isRegularFile(dir.resolve(marker))
    }

    private fun candidateDirs(): List<Path> {
        val primary = DataDirectory.baseDir().resolve(DataDirectory.APP_ID)
        return listOf(primary)
    }
}
