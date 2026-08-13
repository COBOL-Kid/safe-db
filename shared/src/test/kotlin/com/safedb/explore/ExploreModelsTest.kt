package com.safedb.explore

import com.safedb.model.SafeDbJson
import kotlin.test.Test
import kotlin.test.assertEquals

class ExploreModelsTest {
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
