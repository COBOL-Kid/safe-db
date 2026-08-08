package com.safedb.ui

import com.safedb.explore.ExploreColumnGroup
import com.safedb.explore.ExploreConfig
import com.safedb.explore.ExplorePivotLayout
import com.safedb.explore.ExploreSortTarget
import com.safedb.explore.MeasureFn
import com.safedb.explore.PivotDimension
import com.safedb.explore.PivotMeasure
import com.safedb.explore.SortDir
import com.safedb.model.QueryResult
import com.safedb.model.ResultColumn
import com.safedb.model.TableRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExploreUiModelTest {
    @Test
    fun truncationExplanationIsSelectedOnlyForTruncatedSamples() {
        assertEquals(
            "You’re viewing a sample, so totals may not represent the full result.",
            exploreTruncationExplanation(truncated = true),
        )
        assertNull(exploreTruncationExplanation(truncated = false))
    }

    @Test
    fun fieldOptionsQualifyOnlyDuplicateJoinedLabels() {
        val options =
            buildExploreFieldOptions(
                sample =
                    QueryResult(
                        columns =
                            listOf(
                                ResultColumn("t0__id", "bigint"),
                                ResultColumn("t0__status", "varchar"),
                                ResultColumn("t1__status", "varchar"),
                            ),
                        rows = emptyList(),
                        rowCount = 0,
                        truncated = false,
                        warnings = emptyList(),
                    ),
                tables =
                    listOf(
                        TableRef("public", "orders", "t0"),
                        TableRef("public", "shipments", "t1"),
                    ),
            )

        assertEquals(listOf("id", "orders.status", "shipments.status"), options.map { it.label })
        assertEquals(listOf("orders", "orders", "shipments"), options.map { it.sourceTableLabel })
        assertEquals(
            listOf("orders · bigint", "orders · varchar", "shipments · varchar"),
            options.map { it.supportingText() },
        )
    }

    @Test
    fun fieldOptionsDisambiguateRepeatedTableNamesWithSchema() {
        val options =
            buildExploreFieldOptions(
                sample =
                    QueryResult(
                        columns =
                            listOf(
                                ResultColumn("t0__occurred_at", "timestamp"),
                                ResultColumn("t1__occurred_at", "timestamp"),
                                ResultColumn("t2__status", "varchar"),
                            ),
                        rows = emptyList(),
                        rowCount = 0,
                        truncated = false,
                        warnings = emptyList(),
                    ),
                tables =
                    listOf(
                        TableRef("analytics", "events", "t0"),
                        TableRef("archive", "events", "t1"),
                        TableRef("public", "orders", "t2"),
                    ),
            )

        assertEquals(
            listOf("analytics.events", "archive.events", "orders"),
            options.map { it.sourceTableLabel },
        )
        assertTrue(options[0].matchesSearch("analytics"))
        assertTrue(options[1].matchesSearch("archive.events"))
        assertTrue(options[2].matchesSearch("orders"))
    }

    @Test
    fun unmappedFieldsRemainTypeOnlyAndAreGroupedSeparately() {
        val options =
            buildExploreFieldOptions(
                sample =
                    QueryResult(
                        columns =
                            listOf(
                                ResultColumn("t0__status", "varchar"),
                                ResultColumn("expression_total", "decimal"),
                            ),
                        rows = emptyList(),
                        rowCount = 0,
                        truncated = false,
                        warnings = emptyList(),
                    ),
                tables = listOf(TableRef("public", "orders", "t0")),
            )

        assertEquals("decimal", options[1].supportingText())
        assertEquals(listOf("orders", "Other fields"), groupExploreFields(options).map { it.label })
        assertTrue(options[1].matchesSearch("expression"))
    }

    @Test
    fun valueFunctionsFollowFieldType() {
        val numeric = buildField("amount", "decimal")
        val text = buildField("status", "varchar")

        assertEquals(
            listOf(
                MeasureFn.Sum,
                MeasureFn.Avg,
                MeasureFn.Min,
                MeasureFn.Max,
                MeasureFn.CountNumbers,
                MeasureFn.CountDistinct,
                MeasureFn.Product,
                MeasureFn.StdDev,
                MeasureFn.StdDevPopulation,
                MeasureFn.Variance,
                MeasureFn.VariancePopulation,
            ),
            availableMeasureFunctions(numeric),
        )
        assertEquals(
            listOf(MeasureFn.CountDistinct, MeasureFn.Min, MeasureFn.Max),
            availableMeasureFunctions(text),
        )
        assertEquals("Average amount", measureFor(numeric, MeasureFn.Avg).label)
    }

    @Test
    fun clickingSameHeaderTogglesDirectionAndMeasuresStartDescending() {
        val target = ExploreSortTarget.Measure("revenue")
        val first = toggleExploreSort(ExploreConfig(), target)
        val second = toggleExploreSort(first, target)

        assertEquals(SortDir.Desc, first.sort?.dir)
        assertEquals(SortDir.Asc, second.sort?.dir)
    }

    @Test
    fun dimensionsCanBeReorderedWithoutChangingMembers() {
        val region = PivotDimension("region")
        val status = PivotDimension("status")

        assertEquals(listOf(status, region), moveDimension(listOf(region, status), status, -1))
        assertEquals(listOf(region, status), moveDimension(listOf(region, status), region, -1))
    }

    @Test
    fun groupedHeadersMapRepeatedMeasureColumnsBackToSortTargets() {
        val revenue = PivotMeasure("revenue", MeasureFn.Sum, "amount", "Revenue")
        val layout =
            ExplorePivotLayout(
                rowDimensions = listOf(PivotDimension("region", "Region")),
                columnDimension = PivotDimension("status", "Status"),
                measures = listOf(revenue),
                columnGroups =
                    listOf(
                        ExploreColumnGroup("pending", 1, listOf("revenue")),
                        ExploreColumnGroup("shipped", 2, listOf("revenue")),
                        ExploreColumnGroup("Total", 3, listOf("revenue"), isTotal = true),
                    ),
                hasGrandTotalRow = true,
            )

        assertEquals(ExploreSortTarget.Dimension("region"), pivotSortTarget(layout, 0))
        assertEquals(ExploreSortTarget.Measure("revenue"), pivotSortTarget(layout, 2))
        assertEquals("Revenue", pivotLeafLabel(layout, 3, "Total Revenue"))
        assertTrue(layout.hasGrandTotalRow)
        assertFalse(layout.columnGroups.first().isTotal)
    }

    private fun buildField(name: String, type: String): ExploreFieldOption =
        buildExploreFieldOptions(
                QueryResult(
                    columns = listOf(ResultColumn(name, type)),
                    rows = emptyList(),
                    rowCount = 0,
                    truncated = false,
                    warnings = emptyList(),
                ),
                tables = emptyList(),
            )
            .single()
}
