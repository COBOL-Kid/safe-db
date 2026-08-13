package com.safedb.store

import com.safedb.model.CURRENT_CONNECTION_VERSION
import com.safedb.model.ConnectionDef
import com.safedb.model.SafeDbJson
import com.safedb.persist.ensurePrivateDir
import java.nio.file.Path
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

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
        require(def.version == CURRENT_CONNECTION_VERSION) {
            "Unsupported connection version ${def.version}; expected $CURRENT_CONNECTION_VERSION"
        }
        lock.withLock {
            val connections = loadConnectionsUnlocked(strict = true).toMutableList()
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
            writeAllUnlocked(loadConnectionsUnlocked(strict = true).filterNot { it.id == id })
        }
    }

    private fun loadConnectionsUnlocked(strict: Boolean = false): List<ConnectionDef> {
        val decode = ::decodeConnection
        return if (strict) readJsonListEntriesStrict(path, decode)
        else readJsonListEntries(path, decode)
    }

    private fun decodeConnection(element: JsonElement): ConnectionDef? {
        val obj = element as? JsonObject ?: return null
        if (obj["transport_security"] == null) return null
        return runCatching {
            SafeDbJson.lenient.decodeFromJsonElement(ConnectionDef.serializer(), element)
        }
            .getOrNull()
            ?.takeIf { it.version == CURRENT_CONNECTION_VERSION }
    }

    private fun writeAllUnlocked(connections: List<ConnectionDef>) {
        writeJsonList(path, connections, ConnectionDef.serializer())
    }
}
