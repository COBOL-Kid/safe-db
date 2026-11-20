package com.safedb.persist

import com.safedb.model.CURRENT_SCHEMA_VERSION
import com.safedb.model.ConnectionDef
import com.safedb.model.Dialect
import com.safedb.model.FilterGroup
import com.safedb.model.HistoryEntry
import com.safedb.model.QuerySpec
import com.safedb.model.SavedQuery
import com.safedb.model.TableRef
import com.safedb.model.TransportSecurity
import com.safedb.store.ConfigStore
import com.safedb.store.QueryStore
import java.nio.channels.FileChannel
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AtomicWriteTest {
    @Test
    fun ensurePrivateDirCreatesDirectory() {
        val dir = Files.createTempDirectory("safedb-atomic-test")
        val nested = dir.resolve("nested/data")
        ensurePrivateDir(nested)
        assertTrue(Files.isDirectory(nested))
    }

    @Test
    fun fsyncParentDirectoryDoesNotThrowForATempDirectory() {
        val dir = Files.createTempDirectory("safedb-atomic-test")
        val rawOpen = runCatching {
            FileChannel.open(dir, StandardOpenOption.READ).use { channel -> channel.force(true) }
        }
        fsyncParentDirectory(dir)
        rawOpen.exceptionOrNull()?.let { error ->
            assertTrue(error is AccessDeniedException, error.toString())
        }
    }

    @Test
    fun atomicWriteRoundTripsContent() {
        val dir = Files.createTempDirectory("safedb-atomic-test")
        val path = dir.resolve("state.json")
        atomicWrite(path, """{"ok":true}""")
        assertEquals("""{"ok":true}""", Files.readString(path))
    }

    @Test
    fun atomicWriteReplacesExistingFile() {
        val dir = Files.createTempDirectory("safedb-atomic-test")
        val path = dir.resolve("state.json")
        Files.writeString(path, "old")
        atomicWrite(path, "new")
        assertEquals("new", Files.readString(path))
    }

    @Test
    fun isPosixProbesTheGivenPath() {
        val dir = Files.createTempDirectory("safedb-atomic-test")
        val expected = runCatching {
            Files.getPosixFilePermissions(dir)
            true
        }
            .getOrDefault(false)
        assertEquals(expected, isPosix(dir))
        assertEquals(expected, isPosix(dir.resolve("does-not-exist-yet")))
    }

    @Test
    fun ensurePrivateDirRestrictsPosixPermissionsWhenSupported() {
        val dir = Files.createTempDirectory("safedb-atomic-test")
        if (!isPosix(dir)) return
        val nested = dir.resolve("private")
        ensurePrivateDir(nested)
        val perms = Files.getPosixFilePermissions(nested)
        assertEquals(PosixFilePermissions.fromString("rwx------"), perms)
    }

    @Test
    fun writePrivateFileRestrictsPosixPermissionsWhenSupported() {
        val dir = Files.createTempDirectory("safedb-atomic-test")
        val path = dir.resolve("secret.txt")
        writePrivateFile(path, "secret")
        assertEquals("secret", Files.readString(path))
        if (isPosix(path)) {
            assertEquals(
                PosixFilePermissions.fromString("rw-------"),
                Files.getPosixFilePermissions(path),
            )
            assertTrue(!hasGroupOrOtherPermissions(path))

            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-r--r--"))
            writePrivateFile(path, "secret")
            assertEquals(
                PosixFilePermissions.fromString("rw-------"),
                Files.getPosixFilePermissions(path),
            )
        }
    }

    @Test
    fun atomicWriteRemovesPrivateTempFileWhenReplacementFails() {
        val dir = Files.createTempDirectory("safedb-atomic-test")
        val destination = Files.createDirectory(dir.resolve("state.json"))
        Files.writeString(destination.resolve("keep.txt"), "keep")

        assertFailsWith<Exception> { atomicWrite(destination, "new") }

        assertTrue(Files.isDirectory(destination))
        assertEquals(
            0L,
            Files.list(dir).use { files ->
                files.filter { it.fileName.toString().startsWith(".state.json.") }.count()
            },
        )
    }

    @Test
    fun withFileLockCreatesAndPreservesLockFileAndSerializesConcurrentThreads() {
        val dir = Files.createTempDirectory("safedb-lock-test")
        val lockPath = dir.resolve("nested/data.json.lock")
        val threads = 6
        val iterations = 15
        val active = AtomicInteger(0)
        val maxActive = AtomicInteger(0)
        val completed = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(threads)
        val ready = CountDownLatch(threads)
        val start = CountDownLatch(1)

        try {
            val futures =
                (0 until threads).map {
                    pool.submit {
                        ready.countDown()
                        assertTrue(start.await(5, TimeUnit.SECONDS))
                        repeat(iterations) {
                            withFileLock(lockPath) {
                                val now = active.incrementAndGet()
                                maxActive.accumulateAndGet(now, ::maxOf)
                                Thread.sleep(2)
                                completed.incrementAndGet()
                                active.decrementAndGet()
                            }
                        }
                    }
                }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            futures.forEach { it.get(15, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        assertEquals(1, maxActive.get())
        assertEquals(threads * iterations, completed.get())
        assertTrue(Files.exists(lockPath))
    }

    @Test
    fun separateConfigStoreInstancesSerializeConcurrentReadModifyWrites() {
        val dir = Files.createTempDirectory("safedb-config-lock-test")
        val writers = 8
        val pool = Executors.newFixedThreadPool(writers)
        val ready = CountDownLatch(writers)
        val start = CountDownLatch(1)

        try {
            val futures =
                (0 until writers).map { idx ->
                    pool.submit {
                        val store = ConfigStore.new(dir)
                        ready.countDown()
                        assertTrue(start.await(5, TimeUnit.SECONDS))
                        store.save(
                            ConnectionDef(
                                id = "c-$idx",
                                name = "Conn $idx",
                                dialect = Dialect.Postgres,
                                host = "localhost",
                                port = 5432,
                                database = "demo",
                                username = "readonly",
                                transportSecurity = TransportSecurity(),
                            ),
                        )
                    }
                }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            futures.forEach { it.get(15, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        val ids = ConfigStore.new(dir).list().map { it.id }.toSet()
        assertEquals((0 until writers).map { "c-$it" }.toSet(), ids)
        assertTrue(Files.exists(dir.resolve("connections.json.lock")))
    }

    @Test
    fun separateQueryStoreInstancesSerializeConcurrentSavedAndHistoryWrites() {
        val dir = Files.createTempDirectory("safedb-query-lock-test")
        val writers = 8
        val pool = Executors.newFixedThreadPool(writers)
        val ready = CountDownLatch(writers)
        val start = CountDownLatch(1)
        val spec =
            QuerySpec(
                tables = listOf(TableRef("public", "users", "t0")),
                columns = emptyList(),
                joins = emptyList(),
                filters = FilterGroup.empty(),
                limit = 10,
                schemaVersion = CURRENT_SCHEMA_VERSION,
            )

        try {
            val futures =
                (0 until writers).map { idx ->
                    pool.submit {
                        val store = QueryStore.new(dir)
                        ready.countDown()
                        assertTrue(start.await(5, TimeUnit.SECONDS))
                        store.saveQuery(SavedQuery("q-$idx", "Query $idx", "c1", spec, "1"))
                        store.addHistory(
                            HistoryEntry(
                                id = "h-$idx",
                                connectionId = "c1",
                                connectionName = "Conn",
                                spec = spec,
                                rowCount = idx,
                                warnings = emptyList(),
                                timestamp = idx.toString(),
                            ),
                        )
                    }
                }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            futures.forEach { it.get(15, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        val reader = QueryStore.new(dir)
        assertEquals(
            (0 until writers).map { "q-$it" }.toSet(),
            reader.listSaved().map { it.id }.toSet(),
        )
        assertEquals(
            (0 until writers).map { "h-$it" }.toSet(),
            reader.listHistory().map { it.id }.toSet(),
        )
        assertTrue(Files.exists(dir.resolve("queries.lock")))
    }
}
