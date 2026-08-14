package com.safedb.ui

import androidx.compose.ui.geometry.Rect
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
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
    fun stackedNegativeBarsSumRangeAndStayClickable() {
        val preview =
            VisualizationPreview(
                chartType = ChartType.Bar,
                title = "Chart",
                marks =
                    listOf(
                        mark("n1", "a", "A", "one", -10.0, 1.0),
                        mark("n2", "a", "A", "two", -20.0, 2.0),
                    ),
            )
        val config =
            VisualizationConfig(
                chartType = ChartType.Bar,
                barArrangement = BarArrangement.Stacked,
            )
        val geometry = visualizationGeometry(preview, config, 800f, 500f)
        val first = geometry.regions.first { it.markId == "n1" }.bounds
        val second = geometry.regions.first { it.markId == "n2" }.bounds
        val zero =
            geometry.plot.bottom -
                ((0.0 - geometry.yMin) / (geometry.yMax - geometry.yMin) * geometry.plot.height)
                    .toFloat()

        assertEquals(-30.0, visualizationValueRange(preview, config).start)
        assertEquals(-30.0, geometry.yMin)
        assertTrue(first.height > 0f && first.top < first.bottom)
        assertTrue(second.height > 0f && second.top < second.bottom)
        assertEquals(first.left, second.left)
        assertEquals(first.right, second.right)
        assertEquals(zero, first.top)
        assertEquals(first.bottom, second.top)
    }

    @Test
    fun measuredInsetsThatExceedCanvasKeepThePlotOnCanvas() {
        val geometry =
            visualizationGeometry(
                preview(ChartType.Bar),
                VisualizationConfig(chartType = ChartType.Bar),
                150f,
                200f,
                PlotInsets(left = 180f, right = 28f),
            )

        assertTrue(geometry.plot.left >= 0f)
        assertTrue(geometry.plot.right <= 150f)
        assertTrue(geometry.plot.width > 0f)
    }

    @Test
    fun plotRectPreservesMinimumPlotSizeAgainstOversizedTrailingInsets() {
        val wide = plotRect(400f, 300f, PlotInsets(left = 80f, right = 400f))
        val short = plotRect(400f, 70f, PlotInsets())

        assertTrue(wide.width >= 20f)
        assertTrue(wide.right <= 400f)
        assertTrue(short.height >= 30f)
        assertTrue(short.bottom <= 70f)
    }

    @Test
    fun oversizedRightInsetStillProducesBarRegionsAndValueRange() {
        val geometry =
            visualizationGeometry(
                preview(ChartType.Bar),
                VisualizationConfig(chartType = ChartType.Bar),
                150f,
                200f,
                PlotInsets(left = 72f, right = 180f),
            )

        assertTrue(geometry.regions.isNotEmpty())
        assertEquals(10.0, geometry.yMax)
    }

    @Test
    fun scatterGeometryOnTinyCanvasDoesNotThrowAndStaysInsidePlot() {
        val geometry =
            visualizationGeometry(
                preview(ChartType.Scatter),
                VisualizationConfig(chartType = ChartType.Scatter),
                400f,
                12f,
            )

        assertTrue(
            geometry.points.values.all { it.y >= geometry.plot.top && it.y <= geometry.plot.bottom }
        )
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
    fun scatterInteriorPointsSitOnTheValueTickScale() {
        val geometry =
            visualizationGeometry(
                preview(ChartType.Scatter),
                VisualizationConfig(chartType = ChartType.Scatter),
                600f,
                380f,
            )
        // Range is 0..10, so y = 5 is interior and must land exactly on the tick scale.
        val expected = (geometry.plot.bottom - 0.5 * geometry.plot.height).toFloat()

        assertEquals(expected, geometry.points.getValue("a-2").y)
        // y == yMax falls in the edge band and is only pulled in by the 8px pad.
        assertEquals(geometry.plot.top + 8f, geometry.points.getValue("a-1").y)
    }

    @Test
    fun scatterGeometryOnTinyWidthKeepsXInsidePlotAndUninverted() {
        val narrow =
            visualizationGeometry(
                preview(ChartType.Scatter),
                VisualizationConfig(chartType = ChartType.Scatter),
                12f,
                400f,
            )
        val tiny =
            visualizationGeometry(
                preview(ChartType.Scatter),
                VisualizationConfig(chartType = ChartType.Scatter),
                5f,
                5f,
            )

        assertTrue(
            narrow.points.values.all { it.x >= narrow.plot.left && it.x <= narrow.plot.right }
        )
        // xValue 4 (max) must not land left of xValue 1 (min).
        assertTrue(narrow.points.getValue("b-2").x >= narrow.points.getValue("a-1").x)
        assertTrue(
            tiny.points.values.all {
                it.x >= tiny.plot.left &&
                    it.x <= tiny.plot.right &&
                    it.y >= tiny.plot.top &&
                    it.y <= tiny.plot.bottom
            }
        )
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
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("5.0T", compactNumber(5e12))
            assertEquals("-2.5B", compactNumber(-2.5e9))
            assertEquals("1.5M", compactNumber(1.5e6))
            assertEquals("12", compactNumber(12.0))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun horizontalValueTicksUseBottomAxisBudgetNotLeftGutter() {
        val plot = Rect(44f, 24f, 400f, 200f)
        val gapPx = 12f

        assertEquals(plot.width / 4f, valueTickMaxWidth(true, plot, gapPx))
        assertEquals(32f, valueTickMaxWidth(false, plot, gapPx))
    }

    @Test
    fun categoryLabelIndicesMatchIndexSteppingOnEvenBands() {
        assertEquals(listOf(0, 1, 2), categoryLabelIndices(listOf(0f, 200f, 400f), 126f))
        assertEquals(listOf(0, 2), categoryLabelIndices(listOf(0f, 200f, 400f), 250f))
    }

    @Test
    fun clusteredScatterCentersKeepIsolatedLabelAndDropOverlappingOnes() {
        // Sample order was 0, 100, 1, 2; sorted centers cluster at the left. The cluster
        // must collapse to one label while the isolated center at 100 keeps its own.
        val selected = categoryLabelIndices(listOf(0f, 1f, 2f, 100f), 50f)

        assertEquals(listOf(0, 3), selected)
    }

    @Test
    fun categoryLabelStepSkipsEnoughWhenBandIsBelowOnePixel() {
        assertTrue(categoryLabelStep(labelExtent = 18f, band = 0.8f) >= 23)
        assertEquals(1, categoryLabelStep(labelExtent = 126f, band = 200f))
    }

    @Test
    fun categoryLabelClampStaysOutOfTheYTickColumn() {
        val x = clampLabelX(x = 0f, labelWidth = 40f, minX = 72f, canvasWidth = 400f)

        assertEquals(72f, x)
        assertTrue(x >= 72f)
    }

    @Test
    fun edgeClampedFirstLineLabelDoesNotOverlapItsNeighbour() {
        val plotLeft = 72f
        val slot = 200f
        val available = slot - 6f
        val firstWidth = categoryLabelMaxWidth(plotLeft, plotLeft, 500f, available)
        val firstLeft = clampLabelX(plotLeft - firstWidth / 2f, firstWidth, plotLeft, 500f)
        val secondCenter = plotLeft + slot

        assertTrue(firstLeft + firstWidth <= secondCenter - available / 2f)
        assertEquals(available, categoryLabelMaxWidth(secondCenter, plotLeft, 500f, available))
    }

    @Test
    fun tooltipAlignsToEndWhenPointerIsOnTheLeftHalf() {
        assertFalse(tooltipAlignsToStart(pointerX = 10f, plotCenterX = 100f))
        assertTrue(tooltipAlignsToStart(pointerX = 150f, plotCenterX = 100f))
    }

    @Test
    fun inBarLabelOmitsValuesThatDoNotFitRatherThanChangingUnits() {
        assertNull(inBarLabelOrNull("50%", formattedWidth = 40f, available = 20f))
        assertEquals("50%", inBarLabelOrNull("50%", formattedWidth = 20f, available = 40f))
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
