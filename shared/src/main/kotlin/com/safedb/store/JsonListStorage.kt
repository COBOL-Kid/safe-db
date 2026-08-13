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
            val quarantine = moveJsonListAside(path, "corrupt")
            throw IllegalStateException(
                "${path.fileName} was corrupt and was moved to $quarantine: $error"
            )
        }
    return JsonListDocument(entries)
}

internal fun <T> writeJsonList(path: Path, values: List<T>, serializer: KSerializer<T>) {
    atomicWrite(path, SafeDbJson.store.encodeToString(ListSerializer(serializer), values))
}

internal fun moveJsonListAside(path: Path, kind: String): Path {
    val quarantine =
        path.resolveSibling(
            "${path.fileName.toString().substringBeforeLast('.')}.$kind-${UUID.randomUUID()}.json"
        )
    Files.move(path, quarantine, StandardCopyOption.REPLACE_EXISTING)
    return quarantine
}

internal fun <T> readJsonListEntries(path: Path, decodeEntry: (JsonElement) -> T?): List<T> {
    val document = readJsonList(path) ?: return emptyList()
    val values = ArrayList<T>(document.entries.size)
    for (element in document.entries) {
        val decoded = decodeEntry(element)
        if (decoded == null) {
            val quarantine = moveJsonListAside(path, "unsupported")
            throw IllegalStateException(
                "${path.fileName} used an unsupported schema and was moved to $quarantine"
            )
        }
        values.add(decoded)
    }
    return values
}
