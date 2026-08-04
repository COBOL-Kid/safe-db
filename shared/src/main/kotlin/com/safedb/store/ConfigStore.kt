package com.safedb.store

import com.safedb.model.CURRENT_CONNECTION_VERSION
import com.safedb.model.ConnectionDef
import com.safedb.model.SafeDbJson
import com.safedb.model.TransportSecurityMode
import com.safedb.persist.atomicWrite
import com.safedb.persist.ensurePrivateDir
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class ConfigStore private constructor(
    private val path: Path,
    private val lock: ReentrantLock = ReentrantLock(),
) {
    companion object {
        fun new(dataDir: Path): ConfigStore {
            ensurePrivateDir(dataDir)
            return ConfigStore(dataDir.resolve("connections.json"))
        }
    }

    fun list(): List<ConnectionDef> = lock.withLock { loadConnectionsUnlocked() }

    fun get(id: String): ConnectionDef? = list().firstOrNull { it.id == id }

    fun save(def: ConnectionDef) {
        lock.withLock {
            val connections = loadConnectionsUnlocked().toMutableList()
            val index = connections.indexOfFirst { it.id == def.id }
            if (index >= 0) {
                connections[index] = def
            } else {
                connections.add(def)
            }
            writeAllUnlocked(connections)
        }
    }

    fun delete(id: String) {
        lock.withLock {
            writeAllUnlocked(loadConnectionsUnlocked().filterNot { it.id == id })
        }
    }

    private fun loadConnectionsUnlocked(): List<ConnectionDef> {
        if (!Files.exists(path)) return emptyList()
        val content = Files.readString(path)
        if (content.trim().isEmpty()) return emptyList()

        val array = SafeDbJson.lenient.parseToJsonElement(content).jsonArray
        val connections = mutableListOf<ConnectionDef>()
        var migratedCount = 0
        var dropped = 0

        for (element in array) {
            val (migrated, upgraded) = migrateLegacyConnection(element)
            runCatching {
                SafeDbJson.lenient.decodeFromJsonElement(ConnectionDef.serializer(), migrated)
            }.onSuccess { def ->
                connections.add(def)
                if (upgraded) migratedCount++
            }.onFailure {
                dropped++
            }
        }

        if (migratedCount > 0 && dropped == 0) {
            val backup = migrationBackupPath(path)
            if (!Files.exists(backup)) {
                atomicWrite(backup, content)
            }
            writeAllUnlocked(connections)
        }

        return connections
    }

    private fun writeAllUnlocked(connections: List<ConnectionDef>) {
        val json = SafeDbJson.store.encodeToString(ListSerializer(ConnectionDef.serializer()), connections)
        atomicWrite(path, json)
    }
}

/**
 * Pre-transport-security profiles omitted `transport_security` from JSON.
 * Upgrade them to plaintext-compatible settings so local databases keep working.
 */
internal fun migrateLegacyConnection(value: JsonElement): Pair<JsonElement, Boolean> {
    val objectValue = value as? JsonObject ?: return value to false
    if ("transport_security" in objectValue) {
        return value to false
    }
    val migrated = JsonObject(
        objectValue.toMutableMap().apply {
            put("version", JsonPrimitive(CURRENT_CONNECTION_VERSION))
            put(
                "transport_security",
                buildJsonObject {
                    put("mode", JsonPrimitive(TransportSecurityMode.Disabled.name))
                    put("legacy_implicit", JsonPrimitive(true))
                },
            )
        },
    )
    return migrated to true
}
