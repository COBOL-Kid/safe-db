package com.safedb.store

import com.safedb.explore.ChartType
import com.safedb.explore.EXPLORE_SCHEMA_VERSION
import com.safedb.explore.ExploreConfig
import com.safedb.explore.ExploreMode
import com.safedb.explore.ExploreRecipe
import com.safedb.explore.MeasureFn
import com.safedb.explore.RecipeField
import com.safedb.explore.VisualizationConfig
import com.safedb.explore.VisualizationField
import com.safedb.explore.VisualizationMeasure
import com.safedb.explore.WorksheetColumnLayout
import com.safedb.explore.WorksheetConfig
import com.safedb.explore.WorksheetValueRef
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
        assertEquals(false, store.list().single().worksheet?.columnLayout?.single()?.visible)
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
        val json =
            store
                .exportJson(recipe("r1", "Old"))
                .replace("\"schemaVersion\": 1", "\"schemaVersion\": 99")

        assertFailsWith<IllegalArgumentException> { store.importJson(json, "2") }
    }

    @Test
    fun corruptStoreIsQuarantined() {
        val dir = Files.createTempDirectory("recipe-corrupt")
        Files.writeString(dir.resolve("explore_recipes.json"), "not json")

        assertFailsWith<IllegalStateException> { RecipeStore.new(dir).list() }
        assertTrue(
            Files.list(dir).use { files ->
                files.anyMatch { it.fileName.toString().startsWith("explore_recipes.corrupt-") }
            }
        )
    }

    @Test
    fun visualizationRoundTripsAndOldPlaceholderRemainsCompatible() {
        val store = RecipeStore.new(Files.createTempDirectory("recipe-visualization"))
        val chart =
            ExploreRecipe(
                id = "chart",
                name = "Chart",
                createdAt = "1",
                updatedAt = "1",
                defaultMode = ExploreMode.Visualization,
                visualization =
                    VisualizationConfig(
                        chartType = ChartType.Bar,
                        x = VisualizationField("status"),
                        values = listOf(VisualizationMeasure("amount", MeasureFn.Sum, "amount")),
                    ),
            )

        store.save(chart)
        assertEquals(ChartType.Bar, store.list().single().visualization?.chartType)

        val placeholder =
            """
            {
              "schemaVersion": 1,
              "id": "old",
              "name": "Old chart",
              "createdAt": "1",
              "updatedAt": "1",
              "defaultMode": "Visualization",
              "visualization": {"schemaVersion": 1},
              "worksheet": {"schemaVersion": 1}
            }
            """
                .trimIndent()
        val imported = store.importJson(placeholder, "2")
        assertEquals(ChartType.Auto, imported.visualization?.chartType)
        assertFalse(imported.visualization?.isConfigured() ?: true)
        assertTrue(imported.worksheet?.columnLayout?.isEmpty() == true)
    }

    @Test
    fun unsupportedPivotVersionIsRejected() {
        val store = RecipeStore.new(Files.createTempDirectory("recipe-pivot-version"))
        val json = pivotRecipeJson(pivotVersion = EXPLORE_SCHEMA_VERSION + 1)

        assertFailsWith<IllegalArgumentException> { store.importJson(json, "2") }
    }

    private fun pivotRecipeJson(pivotVersion: Int) =
        """
        {
          "schemaVersion": 1,
          "id": "old",
          "name": "Sales by status",
          "createdAt": "1",
          "updatedAt": "1",
          "defaultMode": "Pivot",
          "pivot": {
            "schemaVersion": $pivotVersion,
            "rowDimensions": [{"column": "t0__region", "label": "Region", "id": "region"}],
            "columnDimensions": [],
            "collapsedColumnPaths": ["shipped"]
          }
        }
        """
            .trimIndent()

    private fun recipe(id: String, name: String) =
        ExploreRecipe(
            id = id,
            name = name,
            createdAt = "1",
            updatedAt = "1",
            defaultMode = ExploreMode.Pivot,
            pivot = ExploreConfig(),
            worksheet =
                WorksheetConfig(
                    columnLayout =
                        listOf(
                            WorksheetColumnLayout(
                                WorksheetValueRef.Column("amount"),
                                visible = false,
                            )
                        )
                ),
            requiredFields = listOf(RecipeField("amount", "Amount", "decimal")),
        )
}
