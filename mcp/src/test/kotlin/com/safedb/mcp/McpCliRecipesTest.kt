package com.safedb.mcp

import com.safedb.explore.ExploreRecipe
import com.safedb.model.SafeDbJson
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class McpCliRecipesTest {
    @Test
    fun recipesCommandsParse() {
        assertEquals(McpCommand.RecipesList, parseMcpArgs(arrayOf("recipes", "list")))
        assertEquals(
            McpCommand.RecipesImport("/tmp/recipe.json"),
            parseMcpArgs(arrayOf("recipes", "import", "/tmp/recipe.json")),
        )
        assertEquals(
            McpCommand.RecipesExport("r1"),
            parseMcpArgs(arrayOf("recipes", "export", "r1")),
        )
        assertTrue(MCP_USAGE.contains("safe-db-mcp recipes list"))
        assertTrue(MCP_USAGE.contains("safe-db-mcp recipes import /absolute/path/recipe.json"))
        assertTrue(MCP_USAGE.contains("safe-db-mcp recipes export <recipe_id>"))
    }

    @Test
    fun malformedRecipesCommandsAreRejected() {
        val invalid =
            listOf(
                arrayOf("recipes"),
                arrayOf("recipes", "unknown"),
                arrayOf("recipes", "list", "extra"),
                arrayOf("recipes", "import"),
                arrayOf("recipes", "import", "--force"),
                arrayOf("recipes", "export"),
                arrayOf("recipes", "export", "--force"),
            )

        invalid.forEach { args ->
            assertFailsWith<McpCliUsageException>("Expected rejection for ${args.toList()}") {
                parseMcpArgs(args)
            }
        }
    }

    @Test
    fun recipesImportListAndExportRoundTrip() = runBlocking {
        val directory = Files.createTempDirectory("safedb-mcp-cli")
        val recipeFile = directory.resolve("recipe.json")
        Files.writeString(recipeFile, MINIMAL_RECIPE_JSON)
        val service = realRecipeService(directory)

        val importIo = BufferCliIo()
        val importCommand = parseMcpArgs(arrayOf("recipes", "import", recipeFile.toString()))
        assertEquals(0, executeMcpCommand(importCommand, service, importIo, tty = false))

        val imported = service.listExploreRecipes().single()
        assertEquals("By region", imported.name)
        assertNotEquals("ignored", imported.id)
        assertEquals("Imported ${imported.id}  By region\n", importIo.stdout.toString())
        assertEquals("", importIo.stderr.toString())

        val listIo = BufferCliIo()
        assertEquals(
            0,
            executeMcpCommand(McpCommand.RecipesList, service, listIo, tty = false),
        )
        assertEquals(
            "${imported.id}  By region  Pivot  0 fields  ${imported.updatedAt}\n",
            listIo.stdout.toString(),
        )

        val exportIo = BufferCliIo()
        assertEquals(
            0,
            executeMcpCommand(
                McpCommand.RecipesExport(imported.id),
                service,
                exportIo,
                tty = false,
            ),
        )
        val exported = exportIo.stdout.toString().trim()
        assertTrue(exported.contains("\"name\": \"By region\""))
        val decoded = SafeDbJson.lenient.decodeFromString(ExploreRecipe.serializer(), exported)
        assertEquals(imported.id, decoded.id)
        assertEquals("By region", decoded.name)
        assertEquals("", exportIo.stderr.toString())
    }

    @Test
    fun recipesListWithoutRecipesPrintsNone() = runBlocking {
        val directory = Files.createTempDirectory("safedb-mcp-cli")
        val io = BufferCliIo()

        assertEquals(
            0,
            executeMcpCommand(
                McpCommand.RecipesList,
                realRecipeService(directory),
                io,
                tty = false,
            ),
        )
        assertEquals("No recipes.\n", io.stdout.toString())
    }

    @Test
    fun recipesExportUnknownIdExits1() = runBlocking {
        val directory = Files.createTempDirectory("safedb-mcp-cli")
        val service = realRecipeService(directory)
        val io = BufferCliIo()

        assertEquals(
            1,
            executeMcpCommand(McpCommand.RecipesExport("missing"), service, io, tty = false),
        )
        assertEquals("safe-db-mcp: recipe not found: missing\n", io.stderr.toString())
        assertEquals("", io.stdout.toString())
    }

    @Test
    fun recipesInvalidImportExitsNonZeroWithoutSaving() = runBlocking {
        val directory = Files.createTempDirectory("safedb-mcp-cli")
        val recipeFile = directory.resolve("recipe.json")
        Files.writeString(recipeFile, "{ not a recipe")
        val service = realRecipeService(directory)
        val io = BufferCliIo()
        val command = parseMcpArgs(arrayOf("recipes", "import", recipeFile.toString()))

        assertEquals(1, executeMcpCommand(command, service, io, tty = false))
        assertTrue(service.listExploreRecipes().isEmpty())
        assertTrue(io.stderr.toString().startsWith("safe-db-mcp: "))
        assertEquals("", io.stdout.toString())
    }

    @Test
    fun recipesRelativeImportPathIsRejectedWithoutSaving() = runBlocking {
        val directory = Files.createTempDirectory("safedb-mcp-cli")
        val service = realRecipeService(directory)
        val io = BufferCliIo()

        assertEquals(
            2,
            executeMcpCommand(McpCommand.RecipesImport("recipe.json"), service, io, tty = false),
        )
        assertTrue(io.stderr.toString().contains("must be absolute"))
        assertTrue(service.listExploreRecipes().isEmpty())
    }

    @Test
    fun recipesImportRejectsMissingAndOversizedFiles() = runBlocking {
        val directory = Files.createTempDirectory("safedb-mcp-cli")
        val service = realRecipeService(directory)

        val missingIo = BufferCliIo()
        assertEquals(
            1,
            executeMcpCommand(
                McpCommand.RecipesImport(directory.resolve("missing.json").toString()),
                service,
                missingIo,
                tty = false,
            ),
        )
        assertTrue(missingIo.stderr.toString().contains("Recipe file not found"))

        val oversized = directory.resolve("oversized.json")
        Files.write(oversized, ByteArray(1024 * 1024 + 1))
        val oversizedIo = BufferCliIo()
        assertEquals(
            1,
            executeMcpCommand(
                McpCommand.RecipesImport(oversized.toString()),
                service,
                oversizedIo,
                tty = false,
            ),
        )
        assertTrue(oversizedIo.stderr.toString().contains("larger than 1 MiB"))
        assertTrue(service.listExploreRecipes().isEmpty())
    }

    @Test
    fun recipesImportRejectsInvalidUtf8WithoutSaving() = runBlocking {
        val directory = Files.createTempDirectory("safedb-mcp-cli")
        val recipeFile = directory.resolve("invalid-utf8.json")
        Files.write(recipeFile, byteArrayOf(0x7B, 0xFF.toByte(), 0x7D))
        val service = realRecipeService(directory)
        val io = BufferCliIo()
        val command = parseMcpArgs(arrayOf("recipes", "import", recipeFile.toString()))

        assertEquals(1, executeMcpCommand(command, service, io, tty = false))
        assertTrue(io.stderr.toString().contains("valid UTF-8"))
        assertTrue(service.listExploreRecipes().isEmpty())
        assertEquals("", io.stdout.toString())
    }
}
