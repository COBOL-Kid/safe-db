package com.safedb.persist

import java.nio.channels.FileChannel
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.util.EnumSet
import java.util.UUID

fun ensurePrivateDir(path: Path) {
    Files.createDirectories(path)
    if (isPosix(path)) {
        Files.setPosixFilePermissions(
            path,
            EnumSet.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            ),
        )
    }
}

fun atomicWrite(path: Path, content: String, ownerOnly: Boolean = false) {
    val parent = path.parent ?: error("path has no parent: $path")
    ensurePrivateDir(parent)

    val fileName = path.fileName?.toString() ?: error("invalid path: $path")
    val tmpPath = parent.resolve(".${fileName}.${UUID.randomUUID()}.tmp")

    // Fsync the temporary file before renaming so success survives a crash. Directory fsync is
    // best-effort: Windows rejects FileChannel.open(directory, READ) with AccessDeniedException.
    try {
        FileChannel.open(tmpPath, StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW).use {
            channel ->
            channel.write(java.nio.ByteBuffer.wrap(content.toByteArray(Charsets.UTF_8)))
            channel.force(true)
        }
        if (ownerOnly) {
            restrictToOwnerReadWrite(tmpPath)
        }
        replaceFile(tmpPath, path)
        fsyncParentDirectory(parent)
    } catch (error: Exception) {
        runCatching { Files.deleteIfExists(tmpPath) }
        throw error
    }
}

fun writePrivateFile(path: Path, content: String) {
    atomicWrite(path, content, ownerOnly = true)
}

fun restrictToOwnerReadWrite(path: Path) {
    if (!isPosix(path)) return
    Files.setPosixFilePermissions(
        path,
        EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
    )
}

fun hasGroupOrOtherPermissions(path: Path): Boolean =
    isPosix(path) && Files.getPosixFilePermissions(path).any { it in GROUP_OR_OTHER }

internal fun fsyncParentDirectory(directory: Path) {
    try {
        FileChannel.open(directory, StandardOpenOption.READ).use { parentChannel ->
            parentChannel.force(true)
        }
    } catch (_: AccessDeniedException) {
        // Windows rejects FileChannel.open(directory, READ).
    }
}

private fun replaceFile(source: Path, destination: Path) {
    if (!Files.exists(destination)) {
        Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE)
        return
    }
    Files.move(
        source,
        destination,
        StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.ATOMIC_MOVE,
    )
}

internal fun isPosix(path: Path): Boolean {
    val probe =
        generateSequence(path.normalize()) { it.parent }.firstOrNull { Files.exists(it) } ?: path
    return runCatching {
            Files.getPosixFilePermissions(probe)
            true
        }
        .getOrDefault(false)
}

private val GROUP_OR_OTHER =
    EnumSet.of(
        PosixFilePermission.GROUP_READ,
        PosixFilePermission.GROUP_WRITE,
        PosixFilePermission.GROUP_EXECUTE,
        PosixFilePermission.OTHERS_READ,
        PosixFilePermission.OTHERS_WRITE,
        PosixFilePermission.OTHERS_EXECUTE,
    )
