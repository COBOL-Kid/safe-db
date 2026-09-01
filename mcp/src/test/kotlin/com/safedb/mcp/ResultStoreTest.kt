package com.safedb.mcp

import com.safedb.persist.hasGroupOrOtherPermissions
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.EnumSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class ResultStoreTest {
    @Test
    fun putThenGetReturnsTheResultAndWritesOwnerOnlyJsonl() = runBlocking {
        withTempResults { dir ->
            val store = ResultStore(dir, nowMs = { 1_000L })
            val result = sampleMcpQueryResult()
            val stored = store.put(result)
            assertEquals(result, store.get(stored.resultId))
            val path = Path.of(stored.artifactPath!!)
            assertTrue(Files.isRegularFile(path))
            assertEquals(Files.size(path), stored.artifactBytes)
            val lines = Files.readAllLines(path)
            val expected = flattenRows(result)
            assertEquals(expected.size, lines.size)
            lines.zip(expected).forEach { (line, row) ->
                assertEquals(row, Json.parseToJsonElement(line).jsonObject)
            }
            assertFalse(hasGroupOrOtherPermissions(path))
        }
    }

    @Test
    fun ttlExpiryDropsEntryAndDeletesFile() = runBlocking {
        withTempResults { dir ->
            val clock = mutableListOf(1_000L)
            val store = ResultStore(dir, nowMs = { clock.single() }, ttlMs = 100)
            val stored = store.put(sampleMcpQueryResult())
            val path = Path.of(stored.artifactPath!!)
            clock[0] = 1_099
            assertNotNull(store.get(stored.resultId))
            clock[0] = 1_100
            assertNull(store.get(stored.resultId))
            assertFalse(Files.exists(path))
        }
    }

    @Test
    fun lastNEvictionDeletesJsonlAndHonorsAccessOrder() = runBlocking {
        withTempResults { dir ->
            val store = ResultStore(dir, nowMs = { 1_000L }, maxEntries = 2)
            val first = store.put(sampleMcpQueryResult(rowCount = 1))
            val second = store.put(sampleMcpQueryResult(rowCount = 2))
            assertNotNull(store.get(first.resultId))
            val third = store.put(sampleMcpQueryResult(rowCount = 3))
            assertNotNull(store.get(first.resultId))
            assertNull(store.get(second.resultId))
            assertNotNull(store.get(third.resultId))
            assertFalse(Files.exists(Path.of(second.artifactPath!!)))
            assertTrue(Files.exists(Path.of(first.artifactPath!!)))
            assertTrue(Files.exists(Path.of(third.artifactPath!!)))
        }
    }

    @Test
    fun maxBytesEvictionDeletesEldestButKeepsASingleOversizeResult() = runBlocking {
        withTempResults { dir ->
            val store = ResultStore(dir, nowMs = { 1_000L }, maxBytes = 10)
            val first = store.put(sampleMcpQueryResult(rowCount = 2))
            val bytes = first.artifactBytes
            assertNotNull(bytes)
            assertTrue(bytes > 10)
            assertNotNull(store.get(first.resultId))
            val second = store.put(sampleMcpQueryResult(rowCount = 1))
            assertNull(store.get(first.resultId))
            assertNotNull(store.get(second.resultId))
            assertFalse(Files.exists(Path.of(first.artifactPath!!)))
        }
    }

    @Test
    fun constructWipesExistingFiles() = runBlocking {
        withTempResults { dir ->
            val leftover = dir.resolve("leftover.jsonl")
            Files.writeString(leftover, "{}\n")
            ResultStore(dir, nowMs = { 1_000L })
            assertFalse(Files.exists(leftover))
        }
    }

    @Test
    fun unknownIdReturnsNull() = runBlocking {
        withTempResults { dir ->
            val store = ResultStore(dir, nowMs = { 1_000L })
            assertNull(store.get("missing"))
        }
    }

    @Test
    fun cancelledPutDeletesUntrackedJsonl() = runBlocking {
        withTempResults { dir ->
            var resultId: String? = null
            val store =
                ResultStore(
                    dir,
                    nowMs = { 1_000L },
                    onAfterJsonlWrite = {
                        resultId =
                            Files.list(dir).use { stream ->
                                stream
                                    .filter { Files.isRegularFile(it) }
                                    .findFirst()
                                    .orElseThrow()
                                    .fileName
                                    .toString()
                                    .removeSuffix(".jsonl")
                            }
                        throw CancellationException("cancelled after write")
                    },
                )
            assertFailsWith<CancellationException> { store.put(sampleMcpQueryResult()) }
            assertTrue(
                Files.list(dir).use { stream -> stream.noneMatch { Files.isRegularFile(it) } }
            )
            assertNull(store.get(resultId!!))
        }
    }

    @Test
    fun jsonlWriteFailureKeepsInMemoryResultWithoutArtifact() = runBlocking {
        withTempResults { dir ->
            val posix = runCatching {
                Files.getPosixFilePermissions(dir)
                true
            }
                .getOrDefault(false)
            if (!posix) return@withTempResults
            val store = ResultStore(dir, nowMs = { 1_000L })
            Files.setPosixFilePermissions(
                dir,
                EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE),
            )
            try {
                val stored = store.put(sampleMcpQueryResult())
                assertNull(stored.artifactPath)
                assertNull(stored.artifactBytes)
                assertEquals(sampleMcpQueryResult(), store.get(stored.resultId))
            } finally {
                Files.setPosixFilePermissions(
                    dir,
                    EnumSet.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE,
                    ),
                )
            }
        }
    }
}

private suspend fun withTempResults(block: suspend (Path) -> Unit) {
    val dir = Files.createTempDirectory("safedb-mcp-results")
    try {
        block(dir)
    } finally {
        dir.toFile().deleteRecursively()
    }
}
