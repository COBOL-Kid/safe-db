package com.safedb.secrets

import com.safedb.persist.hasGroupOrOtherPermissions
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

const val MAX_PASSWORD_FILE_BYTES = 4_096

class PasswordFileException(message: String, cause: Throwable? = null) : Exception(message, cause)

object PasswordFile {
    fun read(rawPath: String, label: String, requireOwnerOnly: Boolean = false): String =
        read(requireAbsoluteRegularFile(rawPath, label), label, requireOwnerOnly)

    fun read(path: Path, label: String, requireOwnerOnly: Boolean = false): String {
        if (requireOwnerOnly && hasGroupOrOtherPermissions(path)) {
            throw PasswordFileException("$label must be owner-only (mode 0600)")
        }
        val decoded = readUtf8File(path, MAX_PASSWORD_FILE_BYTES + 2, label)
        val password =
            when {
                decoded.endsWith("\r\n") -> decoded.dropLast(2)
                decoded.endsWith("\n") -> decoded.dropLast(1)
                else -> decoded
            }
        val passwordBytes = password.toByteArray(StandardCharsets.UTF_8)
        val passwordTooLarge =
            try {
                passwordBytes.size > MAX_PASSWORD_FILE_BYTES
            } finally {
                passwordBytes.fill(0)
            }
        if (passwordTooLarge) {
            throw PasswordFileException("$label is too large")
        }
        if (password.any { it == '\u0000' || it == '\r' || it == '\n' }) {
            throw PasswordFileException("$label must contain exactly one line")
        }
        return password
    }

    internal fun requireAbsoluteRegularFile(raw: String, label: String): Path {
        val path =
            try {
                Path.of(raw)
            } catch (error: Exception) {
                throw PasswordFileException("$label path is invalid", error)
            }
        if (!path.isAbsolute) throw PasswordFileException("$label path must be absolute")
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
            throw PasswordFileException("$label must be a readable regular file")
        }
        return path.normalize()
    }

    private fun readUtf8File(path: Path, maxBytes: Int, label: String): String {
        val bytes =
            try {
                Files.newInputStream(path).use { it.readNBytes(maxBytes + 1) }
            } catch (error: Exception) {
                throw PasswordFileException("$label could not be read", error)
            }
        if (bytes.size > maxBytes) {
            bytes.fill(0)
            throw PasswordFileException("$label is too large")
        }
        return try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (error: Exception) {
            throw PasswordFileException("$label must be valid UTF-8", error)
        } finally {
            bytes.fill(0)
        }
    }
}
