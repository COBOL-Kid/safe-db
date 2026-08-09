package com.safedb.store

import com.safedb.model.CURRENT_SCHEMA_VERSION
import com.safedb.model.FilterOp
import com.safedb.model.HistoryEntry
import com.safedb.model.SafeDbJson
import com.safedb.model.SavedQuery
import com.safedb.model.asObjectOrNull
import com.safedb.model.isNullOrEmpty
import com.safedb.model.stringOrEmpty
import com.safedb.model.u64OrDefault
import com.safedb.persist.atomicWrite
import com.safedb.persist.ensurePrivateDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

class QueryStore
private constructor(
    private val savedPath: Path,
    private val historyPath: Path,
    private val lock: ReentrantLock = ReentrantLock(),
    private val maxHistory: Int = 100,
) {
    companion object {
        fun new(dataDir: Path, maxHistory: Int = 100): QueryStore {
            ensurePrivateDir(dataDir)
            return QueryStore(
                savedPath = dataDir.resolve("saved_queries.json"),
                historyPath = dataDir.resolve("query_history.json"),
                maxHistory = maxHistory,
            )
        }
    }

    fun listSaved(): List<SavedQuery> = lock.withLock {
        readValid(savedPath, SavedQuery.serializer())
    }

    fun saveQuery(query: SavedQuery) {
        lock.withLock {
            val queries = readValid(savedPath, SavedQuery.serializer()).toMutableList()
            val index = queries.indexOfFirst { it.id == query.id }
            if (index >= 0) {
                queries[index] = query
            } else {
                queries.add(query)
            }
            writeJson(savedPath, queries, SavedQuery.serializer())
        }
    }

    fun deleteSaved(id: String) {
        lock.withLock {
            val queries = readValid(savedPath, SavedQuery.serializer()).filterNot { it.id == id }
            writeJson(savedPath, queries, SavedQuery.serializer())
        }
    }

    fun listHistory(): List<HistoryEntry> = lock.withLock {
        readValid(historyPath, HistoryEntry.serializer())
    }

    fun addHistory(entry: HistoryEntry) {
        lock.withLock {
            val history = readValid(historyPath, HistoryEntry.serializer()).toMutableList()
            history.add(0, entry)
            if (history.size > maxHistory) {
                history.subList(maxHistory, history.size).clear()
            }
            writeJson(historyPath, history, HistoryEntry.serializer())
        }
    }

    fun clearHistory() {
        lock.withLock { writeJson(historyPath, emptyList(), HistoryEntry.serializer()) }
    }

    private fun <T> readValid(path: Path, serializer: KSerializer<T>): List<T> {
        val document = readJsonList(path) ?: return emptyList()
        val content = document.originalContent
        val array = document.entries

        val valid = mutableListOf<T>()
        var migratedCount = 0
        var dropped = 0

        for (element in array) {
            val (upgradedValue, upgraded) = upgradeEntryToV3(element)
            val decoded = runCatching {
                SafeDbJson.lenient.decodeFromJsonElement(serializer, upgradedValue)
            }
                .getOrNull()
            if (decoded != null) {
                valid.add(decoded)
                if (upgraded) migratedCount++
                continue
            }

            val migrated = migrateV1Entry(element)?.let { upgradeEntryToV3(it).first }
            val migratedDecoded = migrated?.let {
                runCatching { SafeDbJson.lenient.decodeFromJsonElement(serializer, it) }.getOrNull()
            }
            if (migratedDecoded != null) {
                valid.add(migratedDecoded)
                migratedCount++
            } else {
                dropped++
            }
        }

        if (migratedCount > 0 && dropped == 0) {
            val backup = migrationBackupPath(path)
            if (!Files.exists(backup)) {
                atomicWrite(backup, content)
            }
            writeJson(path, valid, serializer)
        }

        return valid
    }

    private fun <T> writeJson(path: Path, data: List<T>, serializer: KSerializer<T>) {
        writeJsonList(path, data, serializer)
    }
}

