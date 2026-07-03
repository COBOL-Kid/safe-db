package com.safedb.persist

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun ensurePrivateDirRestrictsPosixPermissionsWhenSupported() {
        if (!runCatching { Files.getPosixFilePermissions(Files.createTempDirectory("posix-check")) }.isSuccess) {
            return
        }
        val dir = Files.createTempDirectory("safedb-atomic-test")
        val nested = dir.resolve("private")
        ensurePrivateDir(nested)
        val perms = Files.getPosixFilePermissions(nested)
        assertEquals(
            PosixFilePermissions.fromString("rwx------"),
            perms,
        )
    }
}
