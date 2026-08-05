package com.safedb

import com.safedb.launch.LaunchProfileBootstrap
import com.safedb.launch.LaunchProfileException
import com.safedb.platform.LegacyDataImport
import com.safedb.secrets.SecretsManager
import com.safedb.service.SafeDbServiceImpl
import com.safedb.store.ConfigStore
import com.safedb.store.QueryStore
import com.safedb.store.RecipeStore
import com.safedb.store.SettingsStore
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    try {
        LaunchProfileBootstrap.configure(args)
    } catch (error: LaunchProfileException) {
        System.err.println("safe-db: ${error.message}")
        exitProcess(2)
    }
    SecretsManager.initStore()
    val dataDir = LegacyDataImport.resolveDataDir()
    val service = SafeDbServiceImpl(
        configStore = ConfigStore.new(dataDir),
        queryStore = QueryStore.new(dataDir),
        settingsStore = SettingsStore.new(dataDir),
        recipeStore = RecipeStore.new(dataDir),
    )
    runApp(AppState(service))
}
