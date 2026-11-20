package com.safedb.store

import com.safedb.explore.EXPLORE_RECIPE_SCHEMA_VERSION
import com.safedb.explore.ExploreRecipe
import com.safedb.model.SafeDbJson
import com.safedb.persist.ensurePrivateDir
import com.safedb.persist.withFileLock
import java.nio.file.Path
import java.util.UUID
import kotlinx.serialization.json.JsonElement

class RecipeStore private constructor(private val path: Path, private val lockPath: Path) {
    companion object {
        fun new(dataDir: Path): RecipeStore {
            ensurePrivateDir(dataDir)
            return RecipeStore(
                path = dataDir.resolve("explore_recipes.json"),
                lockPath = dataDir.resolve("explore_recipes.json.lock"),
            )
        }
    }

    fun list(): List<ExploreRecipe> =
        withFileLock(lockPath) { readUnlocked().sortedByDescending { it.updatedAt } }

    fun save(recipe: ExploreRecipe) {
        recipe.validate()
        withFileLock(lockPath) {
            val recipes = readUnlocked().toMutableList()
            val index = recipes.indexOfFirst { it.id == recipe.id }
            if (index >= 0) recipes[index] = recipe else recipes += recipe
            writeUnlocked(recipes)
        }
    }

    fun delete(id: String) {
        withFileLock(lockPath) { writeUnlocked(readUnlocked().filterNot { it.id == id }) }
    }

    fun importJson(content: String, nowEpochSec: String): ExploreRecipe {
        val decoded =
            SafeDbJson.lenient.decodeFromString(ExploreRecipe.serializer(), content).validate()
        require(decoded.schemaVersion == EXPLORE_RECIPE_SCHEMA_VERSION) {
            "Unsupported recipe version ${decoded.schemaVersion}"
        }
        return withFileLock(lockPath) {
            val existing = readUnlocked()
            val imported =
                decoded.copy(
                    id = UUID.randomUUID().toString(),
                    name = uniqueName(decoded.name, existing.map { it.name }),
                    createdAt = nowEpochSec,
                    updatedAt = nowEpochSec,
                )
            writeUnlocked(existing + imported)
            imported
        }
    }

    fun exportJson(recipe: ExploreRecipe): String =
        SafeDbJson.store.encodeToString(recipe.validate())

    private fun decodeRecipe(element: JsonElement): ExploreRecipe? = runCatching {
        SafeDbJson.lenient.decodeFromJsonElement(ExploreRecipe.serializer(), element).validate()
    }
        .getOrNull()

    private fun readUnlocked(): List<ExploreRecipe> = readJsonListEntries(path, ::decodeRecipe)

    private fun writeUnlocked(recipes: List<ExploreRecipe>) {
        writeJsonList(path, recipes, ExploreRecipe.serializer())
    }
}

internal fun uniqueName(base: String, existing: List<String>): String {
    if (existing.none { it.equals(base, ignoreCase = true) }) return base
    var suffix = 2
    while (existing.any { it.equals("$base ($suffix)", ignoreCase = true) }) suffix++
    return "$base ($suffix)"
}