internal fun upgradeEntryToV3(value: JsonElement): Pair<JsonElement, Boolean> {
    val objectValue = value.asObjectOrNull() ?: return value to false
    val spec = objectValue["spec"]?.asObjectOrNull() ?: return value to false
    val version = spec.u64OrDefault("schema_version", 1)
    if (version >= CURRENT_SCHEMA_VERSION) {
        return value to false
    }

    val updatedSpec = spec.toMutableMap()
    updatedSpec["filters"]?.let { filters -> updatedSpec["filters"] = ensureGroupIds(filters) }
    updatedSpec["schema_version"] = JsonPrimitive(CURRENT_SCHEMA_VERSION)

    val updated =
        JsonObject(objectValue.toMutableMap().apply { put("spec", JsonObject(updatedSpec)) })
    return updated to true
}

internal fun migrationBackupPath(path: Path): Path =
    path.resolveSibling("${path.fileName.toString().substringBeforeLast('.')}.migration.bak")

internal fun ensureGroupIds(group: JsonElement): JsonElement {
    val objectValue = group.asObjectOrNull() ?: return group
    val updated = objectValue.toMutableMap()
    if (updated["id"]?.jsonPrimitive.isNullOrEmpty()) {
        updated["id"] = JsonPrimitive(UUID.randomUUID().toString())
    }

    val children = updated["children"]?.jsonArray ?: return JsonObject(updated)
    val newChildren = children.map { child ->
        val childObject = child.asObjectOrNull() ?: return@map child
        val childMap = childObject.toMutableMap()
        when {
            "Leaf" in childMap -> {
                val leaf = childMap["Leaf"]?.asObjectOrNull()
                if (leaf != null) {
                    val leafMap = leaf.toMutableMap()
                    if (leafMap["id"]?.jsonPrimitive.isNullOrEmpty()) {
                        leafMap["id"] = JsonPrimitive(UUID.randomUUID().toString())
                    }
                    childMap["Leaf"] = JsonObject(leafMap)
                }
            }
            "Group" in childMap -> {
                childMap["Group"] = ensureGroupIds(childMap.getValue("Group"))
            }
        }
        JsonObject(childMap)
    }
    updated["children"] = JsonArray(newChildren)
    return JsonObject(updated)
}

// V1 stored spec.filters as an array with string values instead of the current filter tree.
internal fun migrateV1Entry(value: JsonElement): JsonElement? {
    val objectValue = value.asObjectOrNull() ?: return null
    val spec = objectValue["spec"]?.asObjectOrNull() ?: return null
    val filtersArray = spec["filters"] as? JsonArray ?: return null

    val children = buildJsonArray {
        for (filter in filtersArray) {
            val filterObject = filter.asObjectOrNull() ?: return null
            val tableAlias = filterObject.stringOrEmpty("table_alias")
            val column = filterObject.stringOrEmpty("column")
            val opElement = filterObject["op"] ?: return null
            val op =
                runCatching {
                    SafeDbJson.lenient.decodeFromJsonElement(FilterOp.serializer(), opElement)
                }
                    .getOrNull() ?: return null
            val rawValue = filterObject["value"]
            val valueElement =
                when {
                    rawValue == null || rawValue is JsonNull -> null
                    rawValue is JsonPrimitive && rawValue.isString ->
                        buildJsonObject {
                            put(
                                "Single",
                                buildJsonObject {
                                    put("kind", JsonPrimitive("Text"))
                                    put("text", rawValue)
                                },
                            )
                        }
                    else -> null
                }
            add(
                buildJsonObject {
                    put(
                        "Leaf",
                        buildJsonObject {
                            put("table_alias", JsonPrimitive(tableAlias))
                            put("column", JsonPrimitive(column))
                            put(
                                "op",
                                SafeDbJson.lenient.encodeToJsonElement(FilterOp.serializer(), op),
                            )
                            if (valueElement != null) {
                                put("value", valueElement)
                            }
                        },
                    )
                }
            )
        }
    }

    val newSpec =
        spec.toMutableMap().apply {
            put(
                "filters",
                buildJsonObject {
                    put("connector", JsonPrimitive("And"))
                    put("children", children)
                },
            )
            put("schema_version", JsonPrimitive(CURRENT_SCHEMA_VERSION))
        }

    return JsonObject(objectValue.toMutableMap().apply { put("spec", JsonObject(newSpec)) })
}
