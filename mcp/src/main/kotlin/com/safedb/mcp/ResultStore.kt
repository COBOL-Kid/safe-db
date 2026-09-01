package com.safedb.mcp

import com.safedb.model.QueryResult
import com.safedb.persist.ensurePrivateDir
import com.safedb.persist.restrictToOwnerReadWrite
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.LinkedHashMap
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement

internal const val RESULT_STORE_TTL_MS = 300_000L
internal const val RESULT_STORE_MAX_ENTRIES = 8
internal const val RESULT_STORE_MAX_BYTES = 32L * 1024 * 1024

internal data class StoredResult(
    val resultId: String,
    val artifactPath: String?,
    val artifactBytes: Long?,
)

internal class ResultStore(
    private val resultsDir: Path,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val ttlMs: Long = RESULT_STORE_TTL_MS,
    private val maxEntries: Int = RESULT_STORE_MAX_ENTRIES,
    private val maxBytes: Long = RESULT_STORE_MAX_BYTES,
    private val onAfterJsonlWrite: () -> Unit = {},
) {
    private data class Entry(
        val result: QueryResult,
        val artifactFile: Path,
        val artifactBytes: Long,
        val expiresAtMs: Long,
    )

    private val mutex = Mutex()
    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)

    init {
        ensurePrivateDir(resultsDir)
        wipeExistingFiles()
    }

    suspend fun put(result: QueryResult): StoredResult {
        val resultId = UUID.randomUUID().toString()
        val path = resultsDir.resolve("$resultId.jsonl")
        var artifactPath: String? = null
        var artifactBytes: Long? = null
        var inserted = false
        try {
            try {
                val written =
                    withContext(Dispatchers.IO) {
                        writeJsonl(path, result)
                        restrictToOwnerReadWrite(path)
                        val bytes = Files.size(path)
                        onAfterJsonlWrite()
                        bytes to path.toString()
                    }
                artifactBytes = written.first
                artifactPath = written.second
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                runCatching { Files.deleteIfExists(path) }
            }

            mutex.withLock {
                expireLocked()
                entries[resultId] =
                    Entry(
                        result = result,
                        artifactFile = path,
                        artifactBytes = artifactBytes ?: 0L,
                        expiresAtMs = nowMs() + ttlMs,
                    )
                inserted = true
                evictLocked(keepId = resultId)
            }
            return StoredResult(resultId, artifactPath, artifactBytes)
        } finally {
            // Cancel after a successful write must not leave an untracked JSONL on disk.
            if (!inserted) {
                runCatching { Files.deleteIfExists(path) }
            }
        }
    }

    suspend fun get(resultId: String): QueryResult? = mutex.withLock {
        expireLocked()
        entries[resultId]?.result
    }

    private fun writeJsonl(path: Path, result: QueryResult) {
        val names = result.columns.map { it.name }
        Files.newBufferedWriter(
                path,
                Charsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            )
            .use { writer ->
                for (row in result.rows) {
                    writer.append(
                        toolJson.encodeToString(JsonElement.serializer(), flattenRow(names, row))
                    )
                    writer.append('\n')
                }
            }
    }

    private fun expireLocked() {
        val now = nowMs()
        val expired = entries.mapNotNull { (id, entry) -> id.takeIf { now >= entry.expiresAtMs } }
        expired.forEach { removeLocked(it) }
    }

    private fun evictLocked(keepId: String) {
        while (entries.size > maxEntries || totalBytesLocked() > maxBytes) {
            val eldest = entries.keys.first()
            if (eldest == keepId && entries.size == 1) break
            removeLocked(eldest)
        }
    }

    private fun totalBytesLocked(): Long = entries.values.sumOf { it.artifactBytes }

    private fun removeLocked(id: String) {
        val entry = entries.remove(id) ?: return
        runCatching { Files.deleteIfExists(entry.artifactFile) }
    }

    private fun wipeExistingFiles() {
        if (!Files.isDirectory(resultsDir)) return
        Files.list(resultsDir).use { stream ->
            stream.filter { Files.isRegularFile(it) }.forEach { Files.deleteIfExists(it) }
        }
    }
}
