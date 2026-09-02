package com.safedb.secrets

import com.safedb.persist.isPosix
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PasswordFileTest {
    @Test
    fun readsOneLineAndStripsTrailingNewline() {
        val directory = Files.createTempDirectory("safedb-password-file")
        val path = directory.resolve("password.txt").apply { writeText("  secret  \r\n") }

        assertEquals("  secret  ", PasswordFile.read(path.toString(), "Password file"))
    }

    @Test
    fun rejectsRelativePathAndMultilineWithoutLeakingSecret() {
        assertTrue(
            assertFailsWith<PasswordFileException> {
                    PasswordFile.read("relative.txt", "Password file")
                }
                .message!!
                .contains("absolute")
        )

        val directory = Files.createTempDirectory("safedb-password-file")
        val path = directory.resolve("password.txt").apply { writeText("top-secret\nsecond-line") }
        val error =
            assertFailsWith<PasswordFileException> {
                PasswordFile.read(path.toString(), "Password file")
            }
        assertFalse(error.message.orEmpty().contains("top-secret"))
        assertTrue(error.message!!.contains("exactly one line"))
    }

    @Test
    fun rejectsOversizedAndInvalidUtf8() {
        val directory = Files.createTempDirectory("safedb-password-file")
        val oversized =
            directory.resolve("too-big.txt").apply {
                writeBytes(ByteArray(MAX_PASSWORD_FILE_BYTES + 3) { 'a'.code.toByte() })
            }
        assertEquals(
            "Password file is too large",
            assertFailsWith<PasswordFileException> {
                    PasswordFile.read(oversized.toString(), "Password file")
                }
                .message,
        )

        val invalid =
            directory.resolve("bad.txt").apply { writeBytes(byteArrayOf(0xC3.toByte(), 0x28)) }
        assertTrue(
            assertFailsWith<PasswordFileException> {
                    PasswordFile.read(invalid.toString(), "Password file")
                }
                .message!!
                .contains("UTF-8")
        )
    }

    @Test
    fun requireOwnerOnlyRejectsGroupOrOtherBitsWhenPosix() {
        val directory = Files.createTempDirectory("safedb-password-file")
        if (!isPosix(directory)) return
        val path = directory.resolve("password.txt").apply { writeText("secret") }
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-r--r--"))
        assertTrue(
            assertFailsWith<PasswordFileException> {
                    PasswordFile.read(path.toString(), "Password file", requireOwnerOnly = true)
                }
                .message!!
                .contains("owner-only")
        )

        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
        assertEquals(
            "secret",
            PasswordFile.read(path.toString(), "Password file", requireOwnerOnly = true),
        )
    }
}
