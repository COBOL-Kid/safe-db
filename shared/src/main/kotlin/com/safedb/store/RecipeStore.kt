package com.safedb.store

import com.safedb.explore.EXPLORE_RECIPE_SCHEMA_VERSION
import com.safedb.explore.ExploreRecipe
import com.safedb.model.SafeDbJson
import com.safedb.persist.ensurePrivateDir
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.serialization.json.JsonElement

class RecipeStore
private constructor(private val path: Path, private val lock: ReentrantLock = ReentrantLock()) {
    companion object {
        fun new(dataDir: Path): RecipeStore {
            ensurePrivateDir(dataDir)
            return RecipeStore(dataDir.resolve("explore_recipes.json"))
        }
    }

    fun list(): List<ExploreRecipe> = lock.withLock { read().sortedByDescending { it.updatedAt } }

    fun save(recipe: ExploreRecipe) {
        recipe.validate()
        lock.withLock {
            val recipes = readExistingForMutation().toMutableList()
            val index = recipes.indexOfFirst { it.id == recipe.id }
            if (index >= 0) recipes[index] = recipe else recipes += recipe
            write(recipes)
        }
    }

    fun delete(id: String) {
        lock.withLock { write(readExistingForMutation().filterNot { it.id == id }) }
    }

    fun importJson(content: String, nowEpochSec: String): ExploreRecipe {
        val decoded =
            SafeDbJson.lenient.decodeFromString(ExploreRecipe.serializer(), content).validate()
        require(decoded.schemaVersion == EXPLORE_RECIPE_SCHEMA_VERSION) {
            "Unsupported recipe version ${decoded.schemaVersion}"
        }
        return lock.withLock {
            val existing = readExistingForMutation()
            val imported =
                decoded.copy(
                    id = UUID.randomUUID().toString(),
                    name = uniqueName(decoded.name, existing.map { it.name }),
                    createdAt = nowEpochSec,
                    updatedAt = nowEpochSec,
                )
            write(existing + imported)
            imported
        }
    }

    fun exportJson(recipe: ExploreRecipe): String =
        SafeDbJson.store.encodeToString(recipe.validate())

    private fun decodeRecipe(element: JsonElement): ExploreRecipe? = runCatching {
        SafeDbJson.lenient.decodeFromJsonElement(ExploreRecipe.serializer(), element).validate()
    }
        .getOrNull()

    private fun read(): List<ExploreRecipe> = readJsonListEntries(path, ::decodeRecipe)

    private fun readExistingForMutation(): List<ExploreRecipe> =
        readJsonListEntriesStrict(path, ::decodeRecipe)

    private fun write(recipes: List<ExploreRecipe>) {
        writeJsonList(path, recipes, ExploreRecipe.serializer())
    }
}

internal fun uniqueName(base: String, existing: List<String>): String {
    if (existing.none { it.equals(base, ignoreCase = true) }) return base
    var suffix = 2
    while (existing.any { it.equals("$base ($suffix)", ignoreCase = true) }) suffix++
    return "$base ($suffix)"
}
