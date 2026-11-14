package com.safedb.mcp

import com.safedb.service.SafeDbService
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MAX_RECIPE_IMPORT_BYTES = 1024 * 1024

internal fun parseRecipesCommand(args: Array<String>): McpCommand {
    if (args.size < 2) throw McpCliUsageException()
    return when (args[1]) {
        "list" -> {
            if (args.size != 2) throw McpCliUsageException()
            McpCommand.RecipesList
        }
        "import" -> {
            if (args.size != 3 || args[2].startsWith("-")) throw McpCliUsageException()
            McpCommand.RecipesImport(args[2])
        }
        "export" -> {
            if (args.size != 3 || args[2].startsWith("-")) throw McpCliUsageException()
            McpCommand.RecipesExport(args[2])
        }
        else -> throw McpCliUsageException()
    }
}

internal suspend fun listRecipes(service: SafeDbService, io: CliIo): Int {
    val recipes = service.listExploreRecipes()
    if (recipes.isEmpty()) {
        io.print("No recipes.")
    } else {
        recipes.forEach { recipe ->
            io.print(
                "${recipe.id}  ${recipe.name}  ${recipe.defaultMode}  " +
                    "${recipe.requiredFields.size} fields  ${recipe.updatedAt}",
            )
        }
    }
    return 0
}

internal suspend fun importRecipe(path: String, service: SafeDbService, io: CliIo): Int {
    val file = Path.of(path)
    if (!file.isAbsolute) {
        throw McpCliUsageException("Recipe path must be absolute: $path")
    }
    val json =
        withContext(Dispatchers.IO) {
            if (!Files.isRegularFile(file)) {
                throw IllegalArgumentException("Recipe file not found: $path")
            }
            val bytes =
                Files.newInputStream(file).use { it.readNBytes(MAX_RECIPE_IMPORT_BYTES + 1) }
            if (bytes.size > MAX_RECIPE_IMPORT_BYTES) {
                throw IllegalArgumentException("Recipe file is larger than 1 MiB: $path")
            }
            try {
                StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            } catch (error: Exception) {
                throw IllegalArgumentException("Recipe file is not valid UTF-8: $path", error)
            }
        }
    val recipe = service.importExploreRecipe(json, Instant.now().epochSecond.toString())
    io.print("Imported ${recipe.id}  ${recipe.name}")
    return 0
}

internal suspend fun exportRecipe(id: String, service: SafeDbService, io: CliIo): Int {
    val recipe = requireRecipe(service, id, io) ?: return 1
    io.print(service.exportExploreRecipe(recipe))
    return 0
}
