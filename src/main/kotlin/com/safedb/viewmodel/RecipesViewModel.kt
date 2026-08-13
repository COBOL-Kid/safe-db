package com.safedb.viewmodel

import com.safedb.explore.ExploreRecipe
import com.safedb.service.SafeDbService
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RecipesViewModel(
    private val service: SafeDbService,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _recipes = MutableStateFlow<List<ExploreRecipe>>(emptyList())
    val recipes: StateFlow<List<ExploreRecipe>> = _recipes.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    suspend fun load() {
        capturingFailure(_error) { _recipes.value = service.listExploreRecipes() }
    }

    fun save(recipe: ExploreRecipe, onComplete: (Boolean) -> Unit = {}) {
        scope.launch {
            val saved = capturingFailure(_error) { service.saveExploreRecipe(recipe) }
            if (saved) load()
            onComplete(saved)
        }
    }

    fun delete(id: String) {
        scope.launch { if (capturingFailure(_error) { service.deleteExploreRecipe(id) }) load() }
    }

    fun import(path: Path, onComplete: (ExploreRecipe?) -> Unit = {}) {
        scope.launch {
            var imported: ExploreRecipe? = null
            capturingFailure(_error) {
                val json = withContext(ioDispatcher) { Files.readString(path) }
                imported = service.importExploreRecipe(json, Instant.now().epochSecond.toString())
            }
            if (imported != null) load()
            onComplete(imported)
        }
    }

    fun export(recipe: ExploreRecipe, path: Path, onComplete: (Boolean) -> Unit = {}) {
        scope.launch {
            onComplete(
                capturingFailure(_error) {
                    val json = service.exportExploreRecipe(recipe)
                    withContext(ioDispatcher) { com.safedb.persist.atomicWrite(path, json) }
                }
            )
        }
    }

    fun clearError() {
        _error.value = null
    }
}
