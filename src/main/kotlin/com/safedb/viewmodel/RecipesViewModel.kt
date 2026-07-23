package com.safedb.viewmodel

import com.safedb.explore.ExploreRecipe
import com.safedb.service.SafeDbService
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class RecipesViewModel(
    private val service: SafeDbService,
    private val scope: CoroutineScope,
) {
    private val _recipes = MutableStateFlow<List<ExploreRecipe>>(emptyList())
    val recipes: StateFlow<List<ExploreRecipe>> = _recipes.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    suspend fun load() {
        runCatching { service.listExploreRecipes() }
            .onSuccess { _recipes.value = it; _error.value = null }
            .onFailure { _error.value = it.message ?: it.toString() }
    }

    fun save(recipe: ExploreRecipe, onComplete: (Boolean) -> Unit = {}) {
        scope.launch {
            runCatching { service.saveExploreRecipe(recipe) }
                .onSuccess { load(); onComplete(true) }
                .onFailure { _error.value = it.message ?: it.toString(); onComplete(false) }
        }
    }

    fun delete(id: String) {
        scope.launch {
            runCatching { service.deleteExploreRecipe(id) }
                .onSuccess { load() }
                .onFailure { _error.value = it.message ?: it.toString() }
        }
    }

    fun import(path: Path, onComplete: (ExploreRecipe?) -> Unit = {}) {
        scope.launch {
            runCatching {
                service.importExploreRecipe(Files.readString(path), Instant.now().epochSecond.toString())
            }.onSuccess { recipe ->
                load()
                onComplete(recipe)
            }.onFailure {
                _error.value = it.message ?: it.toString()
                onComplete(null)
            }
        }
    }

    fun export(recipe: ExploreRecipe, path: Path, onComplete: (Boolean) -> Unit = {}) {
        scope.launch {
            runCatching {
                val json = service.exportExploreRecipe(recipe)
                com.safedb.persist.atomicWrite(path, json)
            }.onSuccess { onComplete(true) }
                .onFailure { _error.value = it.message ?: it.toString(); onComplete(false) }
        }
    }

    fun clearError() {
        _error.value = null
    }
}
