package com.safedb.mcp

import com.safedb.model.Schema
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal const val SCHEMA_CACHE_TTL_MS = 300_000L

internal class SchemaCache(
    private val load: suspend (String) -> Schema,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val ttlMs: Long = SCHEMA_CACHE_TTL_MS,
) {
    private data class Entry(val schema: Schema, val expiresAtMs: Long)

    private val mutex = Mutex()
    private val entries = mutableMapOf<String, Entry>()

    suspend fun get(connectionId: String, refresh: Boolean = false): Schema {
        if (!refresh) {
            val cached = mutex.withLock { entries[connectionId] }
            if (cached != null && nowMs() < cached.expiresAtMs) {
                return cached.schema
            }
        }
        val schema = load(connectionId)
        mutex.withLock { entries[connectionId] = Entry(schema, nowMs() + ttlMs) }
        return schema
    }

    suspend fun invalidate(connectionId: String) {
        mutex.withLock { entries.remove(connectionId) }
    }
}
