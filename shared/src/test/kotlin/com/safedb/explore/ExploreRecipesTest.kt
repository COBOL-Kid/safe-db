package com.safedb.explore

import com.safedb.model.FilterGroup
import com.safedb.model.QueryResult
import com.safedb.model.QuerySpec
import com.safedb.model.ResultColumn
import com.safedb.model.TableRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExploreRecipesTest {
    @Test
    fun exactAndUniqueLabelTypeMatchesResolveAutomatically() {
        val recipe = recipe(
            listOf(
                RecipeField("t0__amount", "Amount", "decimal", "orders"),
                RecipeField("old__status", "Status", "varchar", "orders"),
            ),
        )
        val mapping = resolveRecipeFields(recipe, sample(), spec())

        assertEquals("t0__amount", mapping.resolved["t0__amount"])
        assertEquals("t0__status", mapping.resolved["old__status"])
        assertTrue(mapping.unresolved.isEmpty())
    }

    @Test
    fun ambiguousAndIncompatibleFieldsStayUnresolvedUntilManualMapping() {
        val ambiguousSample = QueryResult(
            columns = listOf(ResultColumn("t0__amount", "decimal"), ResultColumn("t1__amount", "decimal")),
            rows = emptyList(), rowCount = 0, truncated = false, warnings = emptyList(),
        )
        val recipe = recipe(listOf(RecipeField("old__amount", "Amount", "decimal")))

        assertEquals(1, resolveRecipeFields(recipe, ambiguousSample, spec()).unresolved.size)
        assertEquals(
            "t1__amount",
            resolveRecipeFields(recipe, ambiguousSample, spec(), mapOf("old__amount" to "t1__amount")).resolved["old__amount"],
        )
        assertTrue(!compatibleTypes("decimal", "varchar"))
        val incompatibleSample = QueryResult(
            columns = listOf(ResultColumn("status", "varchar")), rows = emptyList(), rowCount = 0, truncated = false, warnings = emptyList(),
        )
        assertEquals(1, resolveRecipeFields(recipe, incompatibleSample, spec(), mapOf("old__amount" to "status")).unresolved.size)
    }

    @Test
    fun exactAliasesStillRequireCompatibleTypeAndSourceTable() {
        val recipe = recipe(listOf(RecipeField("t0__amount", "Amount", "decimal", "orders")))
        val incompatibleType = QueryResult(
            columns = listOf(ResultColumn("t0__amount", "varchar")),
            rows = emptyList(), rowCount = 0, truncated = false, warnings = emptyList(),
        )
        val differentSource = QuerySpec(
            tables = listOf(TableRef("public", "refunds", "t0")),
            columns = emptyList(), joins = emptyList(), filters = FilterGroup.empty(), limit = 100,
        )

        assertEquals(1, resolveRecipeFields(recipe, incompatibleType, spec()).unresolved.size)
        assertEquals(1, resolveRecipeFields(recipe, sample(), differentSource).unresolved.size)
    }

    @Test
    fun remapRewritesPivotWorksheetAndFormulaReferences() {
        val recipe = ExploreRecipe(
            id = "r",
            name = "Mapped",
            createdAt = "1",
            updatedAt = "1",
            defaultMode = ExploreMode.Worksheet,
            pivot = ExploreConfig(rowDimensions = listOf(PivotDimension("old"))),
            worksheet = WorksheetConfig(
                groups = listOf(WorksheetGroup("g", "old")),
                calculations = listOf(WorksheetCalculation.RowFormula("calc", "Calc", "[old] + 1")),
            ),
        )

        val mapped = remapRecipe(recipe, mapOf("old" to "new"))

        assertEquals("new", mapped.pivot?.rowDimensions?.single()?.column)
        assertEquals("new", mapped.worksheet?.groups?.single()?.column)
        assertEquals("[new] + 1", (mapped.worksheet?.calculations?.single() as WorksheetCalculation.RowFormula).formula)
    }

    @Test
    fun remapRewritesVisualizationReferences() {
        val recipe = ExploreRecipe(
            id = "v",
            name = "Chart",
            createdAt = "1",
            updatedAt = "1",
            defaultMode = ExploreMode.Visualization,
            visualization = VisualizationConfig(
                chartType = ChartType.Scatter,
                x = VisualizationField("old"),
                values = listOf(VisualizationMeasure("value", MeasureFn.Sum, "old", aggregate = false)),
                series = VisualizationField("old_series"),
                size = VisualizationField("old_size"),
                filters = listOf(PivotFilter.Members("f", "old", "Amount")),
            ),
        )

        val mapped = remapRecipe(
            recipe,
            mapOf("old" to "new", "old_series" to "new_series", "old_size" to "new_size"),
        )

        assertEquals("new", mapped.visualization?.x?.column)
        assertEquals("new", mapped.visualization?.values?.single()?.sourceColumn)
        assertEquals("new_series", mapped.visualization?.series?.column)
        assertEquals("new_size", mapped.visualization?.size?.column)
        assertEquals("new", mapped.visualization?.filters?.single()?.column)
    }

    @Test
    fun recipeFieldsIncludesEveryVisualizationChannel() {
        val visualization = VisualizationConfig(
            chartType = ChartType.Scatter,
            x = VisualizationField("t0__amount"),
            values = listOf(VisualizationMeasure("value", MeasureFn.Sum, "t0__amount", aggregate = false)),
            series = VisualizationField("t0__status"),
            size = VisualizationField("t0__amount"),
            filters = listOf(PivotFilter.Members("f", "t0__status", "Status")),
        )

        val fields = recipeFields(sample(), spec(), null, null, visualization)

        assertEquals(setOf("t0__amount", "t0__status"), fields.map { it.column }.toSet())
    }

    private fun recipe(fields: List<RecipeField>) = ExploreRecipe(
        id = "r", name = "Recipe", createdAt = "1", updatedAt = "1",
        defaultMode = ExploreMode.Pivot, pivot = ExploreConfig(), requiredFields = fields,
    )

    private fun sample() = QueryResult(
        columns = listOf(ResultColumn("t0__amount", "decimal"), ResultColumn("t0__status", "varchar")),
        rows = emptyList(), rowCount = 0, truncated = false, warnings = emptyList(),
    )

    private fun spec() = QuerySpec(
        tables = listOf(TableRef("public", "orders", "t0")),
        columns = emptyList(), joins = emptyList(), filters = FilterGroup.empty(), limit = 100,
    )
}
