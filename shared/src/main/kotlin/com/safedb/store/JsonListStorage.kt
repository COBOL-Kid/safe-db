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

internal data class JsonListDocument(val entries: JsonArray)

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
    return JsonListDocument(entries)
}

internal fun <T> writeJsonList(path: Path, values: List<T>, serializer: KSerializer<T>) {
    atomicWrite(path, SafeDbJson.store.encodeToString(ListSerializer(serializer), values))
}

internal fun <T> readJsonListEntries(path: Path, decodeEntry: (JsonElement) -> T?): List<T> {
    val document = readJsonList(path) ?: return emptyList()
    return document.entries.mapNotNull(decodeEntry)
}

internal fun <T> readJsonListEntriesStrict(path: Path, decodeEntry: (JsonElement) -> T?): List<T> {
    val document = readJsonList(path) ?: return emptyList()
    return document.entries.map { element ->
        decodeEntry(element)
            ?: error("${path.fileName} contains an unsupported or unreadable entry")
    }
}
