package com.safedb.mcp

import com.safedb.service.SafeDbServiceImpl
import com.safedb.store.ConfigStore
import com.safedb.store.QueryStore
import com.safedb.store.RecipeStore
import com.safedb.store.SettingsStore
import java.nio.file.Path

internal fun realRecipeService(directory: Path): SafeDbServiceImpl =
    SafeDbServiceImpl(
        configStore = ConfigStore.new(directory),
        queryStore = QueryStore.new(directory),
        settingsStore = SettingsStore.new(directory),
        recipeStore = RecipeStore.new(directory),
    )

internal const val MINIMAL_RECIPE_JSON =
    """{
      "schemaVersion": 1,
      "id": "ignored",
      "name": "By region",
      "createdAt": "1",
      "updatedAt": "1",
      "defaultMode": "Pivot",
      "pivot": { "schemaVersion": 1 }
    }"""

internal fun validNonInteractiveAddArgs(
    passwordFile: Path,
    dialect: String = "postgres",
): Array<String> =
    arrayOf(
        "setup",
        "--dialect",
        dialect,
        "--database",
        "app",
        "--username",
        "readonly",
        "--password-file",
        passwordFile.toString(),
    )
