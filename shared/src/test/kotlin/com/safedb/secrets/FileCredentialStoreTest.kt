package com.safedb.secrets

import com.safedb.persist.hasGroupOrOtherPermissions
import com.safedb.persist.isPosix
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileCredentialStoreTest {
    @Test
    fun roundTripDeleteAndMissing() {
        val dir = Files.createTempDirectory("safedb-file-store")
        val store = FileCredentialStore(dir)

        store.setPassword("safe-db", "conn-1", """{"password":"secret"}""")
        assertEquals("""{"password":"secret"}""", store.getPassword("safe-db", "conn-1"))
        assertNull(store.getPassword("safe-db", "missing"))

        store.deletePassword("safe-db", "conn-1")
        assertNull(store.getPassword("safe-db", "conn-1"))
        store.deletePassword("safe-db", "missing")
        assertEquals("file", store.vendor())
    }

    @Test
    fun writesOwnerOnlyFilesOnPosix() {
        val dir = Files.createTempDirectory("safedb-file-store")
        if (!isPosix(dir)) return
        val store = FileCredentialStore(dir)
        store.setPassword("safe-db", "conn-1", "secret")
        val path = dir.resolve("conn-1")
        assertEquals(
            PosixFilePermissions.fromString("rw-------"),
            Files.getPosixFilePermissions(path),
        )
        assertFalse(hasGroupOrOtherPermissions(path))
    }

    @Test
    fun rejectsWorldReadableFilesOnPosix() {
        val dir = Files.createTempDirectory("safedb-file-store")
        if (!isPosix(dir)) return
        val store = FileCredentialStore(dir)
        store.setPassword("safe-db", "conn-1", "secret")
        val path = dir.resolve("conn-1")
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-r--r--"))
        assertTrue(
            assertFailsWith<IllegalStateException> { store.getPassword("safe-db", "conn-1") }
                .message!!
                .contains("owner-only")
        )
    }

    @Test
    fun rejectsUnsafeAccountNames() {
        val dir = Files.createTempDirectory("safedb-file-store")
        val store = FileCredentialStore(dir)
        val sibling = dir.resolve("keep")
        Files.writeString(sibling, "ok")
        val parent = checkNotNull(dir.parent)
        val parentEntries =
            Files.list(parent).use { stream -> stream.map { it.toString() }.toList().toSet() }

        listOf("../escape", "a/b", ".", "..").forEach { account ->
            val error =
                assertFailsWith<IllegalArgumentException> {
                    store.setPassword("safe-db", account, "secret")
                }
            assertEquals("Invalid credential account name", error.message)
            assertFailsWith<IllegalArgumentException> { store.getPassword("safe-db", account) }
            assertFailsWith<IllegalArgumentException> { store.deletePassword("safe-db", account) }
        }

        assertTrue(Files.isDirectory(dir))
        assertEquals("ok", Files.readString(sibling))
        assertEquals(
            setOf("keep"),
            Files.list(dir).use { stream ->
                stream.map { it.fileName.toString() }.toList().toSet()
            },
        )
        assertEquals(
            parentEntries,
            Files.list(parent).use { stream -> stream.map { it.toString() }.toList().toSet() },
        )
    }
}
