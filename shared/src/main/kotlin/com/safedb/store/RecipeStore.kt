package com.safedb.store

import com.safedb.explore.EXPLORE_RECIPE_SCHEMA_VERSION
import com.safedb.explore.ExploreRecipe
import com.safedb.model.SafeDbJson
import com.safedb.persist.atomicWrite
import com.safedb.persist.ensurePrivateDir
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonArray
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class RecipeStore private constructor(
    private val path: Path,
    private val lock: ReentrantLock = ReentrantLock(),
) {
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
            val recipes = read().toMutableList()
            val index = recipes.indexOfFirst { it.id == recipe.id }
            if (index >= 0) recipes[index] = recipe else recipes += recipe
            write(recipes)
        }
    }

    fun delete(id: String) {
        lock.withLock { write(read().filterNot { it.id == id }) }
    }

    fun importJson(content: String, nowEpochSec: String): ExploreRecipe {
        val decoded = SafeDbJson.lenient.decodeFromString(ExploreRecipe.serializer(), content).validate()
        require(decoded.schemaVersion == EXPLORE_RECIPE_SCHEMA_VERSION) { "Unsupported recipe version ${decoded.schemaVersion}" }
        return lock.withLock {
            val existing = read()
            val imported = decoded.copy(
                id = UUID.randomUUID().toString(),
                name = uniqueName(decoded.name, existing.map { it.name }),
                createdAt = nowEpochSec,
                updatedAt = nowEpochSec,
            )
            write(existing + imported)
            imported
        }
    }

    fun exportJson(recipe: ExploreRecipe): String = SafeDbJson.store.encodeToString(recipe.validate())

    private fun read(): List<ExploreRecipe> {
        if (!Files.exists(path)) return emptyList()
        val content = Files.readString(path)
        if (content.isBlank()) return emptyList()
        val array = try {
            SafeDbJson.lenient.parseToJsonElement(content).jsonArray
        } catch (error: Exception) {
            val quarantine = path.resolveSibling("explore_recipes.corrupt-${UUID.randomUUID()}.json")
            Files.move(path, quarantine, StandardCopyOption.REPLACE_EXISTING)
            throw IllegalStateException("${path.fileName} was corrupt and was moved to $quarantine: $error")
        }
        return array.mapNotNull { element ->
            runCatching { SafeDbJson.lenient.decodeFromJsonElement(ExploreRecipe.serializer(), element).validate() }.getOrNull()
        }
    }

    private fun write(recipes: List<ExploreRecipe>) {
        atomicWrite(path, SafeDbJson.store.encodeToString(ListSerializer(ExploreRecipe.serializer()), recipes))
    }
}

internal fun uniqueName(base: String, existing: List<String>): String {
    if (existing.none { it.equals(base, ignoreCase = true) }) return base
    var suffix = 2
    while (existing.any { it.equals("$base ($suffix)", ignoreCase = true) }) suffix++
    return "$base ($suffix)"
}
