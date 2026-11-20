package com.safedb.store

import com.safedb.model.SafeDbJson
import com.safedb.model.Settings
import com.safedb.model.normalizeSettings
import com.safedb.persist.atomicWrite
import com.safedb.persist.ensurePrivateDir
import com.safedb.persist.withFileLock
import java.nio.file.Files
import java.nio.file.Path

class SettingsStore private constructor(private val path: Path, private val lockPath: Path) {
    companion object {
        fun new(dataDir: Path): SettingsStore {
            ensurePrivateDir(dataDir)
            return SettingsStore(
                path = dataDir.resolve("settings.json"),
                lockPath = dataDir.resolve("settings.json.lock"),
            )
        }
    }

    fun load(): Settings =
        withFileLock(lockPath) {
            if (!Files.exists(path)) {
                return@withFileLock Settings.default()
            }
            val content = Files.readString(path)
            if (content.trim().isEmpty()) {
                return@withFileLock Settings.default()
            }
            normalizeSettings(SafeDbJson.lenient.decodeFromString(Settings.serializer(), content))
        }

    fun save(settings: Settings) {
        withFileLock(lockPath) {
            val normalized = normalizeSettings(settings)
            val json = SafeDbJson.store.encodeToString(Settings.serializer(), normalized)
            atomicWrite(path, json)
        }
    }
}
