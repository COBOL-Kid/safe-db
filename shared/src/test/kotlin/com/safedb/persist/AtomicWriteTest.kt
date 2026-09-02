package com.safedb.persist

import java.nio.channels.FileChannel
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
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
}
