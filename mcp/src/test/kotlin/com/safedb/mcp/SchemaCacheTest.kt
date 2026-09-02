package com.safedb.mcp

import com.safedb.model.Schema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlinx.coroutines.runBlocking

class SchemaCacheTest {
    @Test
    fun secondGetWithinTtlDoesNotReload() = runBlocking {
        val clock = mutableListOf(1_000L)
        val loads = mutableListOf<String>()
        val first = sampleMcpSchema()
        val cache =
            SchemaCache(
                load = { id ->
                    loads += id
                    first
                },
                nowMs = { clock.single() },
                ttlMs = 100,
            )

        assertSame(first, cache.get("c1"))
        clock[0] = 1_099
        assertSame(first, cache.get("c1"))
        assertEquals(listOf("c1"), loads)
    }

    @Test
    fun expiredEntryReloads() = runBlocking {
        val clock = mutableListOf(1_000L)
        val loads = mutableListOf<String>()
        val first = sampleMcpSchema()
        val second = Schema(emptyList())
        val schemas = ArrayDeque(listOf(first, second))
        val cache =
            SchemaCache(
                load = { id ->
                    loads += id
                    schemas.removeFirst()
                },
                nowMs = { clock.single() },
                ttlMs = 100,
            )

        assertSame(first, cache.get("c1"))
        clock[0] = 1_100
        assertSame(second, cache.get("c1"))
        assertEquals(listOf("c1", "c1"), loads)
    }

    @Test
    fun refreshBypassesValidEntry() = runBlocking {
        val loads = mutableListOf<String>()
        val first = sampleMcpSchema()
        val second = Schema(emptyList())
        val schemas = ArrayDeque(listOf(first, second))
        val cache =
            SchemaCache(
                load = { id ->
                    loads += id
                    schemas.removeFirst()
                },
                nowMs = { 1_000L },
                ttlMs = 100,
            )

        assertSame(first, cache.get("c1"))
        assertSame(second, cache.get("c1", refresh = true))
        assertEquals(listOf("c1", "c1"), loads)
    }

    @Test
    fun invalidateDropsEntry() = runBlocking {
        val loads = mutableListOf<String>()
        val cache =
            SchemaCache(
                load = { id ->
                    loads += id
                    sampleMcpSchema()
                },
                nowMs = { 1_000L },
                ttlMs = 100,
            )

        cache.get("c1")
        cache.invalidate("c1")
        cache.get("c1")
        assertEquals(listOf("c1", "c1"), loads)
    }

    @Test
    fun failedLoadLeavesPreviousEntry() = runBlocking {
        val loads = mutableListOf<String>()
        var fail = false
        val first = sampleMcpSchema()
        val cache =
            SchemaCache(
                load = { id ->
                    loads += id
                    if (fail) error("jdbc down")
                    first
                },
                nowMs = { 1_000L },
                ttlMs = 100,
            )

        assertSame(first, cache.get("c1"))
        fail = true
        val error = assertFailsWith<IllegalStateException> { cache.get("c1", refresh = true) }
        assertEquals("jdbc down", error.message)
        fail = false
        assertSame(first, cache.get("c1"))
        assertEquals(listOf("c1", "c1"), loads)
    }
}
