package com.safedb.store

import com.safedb.model.SafeDbJson
import com.safedb.model.Settings
import com.safedb.model.normalizeSettings
import com.safedb.persist.atomicWrite
import com.safedb.persist.ensurePrivateDir
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class SettingsStore private constructor(
    private val path: Path,
    private val lock: ReentrantLock = ReentrantLock(),
) {
    companion object {
        fun new(dataDir: Path): SettingsStore {
            ensurePrivateDir(dataDir)
            return SettingsStore(dataDir.resolve("settings.json"))
        }
    }

    fun load(): Settings = lock.withLock {
        if (!Files.exists(path)) {
            return@withLock Settings.default()
        }
        val content = Files.readString(path)
        if (content.trim().isEmpty()) {
            return@withLock Settings.default()
        }
        normalizeSettings(SafeDbJson.lenient.decodeFromString(Settings.serializer(), content))
    }

    fun save(settings: Settings) {
        lock.withLock {
            val normalized = normalizeSettings(settings)
            val json = SafeDbJson.store.encodeToString(Settings.serializer(), normalized)
            atomicWrite(path, json)
        }
    }
}
