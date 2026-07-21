package com.safedb.explore

import com.safedb.model.SafeDbJson
import kotlin.test.Test
import kotlin.test.assertEquals

class ExploreModelsTest {
    @Test
    fun versionTwoConfigurationRoundTripsAdvancedSettings() {
        val config = ExploreConfig(
            rowDimensions = listOf(
                PivotDimension("placed", "Month", "placed-month", PivotGrouping.Date(DateGroupUnit.Month)),
            ),
            columnDimensions = listOf(PivotDimension("status", id = "status")),
            measures = listOf(
                PivotMeasure(
                    alias = "margin",
                    fn = MeasureFn.Sum,
                    label = "Margin",
                    formula = "[revenue] - [cost]",
                    showAs = PivotShowAs(ShowAsMode.PercentGrandTotal),
                    numberFormat = PivotNumberFormat(NumberFormatKind.Percent, decimals = 1),
                ),
            ),
            filters = listOf(PivotFilter.Members("f1", "status", "Status", setOf("shipped"))),
            collapsedRowPaths = setOf("2026/07"),
        )

        val json = SafeDbJson.lenient.encodeToString(ExploreConfig.serializer(), config)
        val restored = SafeDbJson.lenient.decodeFromString(ExploreConfig.serializer(), json)

        assertEquals(EXPLORE_SCHEMA_VERSION, restored.schemaVersion)
        assertEquals(config, restored)
    }
}
