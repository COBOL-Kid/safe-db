package com.safedb.tools

import com.safedb.AppState
import com.safedb.launch.LaunchProfileBootstrap
import com.safedb.launch.LaunchProfileException
import com.safedb.runApp
import com.safedb.secrets.SecretsManager
import com.safedb.service.SafeDbServiceImpl
import com.safedb.store.ConfigStore
import com.safedb.store.QueryStore
import com.safedb.store.RecipeStore
import com.safedb.store.SettingsStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

// Dev-only: runs the real desktop app on Linux (e.g. WSL + Xvfb) for agent GUI testing.
// Never packaged; production Main still rejects Linux.
fun main(args: Array<String>) {
    try {
        LaunchProfileBootstrap.configure(args)
    } catch (error: LaunchProfileException) {
        System.err.println("safe-db agent-gui: ${error.message}")
        exitProcess(2)
    }
    val dataDir =
        Path.of(
                System.getenv("SAFEDB_AGENT_GUI_DATA_DIR")?.takeIf { it.isNotBlank() }
                    ?: "build/agent-gui/data",
            )
            .toAbsolutePath()
            .also { Files.createDirectories(it) }
    val useFileCredentials =
        System.getenv("SAFEDB_AGENT_GUI_CREDENTIALS")?.trim()?.equals("file", ignoreCase = true) ==
            true
    if (useFileCredentials) {
        SecretsManager.initFileStore(dataDir.resolve("credentials"))
    } else {
        SecretsManager.initMemoryStore()
    }
    val service =
        SafeDbServiceImpl(
            configStore = ConfigStore.new(dataDir),
            queryStore = QueryStore.new(dataDir),
            settingsStore = SettingsStore.new(dataDir),
            recipeStore = RecipeStore.new(dataDir),
        )
    runApp(AppState(), service)
}
