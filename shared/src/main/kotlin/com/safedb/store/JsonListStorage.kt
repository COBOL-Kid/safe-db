package com.safedb.store

import com.safedb.model.SafeDbJson
import com.safedb.persist.atomicWrite
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

internal data class JsonListDocument(val originalContent: String, val entries: JsonArray)

internal fun readJsonList(path: Path): JsonListDocument? {
    if (!Files.exists(path)) return null
    val content = Files.readString(path)
    if (content.isBlank()) return null
    val entries =
        try {
            SafeDbJson.lenient.parseToJsonElement(content) as? JsonArray
                ?: error("Expected a JSON array")
        } catch (error: Exception) {
            val quarantine =
                path.resolveSibling(
                    "${path.fileName.toString().substringBeforeLast('.')}.corrupt-${UUID.randomUUID()}.json"
                )
            Files.move(path, quarantine, StandardCopyOption.REPLACE_EXISTING)
            throw IllegalStateException(
                "${path.fileName} was corrupt and was moved to $quarantine: $error"
            )
        }
    return JsonListDocument(content, entries)
}

internal fun <T> writeJsonList(path: Path, values: List<T>, serializer: KSerializer<T>) {
    atomicWrite(path, SafeDbJson.store.encodeToString(ListSerializer(serializer), values))
}

internal fun migrationBackupPath(path: Path): Path =
    path.resolveSibling("${path.fileName.toString().substringBeforeLast('.')}.migration.bak")

internal data class MigratedEntry<T>(val value: T, val migrated: Boolean)

// Decodes each entry through [decodeEntry], which returns null for an entry it cannot decode, and
// rewrites the file once when every entry survived and at least one was upgraded.
internal fun <T> readMigratedJsonList(
    path: Path,
    serializer: KSerializer<T>,
    decodeEntry: (JsonElement) -> MigratedEntry<T>?,
): List<T> {
    val document = readJsonList(path) ?: return emptyList()

    val values = mutableListOf<T>()
    var migratedCount = 0
    var dropped = 0

    for (element in document.entries) {
        val entry = decodeEntry(element)
        if (entry == null) {
            dropped++
            continue
        }
        values.add(entry.value)
        if (entry.migrated) migratedCount++
    }

    // Never rewrite after a partial decode; doing so would persist only the surviving entries.
    if (migratedCount > 0 && dropped == 0) {
        val backup = migrationBackupPath(path)
        if (!Files.exists(backup)) {
            atomicWrite(backup, document.originalContent)
        }
        writeJsonList(path, values, serializer)
    }

    return values
}
