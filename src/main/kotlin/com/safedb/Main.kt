package com.safedb

import com.safedb.launch.LaunchProfileBootstrap
import com.safedb.launch.LaunchProfileException
import com.safedb.platform.DataDirectory
import com.safedb.platform.DesktopPlatform
import com.safedb.platform.UnsupportedDesktopPlatformException
import com.safedb.secrets.SecretsManager
import com.safedb.service.SafeDbServiceImpl
import com.safedb.store.ConfigStore
import com.safedb.store.QueryStore
import com.safedb.store.RecipeStore
import com.safedb.store.SettingsStore
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    // Keep this order: reject unsupported platforms before trust, credential, or data
    // initialization.
    val platform = requireSupportedDesktopPlatform()
    try {
        LaunchProfileBootstrap.configure(args)
    } catch (error: LaunchProfileException) {
        System.err.println("safe-db: ${error.message}")
        exitProcess(2)
    }
    SecretsManager.initStore(platform = platform)
    val dataDir = DataDirectory.resolve()
    val service =
        SafeDbServiceImpl(
            configStore = ConfigStore.new(dataDir),
            queryStore = QueryStore.new(dataDir),
            settingsStore = SettingsStore.new(dataDir),
            recipeStore = RecipeStore.new(dataDir),
        )
    runApp(AppState(service))
}

internal fun requireSupportedDesktopPlatform(
    osName: String = System.getProperty("os.name").orEmpty(),
    reportError: (String) -> Unit = System.err::println,
    exit: (Int) -> Nothing = ::exitProcess,
): DesktopPlatform =
    try {
        DesktopPlatform.resolve(osName)
    } catch (error: UnsupportedDesktopPlatformException) {
        reportError("safe-db: ${error.message}")
        exit(2)
    }
