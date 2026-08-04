package com.safedb.store

import com.safedb.model.ConnectionDef
import com.safedb.model.SafeDbJson
import com.safedb.persist.atomicWrite
import com.safedb.persist.ensurePrivateDir
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
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
        return array.mapNotNull { element ->
            runCatching {
                SafeDbJson.lenient.decodeFromJsonElement(ConnectionDef.serializer(), element)
            }.getOrNull()
        }
    }

    private fun writeAllUnlocked(connections: List<ConnectionDef>) {
        val json = SafeDbJson.store.encodeToString(ListSerializer(ConnectionDef.serializer()), connections)
        atomicWrite(path, json)
    }
}
