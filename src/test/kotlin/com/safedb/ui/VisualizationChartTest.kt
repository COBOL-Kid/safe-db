package com.safedb.ui

import com.safedb.explore.BarArrangement
import com.safedb.explore.BarOrientation
import com.safedb.explore.ChartType
import com.safedb.explore.VisualizationConfig
import com.safedb.explore.VisualizationField
import com.safedb.explore.VisualizationMark
import com.safedb.explore.VisualizationMeasure
import com.safedb.explore.VisualizationPreview
import com.safedb.model.QueryResult
import com.safedb.model.ResultColumn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VisualizationChartTest {
    @Test
    fun barGeometryProducesDistinctClickableRegionsAtNormalAndNarrowSizes() {
        val preview = preview(ChartType.Bar)
        val normal =
            visualizationGeometry(
                preview,
                VisualizationConfig(chartType = ChartType.Bar),
                900f,
                500f,
            )
        val narrow =
            visualizationGeometry(
                preview,
                VisualizationConfig(chartType = ChartType.Bar),
                260f,
                220f,
            )

        assertEquals(4, normal.regions.size)
        assertEquals(4, narrow.regions.size)
        assertTrue(normal.regions.all { it.bounds.width > 0f && it.bounds.height > 0f })
        assertTrue(narrow.regions.all { it.bounds.width > 0f && it.bounds.height > 0f })
        assertEquals(4, normal.regions.map { it.bounds }.distinct().size)
    }

    @Test
    fun stackedBarsShareCategoryBandAndAccumulateVertically() {
        val preview = preview(ChartType.Bar)
        val geometry =
            visualizationGeometry(
                preview,
                VisualizationConfig(
                    chartType = ChartType.Bar,
                    barArrangement = BarArrangement.Stacked,
                ),
                800f,
                500f,
            )
        val firstCategory = geometry.regions.take(2).map { it.bounds }

        assertEquals(firstCategory[0].left, firstCategory[1].left)
        assertEquals(firstCategory[0].right, firstCategory[1].right)
        assertEquals(firstCategory[0].top, firstCategory[1].bottom)
    }

    @Test
    fun horizontalBarsWithNegativeValuesUseComputedZeroBaseline() {
        val preview =
            VisualizationPreview(
                chartType = ChartType.Bar,
                title = "Chart",
                marks =
                    listOf(
                        mark("negative", "a", "A", "one", -10.0, 1.0),
                        mark("positive", "b", "B", "one", 20.0, 2.0),
                    ),
            )
        val geometry =
            visualizationGeometry(
                preview,
                VisualizationConfig(
                    chartType = ChartType.Bar,
                    barOrientation = BarOrientation.Horizontal,
                ),
                600f,
                380f,
            )
        val zero =
            geometry.plot.left +
                ((0.0 - geometry.yMin) / (geometry.yMax - geometry.yMin) * geometry.plot.width)
                    .toFloat()
        val negative = geometry.regions.first { it.markId == "negative" }.bounds
        val positive = geometry.regions.first { it.markId == "positive" }.bounds

        assertEquals(zero, negative.right)
        assertTrue(negative.left >= geometry.plot.left)
        assertEquals(zero, positive.left)
        assertTrue(positive.right <= geometry.plot.right)
    }

    @Test
    fun scatterGeometryMapsEveryPointInsidePlot() {
        val preview = preview(ChartType.Scatter)
        val geometry =
            visualizationGeometry(
                preview,
                VisualizationConfig(chartType = ChartType.Scatter),
                600f,
                380f,
            )

        assertEquals(4, geometry.points.size)
        assertTrue(geometry.points.values.all { geometry.plot.contains(it) })
    }

    @Test
    fun geometryHonoursMeasuredInsets() {
        val geometry =
            visualizationGeometry(
                preview(ChartType.Bar),
                VisualizationConfig(chartType = ChartType.Bar),
                900f,
                500f,
                PlotInsets(left = 130f, bottom = 90f),
            )

        assertEquals(130f, geometry.plot.left)
        assertEquals(410f, geometry.plot.bottom)
        assertTrue(
            geometry.regions.all {
                it.bounds.left >= geometry.plot.left && it.bounds.right <= geometry.plot.right
            }
        )
    }

    @Test
    fun categoryCentersUseBandCenterNotFirstSeriesBar() {
        val geometry =
            visualizationGeometry(
                preview(ChartType.Bar),
                VisualizationConfig(chartType = ChartType.Bar),
                900f,
                500f,
            )
        val band = geometry.plot.width / 2f
        val center = geometry.categoryCenters.getValue("a")

        assertEquals(geometry.plot.left + band / 2f, center)
        assertTrue(center > geometry.points.getValue("a-1").x)
        assertTrue(center < geometry.points.getValue("a-2").x)
    }

    @Test
    fun compactNumberCoversBillionsAndTrillions() {
        assertEquals("5.0T", compactNumber(5e12))
        assertEquals("-2.5B", compactNumber(-2.5e9))
        assertEquals("1.5M", compactNumber(1.5e6))
        assertEquals("12", compactNumber(12.0))
    }

    @Test
    fun switchingToScatterClearsIncompatibleAssignments() {
        val sample =
            QueryResult(
                columns =
                    listOf(ResultColumn("status", "varchar"), ResultColumn("amount", "decimal")),
                rows = emptyList(),
                rowCount = 0,
                truncated = false,
                warnings = emptyList(),
            )
        val fields = buildExploreFieldOptions(sample, emptyList())
        val config =
            VisualizationConfig(
                chartType = ChartType.Bar,
                x = VisualizationField("status"),
                values = listOf(VisualizationMeasure.countRows()),
                size = VisualizationField("status"),
            )

        val scatter = config.forChartType(ChartType.Scatter, fields)

        assertEquals(null, scatter.x)
        assertTrue(scatter.values.isEmpty())
        assertEquals(null, scatter.size)
    }

    private fun preview(type: ChartType) =
        VisualizationPreview(
            chartType = type,
            title = "Chart",
            marks =
                listOf(
                    mark("a-1", "a", "A", "one", 10.0, 1.0),
                    mark("a-2", "a", "A", "two", 5.0, 2.0),
                    mark("b-1", "b", "B", "one", 8.0, 3.0),
                    mark("b-2", "b", "B", "two", 4.0, 4.0),
                ),
        )

    private fun mark(id: String, key: String, label: String, series: String, y: Double, x: Double) =
        VisualizationMark(
            id = id,
            xKey = key,
            xLabel = label,
            xValue = x,
            y = y,
            formattedY = y.toString(),
            seriesKey = series,
            seriesLabel = series,
            measureAlias = "value",
            measureLabel = "Value",
            sourceRowIndices = listOf(0),
        )
}
