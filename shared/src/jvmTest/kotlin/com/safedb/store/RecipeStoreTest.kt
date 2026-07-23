package com.safedb.store

import com.safedb.explore.ExploreConfig
import com.safedb.explore.ExploreMode
import com.safedb.explore.ExploreRecipe
import com.safedb.explore.RecipeField
import com.safedb.explore.WorksheetConfig
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecipeStoreTest {
    @Test
    fun roundTripsUpsertsAndDeletesRecipes() {
        val dir = Files.createTempDirectory("recipe-store")
        val store = RecipeStore.new(dir)
        val recipe = recipe("r1", "Revenue")

        store.save(recipe)
        store.save(recipe.copy(name = "Revenue updated", updatedAt = "2"))

        assertEquals("Revenue updated", store.list().single().name)
        store.delete("r1")
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun exportContainsConfigurationButNoResultOrConnectionIdentity() {
        val store = RecipeStore.new(Files.createTempDirectory("recipe-export"))
        val json = store.exportJson(recipe("r1", "Share me"))

        assertTrue(json.contains("Share me"))
        assertFalse(json.contains("sample"))
        assertFalse(json.contains("connection_id"))
        assertFalse(json.contains("password"))
    }

    @Test
    fun importCreatesNewIdentityAndUniqueName() {
        val store = RecipeStore.new(Files.createTempDirectory("recipe-import"))
        val original = recipe("original", "Shared")
        store.save(original)

        val imported = store.importJson(store.exportJson(original), "10")

        assertTrue(imported.id != original.id)
        assertEquals("Shared (2)", imported.name)
        assertEquals(2, store.list().size)
    }

    @Test
    fun invalidVersionIsRejected() {
        val store = RecipeStore.new(Files.createTempDirectory("recipe-version"))
        val json = store.exportJson(recipe("r1", "Old")).replace("\"schemaVersion\": 1", "\"schemaVersion\": 99")

        assertFailsWith<IllegalArgumentException> { store.importJson(json, "2") }
    }

    @Test
    fun corruptStoreIsQuarantined() {
        val dir = Files.createTempDirectory("recipe-corrupt")
        Files.writeString(dir.resolve("explore_recipes.json"), "not json")

        assertFailsWith<IllegalStateException> { RecipeStore.new(dir).list() }
        assertTrue(Files.list(dir).use { files -> files.anyMatch { it.fileName.toString().startsWith("explore_recipes.corrupt-") } })
    }

    private fun recipe(id: String, name: String) = ExploreRecipe(
        id = id,
        name = name,
        createdAt = "1",
        updatedAt = "1",
        defaultMode = ExploreMode.Pivot,
        pivot = ExploreConfig(),
        worksheet = WorksheetConfig(),
        requiredFields = listOf(RecipeField("amount", "Amount", "decimal")),
    )
}
