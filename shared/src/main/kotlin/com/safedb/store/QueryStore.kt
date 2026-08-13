package com.safedb.store

import com.safedb.model.CURRENT_SCHEMA_VERSION
import com.safedb.model.HistoryEntry
import com.safedb.model.SafeDbJson
import com.safedb.model.SavedQuery
import com.safedb.persist.ensurePrivateDir
import java.nio.file.Path
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement

class QueryStore
private constructor(
    private val savedPath: Path,
    private val historyPath: Path,
    private val lock: ReentrantLock = ReentrantLock(),
    private val maxHistory: Int = 100,
) {
    companion object {
        fun new(dataDir: Path, maxHistory: Int = 100): QueryStore {
            ensurePrivateDir(dataDir)
            return QueryStore(
                savedPath = dataDir.resolve("saved_queries.json"),
                historyPath = dataDir.resolve("query_history.json"),
                maxHistory = maxHistory,
            )
        }
    }

    fun listSaved(): List<SavedQuery> = lock.withLock {
        readValid(savedPath, SavedQuery.serializer()) { it.spec.schemaVersion }
    }

    fun saveQuery(query: SavedQuery) {
        requireCurrentSchema(query.spec.schemaVersion)
        lock.withLock {
            val queries =
                readValid(savedPath, SavedQuery.serializer(), strict = true) {
                        it.spec.schemaVersion
                    }
                    .toMutableList()
            val index = queries.indexOfFirst { it.id == query.id }
            if (index >= 0) {
                queries[index] = query
            } else {
                queries.add(query)
            }
            writeJson(savedPath, queries, SavedQuery.serializer())
        }
    }

    fun deleteSaved(id: String) {
        lock.withLock {
            val queries =
                readValid(savedPath, SavedQuery.serializer(), strict = true) {
                        it.spec.schemaVersion
                    }
                    .filterNot { it.id == id }
            writeJson(savedPath, queries, SavedQuery.serializer())
        }
    }

    fun listHistory(): List<HistoryEntry> = lock.withLock {
        readValid(historyPath, HistoryEntry.serializer()) { it.spec.schemaVersion }
    }

    fun addHistory(entry: HistoryEntry) {
        requireCurrentSchema(entry.spec.schemaVersion)
        lock.withLock {
            val history =
                readValid(historyPath, HistoryEntry.serializer(), strict = true) {
                        it.spec.schemaVersion
                    }
                    .toMutableList()
            history.add(0, entry)
            if (history.size > maxHistory) {
                history.subList(maxHistory, history.size).clear()
            }
            writeJson(historyPath, history, HistoryEntry.serializer())
        }
    }

    fun clearHistory() {
        lock.withLock { writeJson(historyPath, emptyList(), HistoryEntry.serializer()) }
    }

    private fun requireCurrentSchema(schemaVersion: Int) {
        require(schemaVersion == CURRENT_SCHEMA_VERSION) {
            "Unsupported query schema version $schemaVersion; expected $CURRENT_SCHEMA_VERSION"
        }
    }

    private fun <T> readValid(
        path: Path,
        serializer: KSerializer<T>,
        strict: Boolean = false,
        schemaVersion: (T) -> Int,
    ): List<T> {
        val decode: (JsonElement) -> T? = { element ->
            runCatching { SafeDbJson.lenient.decodeFromJsonElement(serializer, element) }
                .getOrNull()
                ?.takeIf { schemaVersion(it) == CURRENT_SCHEMA_VERSION }
        }
        return if (strict) readJsonListEntriesStrict(path, decode)
        else readJsonListEntries(path, decode)
    }

    private fun <T> writeJson(path: Path, data: List<T>, serializer: KSerializer<T>) {
        writeJsonList(path, data, serializer)
    }
}
