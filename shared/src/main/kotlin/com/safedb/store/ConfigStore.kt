package com.safedb.store

import com.safedb.model.CURRENT_CONNECTION_VERSION
import com.safedb.model.ConnectionDef
import com.safedb.model.SafeDbJson
import com.safedb.model.TransportSecurityMode
import com.safedb.persist.ensurePrivateDir
import java.nio.file.Path
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

class ConfigStore
private constructor(private val path: Path, private val lock: ReentrantLock = ReentrantLock()) {
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
        lock.withLock { writeAllUnlocked(loadConnectionsUnlocked().filterNot { it.id == id }) }
    }

    private fun loadConnectionsUnlocked(): List<ConnectionDef> =
        readMigratedJsonList(path, ConnectionDef.serializer()) { element ->
            val (migrated, upgraded) = migrateLegacyConnection(element)
            runCatching {
                SafeDbJson.lenient.decodeFromJsonElement(ConnectionDef.serializer(), migrated)
            }
                .getOrNull()
                ?.let { MigratedEntry(it, upgraded) }
        }

    private fun writeAllUnlocked(connections: List<ConnectionDef>) {
        writeJsonList(path, connections, ConnectionDef.serializer())
    }
}

// Pre-transport-security profiles omitted transport_security. Preserve their plaintext behavior.
internal fun migrateLegacyConnection(value: JsonElement): Pair<JsonElement, Boolean> {
    val objectValue = value as? JsonObject ?: return value to false
    if ("transport_security" in objectValue) {
        return value to false
    }
    val migrated =
        JsonObject(
            objectValue.toMutableMap().apply {
                put("version", JsonPrimitive(CURRENT_CONNECTION_VERSION))
                put(
                    "transport_security",
                    buildJsonObject {
                        put("mode", JsonPrimitive(TransportSecurityMode.Disabled.name))
                        put("legacy_implicit", JsonPrimitive(true))
                    },
                )
            }
        )
    return migrated to true
}
