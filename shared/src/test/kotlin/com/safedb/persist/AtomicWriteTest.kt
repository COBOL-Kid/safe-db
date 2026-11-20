package com.safedb.persist

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
}
