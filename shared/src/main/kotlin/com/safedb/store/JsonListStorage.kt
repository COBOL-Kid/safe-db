package com.safedb.store

import com.safedb.model.SafeDbJson
import com.safedb.persist.atomicWrite
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray

internal data class JsonListDocument(val originalContent: String, val entries: JsonArray)

/** Shared file mechanics only; each store still owns its validation and migrations. */
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
