package com.safedb.viewmodel

import com.safedb.explore.ExploreConfig
import com.safedb.explore.ExploreMode
import com.safedb.explore.ExploreRecipe
import com.safedb.model.ConnectionDef
import com.safedb.model.HistoryEntry
import com.safedb.model.QueryResult
import com.safedb.model.QuerySpec
import com.safedb.model.SavedQuery
import com.safedb.model.Schema
import com.safedb.model.Settings
import com.safedb.service.SafeDbService
import java.nio.file.Files
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RecipesViewModelTest {
    @Test
    fun loadSaveDeleteImportAndExportUpdateState() = runTest {
        val service = RecipeFakeService()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val viewModel = RecipesViewModel(service, scope, StandardTestDispatcher(testScheduler))
        val recipe = recipe("r1", "Revenue")

        viewModel.load()
        viewModel.save(recipe)
        scope.advanceUntilIdle()
        assertEquals(listOf("Revenue"), viewModel.recipes.value.map { it.name })

        val exportPath = Files.createTempDirectory("recipes-vm").resolve("recipe.json")
        viewModel.export(recipe, exportPath)
        scope.advanceUntilIdle()
        assertTrue(exportPath.readText().contains("Revenue"))

        viewModel.import(exportPath)
        scope.advanceUntilIdle()
        assertEquals(2, viewModel.recipes.value.size)

        viewModel.delete("r1")
        scope.advanceUntilIdle()
        assertEquals(1, viewModel.recipes.value.size)
        assertNull(viewModel.error.value)
    }

    @Test
    fun failuresAreExposedAndClearable() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val viewModel = RecipesViewModel(RecipeFakeService(fail = true), scope)

        viewModel.save(recipe("r", "Fail"))
        scope.advanceUntilIdle()
        assertEquals("recipe failure", viewModel.error.value)
        viewModel.clearError()
        assertNull(viewModel.error.value)
    }

    private fun recipe(id: String, name: String) = ExploreRecipe(
        id = id, name = name, createdAt = "1", updatedAt = "1",
        defaultMode = ExploreMode.Pivot, pivot = ExploreConfig(),
    )
}

private class RecipeFakeService(private val fail: Boolean = false) : SafeDbService {
    private val recipes = mutableListOf<ExploreRecipe>()
    private var lastExported: ExploreRecipe? = null
    override suspend fun listExploreRecipes(): List<ExploreRecipe> = if (fail) error("recipe failure") else recipes.toList()
    override suspend fun saveExploreRecipe(recipe: ExploreRecipe) {
        if (fail) error("recipe failure")
        recipes.removeAll { it.id == recipe.id }
        recipes += recipe
    }
    override suspend fun deleteExploreRecipe(id: String) { recipes.removeAll { it.id == id } }
    override suspend fun exportExploreRecipe(recipe: ExploreRecipe): String {
        lastExported = recipe
        return "exported:${recipe.name}"
    }
    override suspend fun importExploreRecipe(json: String, nowEpochSec: String): ExploreRecipe {
        val decoded = requireNotNull(lastExported)
        val imported = decoded.copy(id = "imported", createdAt = nowEpochSec, updatedAt = nowEpochSec)
        recipes += imported
        return imported
    }
    override suspend fun testConnection(def: ConnectionDef, password: String?) = "ok"
    override suspend fun createConnection(def: ConnectionDef, password: String) = def
    override suspend fun updateConnection(def: ConnectionDef, password: String?) = Unit
    override suspend fun listConnections() = emptyList<ConnectionDef>()
    override suspend fun deleteConnection(id: String) = Unit
    override suspend fun lockCredentials() = Unit
    override suspend fun getSchema(connectionId: String) = Schema(emptyList())
    override suspend fun runQuery(request: com.safedb.service.QueryRunRequest) = error("unused")
    override suspend fun listSavedQueries() = emptyList<SavedQuery>()
    override suspend fun saveSavedQuery(query: SavedQuery) = Unit
    override suspend fun deleteSavedQuery(id: String) = Unit
    override suspend fun listHistory() = emptyList<HistoryEntry>()
    override suspend fun clearHistory() = Unit
    override suspend fun getSettings() = Settings.default()
    override suspend fun saveSettings(settings: Settings) = Unit
}
