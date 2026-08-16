package com.safedb.explore

import com.safedb.model.ResultColumn
import com.safedb.model.SafeDbJson
import com.safedb.model.TableRef
import kotlin.test.Test
import kotlin.test.assertEquals

class ExploreModelsTest {
    @Test
    fun columnLabelsStripKnownAliasesAndQualifyDuplicates() {
        // SQL aliases are arbitrary, so only prefixes matching an actual TableRef alias may be
        // stripped; builder-style t<n> prefixes still work without table context.
        val tables = listOf(TableRef("public", "users", "u"), TableRef("public", "categories", "c"))
        val columns =
            listOf(
                ResultColumn("u__id", "int"),
                ResultColumn("c__id", "int"),
                ResultColumn("u__name", "text"),
            )
        assertEquals(
            mapOf("u__id" to "users.id", "c__id" to "categories.id", "u__name" to "name"),
            displayColumnLabels(columns, tables),
        )

        val unaliased =
            displayColumnLabels(
                listOf(ResultColumn("users__id", "int")),
                listOf(TableRef("public", "users", "users")),
            )
        assertEquals(mapOf("users__id" to "id"), unaliased)

        // Without table context an arbitrary alias prefix is left alone rather than guessed at.
        assertEquals("u__id", displayColumnLabel("u__id"))
        assertEquals("id", displayColumnLabel("t0__id"))
    }

    @Test
    fun configurationRoundTripsAdvancedSettings() {
        val config =
            ExploreConfig(
                rowDimensions =
                    listOf(
                        PivotDimension(
                            "placed",
                            "Month",
                            "placed-month",
                            PivotGrouping.Date(DateGroupUnit.Month),
                        )
                    ),
                columnDimensions = listOf(PivotDimension("status", id = "status")),
                measures =
                    listOf(
                        PivotMeasure(
                            alias = "margin",
                            fn = MeasureFn.Sum,
                            label = "Margin",
                            formula = "[revenue] - [cost]",
                            showAs = PivotShowAs(ShowAsMode.PercentGrandTotal),
                            numberFormat =
                                PivotNumberFormat(NumberFormatKind.Percent, decimals = 1),
                        )
                    ),
                filters = listOf(PivotFilter.Members("f1", "status", "Status", setOf("shipped"))),
                collapsedRowPaths = setOf("2026/07"),
            )

        val json = SafeDbJson.lenient.encodeToString(ExploreConfig.serializer(), config)
        val restored = SafeDbJson.lenient.decodeFromString(ExploreConfig.serializer(), json)

        assertEquals(EXPLORE_SCHEMA_VERSION, restored.schemaVersion)
        assertEquals(config, restored)
    }

    @Test
    fun measureLabelsComposeFromTheShortFormAndReadAsTheLongForm() {
        assertEquals("Count", PivotMeasure("a", MeasureFn.Count).label)
        assertEquals("Distinct values", PivotMeasure("b", MeasureFn.CountDistinct).label)
        assertEquals("StdDevP value", PivotMeasure("c", MeasureFn.StdDevPopulation).label)
        assertEquals("StdDev amount", PivotMeasure("d", MeasureFn.StdDev, "t0__amount").label)

        assertEquals("Count rows", MeasureFn.Count.label)
        assertEquals("Population standard deviation", MeasureFn.StdDevPopulation.label)
        assertEquals(
            MeasureFn.entries.size,
            MeasureFn.entries.map { it.shortLabel }.distinct().size,
        )
    }
}
