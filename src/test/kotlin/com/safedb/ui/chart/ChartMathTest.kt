package com.safedb.ui.chart

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.safedb.explore.BarArrangement
import com.safedb.explore.BarOrientation
import com.safedb.explore.ChartType
import com.safedb.explore.MeasureFn
import com.safedb.explore.NumberFormatKind
import com.safedb.explore.PivotNumberFormat
import com.safedb.explore.ValueAxis
import com.safedb.explore.VisualizationConfig
import com.safedb.explore.VisualizationMark
import com.safedb.explore.VisualizationMeasure
import com.safedb.explore.VisualizationPreview
import com.safedb.explore.VisualizationSeries
import com.safedb.model.ThemePalette
import com.safedb.ui.theme.paletteSpecFor
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChartMathTest {
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
                (geometry.valueAxis.fraction(0.0) * geometry.plot.height).toFloat()

        assertEquals(-30.0, visualizationValueAxis(preview, config).min)
        assertEquals(-30.0, geometry.valueAxis.min)
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
        assertEquals(10.0, geometry.valueAxis.max)
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
            geometry.points.values.all {
                it.y >= geometry.plot.top && it.y <= geometry.plot.bottom
            },
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
            geometry.plot.left + (geometry.valueAxis.fraction(0.0) * geometry.plot.width).toFloat()
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
            narrow.points.values.all { it.x >= narrow.plot.left && it.x <= narrow.plot.right },
        )
        // xValue 4 (max) must not land left of xValue 1 (min).
        assertTrue(narrow.points.getValue("b-2").x >= narrow.points.getValue("a-1").x)
        assertTrue(
            tiny.points.values.all {
                it.x >= tiny.plot.left &&
                    it.x <= tiny.plot.right &&
                    it.y >= tiny.plot.top &&
                    it.y <= tiny.plot.bottom
            },
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
            },
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
            assertEquals("5T", compactNumber(5e12))
            assertEquals("-2.5B", compactNumber(-2.5e9))
            assertEquals("1M", compactNumber(1e6))
            assertEquals("1.5M", compactNumber(1.5e6))
            assertEquals("200K", compactNumber(200_000.0))
            assertEquals("12", compactNumber(12.0))
            assertEquals("0", compactNumber(-0.0))
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
    fun niceValueAxisFractionAndColorCssHexHandleBoundaryInputs() {
        val axis = NiceValueAxis(min = -10.0, max = 30.0, ticks = listOf(-10.0, 10.0, 30.0))
        assertEquals(0.25, axis.fraction(0.0), 1e-9)
        assertEquals(0.5, axis.fraction(10.0), 1e-9)

        val zeroSpan = NiceValueAxis(min = 5.0, max = 5.0, ticks = listOf(5.0))
        assertEquals(2.0, zeroSpan.fraction(7.0), 1e-9)

        assertEquals("#ffffff", Color.White.toCssHex())
        assertEquals("#0f172a", DarkBarLabelColor.toCssHex())
    }

    @Test
    fun tooltipAlignsToEndWhenPointerIsOnTheLeftHalf() {
        assertFalse(tooltipAlignsToStart(pointerX = 10f, plotCenterX = 100f))
        assertTrue(tooltipAlignsToStart(pointerX = 150f, plotCenterX = 100f))
    }

    @Test
    fun niceValueAxisProducesCleanIntervalsAcrossReportRanges() {
        val reportA = niceValueAxis(0.0, 3_624_326.7)
        assertEquals(listOf(0.0, 1_000_000.0, 2_000_000.0, 3_000_000.0, 4_000_000.0), reportA.ticks)

        val reportB = niceValueAxis(0.0, 327_777.59)
        assertEquals(listOf(0.0, 100_000.0, 200_000.0, 300_000.0, 400_000.0), reportB.ticks)

        val reportC = niceValueAxis(0.0, 533_532.96)
        assertEquals(listOf(0.0, 200_000.0, 400_000.0, 600_000.0), reportC.ticks)

        val flat = niceValueAxis(5.0, 5.0)
        assertTrue(flat.max > flat.min)
        assertTrue(flat.ticks.size >= 2)

        val largeFlat = niceValueAxis(1e18, 1e18)
        assertTrue(largeFlat.max > largeFlat.min)
        assertTrue(largeFlat.ticks.all(Double::isFinite))

        val nonFinite = niceValueAxis(Double.NaN, Double.POSITIVE_INFINITY)
        assertEquals(listOf(0.0, 0.25, 0.5, 0.75, 1.0), nonFinite.ticks)

        val overflowSpan = niceValueAxis(-1e308, 1e308)
        assertTrue(overflowSpan.max > overflowSpan.min)
        assertTrue(overflowSpan.ticks.all(Double::isFinite))

        val extremeMax = niceValueAxis(0.0, 1.7e308)
        assertTrue(extremeMax.max > extremeMax.min)
        assertTrue(extremeMax.ticks.all(Double::isFinite))

        val extremeSymmetric = niceValueAxis(-8e307, 8e307)
        assertTrue(extremeSymmetric.max > extremeSymmetric.min)
        assertTrue(extremeSymmetric.ticks.all(Double::isFinite))
    }

    @Test
    fun contrastingBarLabelColorMeetsWcagAaAcrossAllThemePalettes() {
        for (palette in ThemePalette.entries) {
            for (isDark in listOf(false, true)) {
                val series = paletteSpecFor(palette, isDark).series
                series.forEach { barColor ->
                    val labelColor = contrastingBarLabelColor(barColor)
                    val l1 = barColor.luminance()
                    val l2 = labelColor.luminance()
                    val contrast = (max(l1, l2) + 0.05f) / (min(l1, l2) + 0.05f)
                    assertTrue(
                        contrast >= 4.5f,
                        "Expected >= 4.5 contrast for $palette (dark=$isDark) bar=$barColor label=$labelColor, got $contrast",
                    )
                }
            }
        }
    }

    @Test
    fun barValueLabelTextUsesCompactForAutoAndFormattedForExplicitFormat() {
        val autoMeasure =
            VisualizationMeasure(
                alias = "sum_total",
                fn = MeasureFn.Sum,
                sourceColumn = "total",
                numberFormat = PivotNumberFormat(NumberFormatKind.Auto),
            )
        val currencyMeasure =
            autoMeasure.copy(
                alias = "curr_total",
                numberFormat = PivotNumberFormat(NumberFormatKind.Currency, decimals = 0),
            )
        val autoMark =
            mark("1", "a", "A", "one", 3_624_326.7, 1.0)
                .copy(measureAlias = "sum_total", formattedY = "3,624,326.7")
        val currencyMark =
            mark("2", "a", "A", "one", 3_624_326.7, 1.0)
                .copy(measureAlias = "curr_total", formattedY = "$3,624,327")

        assertEquals(
            "3.6M",
            barValueLabelText(autoMark, VisualizationConfig(values = listOf(autoMeasure))),
        )
        assertEquals(
            "$3,624,327",
            barValueLabelText(currencyMark, VisualizationConfig(values = listOf(currencyMeasure))),
        )
    }

    @Test
    fun lineChartCentersPointsInBandsSoFirstLabelStaysInsidePlot() {
        val preview = preview(ChartType.Line)
        val geometry =
            visualizationGeometry(
                preview,
                VisualizationConfig(chartType = ChartType.Line),
                900f,
                500f,
            )
        val firstX = geometry.points.getValue("a-1").x
        val lastX = geometry.points.getValue("b-1").x
        val band = geometry.plot.width / 2f

        assertEquals(geometry.plot.left + band * 0.5f, firstX)
        assertEquals(geometry.plot.left + band * 1.5f, lastX)
    }

    @Test
    fun dualAxisGeometryScalesPrimaryAndSecondaryIndependentlyAcrossBarAndLine() {
        val preview =
            VisualizationPreview(
                chartType = ChartType.Bar,
                title = "Revenue vs Distinct",
                marks =
                    listOf(
                        mark("a-rev", "a", "Services", "rev", 3_624_326.7, 1.0, ValueAxis.Primary),
                        mark("a-cat", "a", "Services", "cat", 1.0, 1.0, ValueAxis.Secondary),
                    ),
                series =
                    listOf(
                        VisualizationSeries("rev", "Sum line_total", ValueAxis.Primary),
                        VisualizationSeries("cat", "Distinct category", ValueAxis.Secondary),
                    ),
            )
        val verticalConfig = VisualizationConfig(chartType = ChartType.Bar)
        val vertical = visualizationGeometry(preview, verticalConfig, 900f, 500f)
        val revBar = vertical.regions.first { it.markId == "a-rev" }.bounds
        val catBar = vertical.regions.first { it.markId == "a-cat" }.bounds
        assertEquals(4_000_000.0, vertical.valueAxis.max)
        assertEquals(1.0, vertical.secondaryValueAxis?.max)
        assertTrue(revBar.height > vertical.plot.height * 0.85f)
        assertEquals(vertical.plot.height, catBar.height, 1e-3f)

        val horizontalConfig = verticalConfig.copy(barOrientation = BarOrientation.Horizontal)
        val horizontal = visualizationGeometry(preview, horizontalConfig, 900f, 500f)
        val horizCatBar = horizontal.regions.first { it.markId == "a-cat" }.bounds
        assertEquals(horizontal.plot.width, horizCatBar.width, 1e-3f)

        val linePreview = preview.copy(chartType = ChartType.Line)
        val line =
            visualizationGeometry(
                linePreview,
                VisualizationConfig(chartType = ChartType.Line),
                900f,
                500f,
            )
        assertEquals(line.plot.top, line.points.getValue("a-cat").y, 1e-3f)
        assertTrue(line.points.getValue("a-rev").y > line.plot.top)

        // Negative values on secondary anchor to secondary's own zero line.
        val signedPreview =
            VisualizationPreview(
                chartType = ChartType.Bar,
                title = "Signed",
                marks =
                    listOf(
                        mark("a-pos", "a", "A", "pos", 1_000_000.0, 1.0, ValueAxis.Primary),
                        mark("a-neg", "a", "A", "neg", -10.0, 1.0, ValueAxis.Secondary),
                    ),
                series =
                    listOf(
                        VisualizationSeries("pos", "Pos", ValueAxis.Primary),
                        VisualizationSeries("neg", "Neg", ValueAxis.Secondary),
                    ),
            )
        val signed = visualizationGeometry(signedPreview, verticalConfig, 800f, 500f)
        assertEquals(0.0, signed.valueAxis.min)
        assertEquals(-10.0, signed.secondaryValueAxis?.min)
        assertEquals(0.0, signed.secondaryValueAxis?.max)
        val negBounds = signed.regions.first { it.markId == "a-neg" }.bounds
        assertEquals(signed.plot.top, negBounds.top, 1e-3f)
        assertEquals(signed.plot.bottom, negBounds.bottom, 1e-3f)
    }

    @Test
    fun barPlacementKeepsDistinctInsetsForGroupedAndStackedOrientations() {
        val preview =
            VisualizationPreview(
                chartType = ChartType.Bar,
                title = "Revenue vs Distinct",
                marks =
                    listOf(
                        mark("a-rev", "a", "Services", "rev", 3_624_326.7, 1.0, ValueAxis.Primary),
                        mark("a-cat", "a", "Services", "cat", 1.0, 1.0, ValueAxis.Secondary),
                    ),
                series =
                    listOf(
                        VisualizationSeries("rev", "Sum line_total", ValueAxis.Primary),
                        VisualizationSeries("cat", "Distinct category", ValueAxis.Secondary),
                    ),
            )
        val markCount = preview.marks.count { it.xKey == "a" }
        val categoryCount = preview.marks.map { it.xKey }.distinct().size
        val grouped = VisualizationConfig(chartType = ChartType.Bar)
        val vertical = visualizationGeometry(preview, grouped, 900f, 500f)
        val verticalBand = vertical.plot.width / categoryCount
        val verticalBar = vertical.regions.first { it.markId == "a-rev" }.bounds
        assertEquals(vertical.plot.left + verticalBand * 0.14f, verticalBar.left, 1e-3f)
        assertEquals((verticalBand * 0.72f / markCount) * 0.86f, verticalBar.width, 1e-3f)

        val horizontal =
            visualizationGeometry(
                preview,
                grouped.copy(barOrientation = BarOrientation.Horizontal),
                900f,
                500f,
            )
        val horizontalBand = horizontal.plot.height / categoryCount
        val horizontalBar = horizontal.regions.first { it.markId == "a-rev" }.bounds
        assertEquals(horizontal.plot.top + horizontalBand * 0.15f, horizontalBar.top, 1e-3f)
        assertEquals((horizontalBand * 0.7f / markCount) * 0.82f, horizontalBar.height, 1e-3f)

        val stacked = grouped.copy(barArrangement = BarArrangement.Stacked)
        val verticalStacked = visualizationGeometry(preview, stacked, 900f, 500f)
        val stackedBand = verticalStacked.plot.width / categoryCount
        val stackedBar = verticalStacked.regions.first { it.markId == "a-rev" }.bounds
        assertEquals(verticalStacked.plot.left + stackedBand * 0.16f, stackedBar.left, 1e-3f)
        assertEquals(
            verticalStacked.plot.left + stackedBand - stackedBand * 0.16f,
            stackedBar.right,
            1e-3f,
        )

        val horizontalStacked =
            visualizationGeometry(
                preview,
                stacked.copy(barOrientation = BarOrientation.Horizontal),
                900f,
                500f,
            )
        val horizontalStackedBand = horizontalStacked.plot.height / categoryCount
        val horizontalStackedBar = horizontalStacked.regions.first { it.markId == "a-rev" }.bounds
        assertEquals(
            horizontalStacked.plot.top + horizontalStackedBand * 0.15f,
            horizontalStackedBar.top,
            1e-3f,
        )
        assertEquals(horizontalStackedBand * 0.7f, horizontalStackedBar.height, 1e-3f)
    }

    @Test
    fun legendSeriesLabelIncludesAxisSideOnlyWhenDualAxisIsActive() {
        val primary = VisualizationSeries("a", "Sum revenue", ValueAxis.Primary)
        val secondary = VisualizationSeries("b", "Count", ValueAxis.Secondary)

        assertEquals("Sum revenue", legendSeriesLabel(primary, hasSecondaryAxis = false, false))
        assertEquals(
            "Sum revenue (left)",
            legendSeriesLabel(primary, hasSecondaryAxis = true, false),
        )
        assertEquals("Count (right)", legendSeriesLabel(secondary, hasSecondaryAxis = true, false))
        assertEquals(
            "Sum revenue (bottom)",
            legendSeriesLabel(primary, hasSecondaryAxis = true, true),
        )
        assertEquals("Count (top)", legendSeriesLabel(secondary, hasSecondaryAxis = true, true))
    }

    @Test
    fun denseHistogramEdgeLabelsFitUntruncatedWithoutOverlappingNeighbors() {
        val plotLeft = 72f
        val canvasWidth = 900f
        val gapPx = 6f
        val centers = List(20) { index -> plotLeft + 20f + index * 40f }
        val requiredGap = 78f

        val selected = categoryLabelIndices(centers, requiredGap, minX = plotLeft)
        // Center 0 is at 92; clamped effective center is 72 + 39 = 111, so index 2 (172) is skipped
        // and index 3 (212) is the next selected label.
        assertEquals(0, selected[0])
        assertEquals(3, selected[1])

        val measuredFirst = 52f
        val measuredSecond = 62f
        val secondLeft = centers[selected[1]] - measuredSecond / 2f
        val firstMaxWidth =
            categoryLabelSpanMaxWidth(
                center = centers[selected[0]],
                minX = plotLeft,
                canvasWidth = canvasWidth,
                available = centers[selected[1]] - centers[selected[0]] - gapPx,
                leftLimit = plotLeft,
                rightLimit = secondLeft - gapPx,
                isFirst = true,
                isLast = false,
            )
        assertTrue(
            firstMaxWidth >= measuredFirst,
            "Expected first histogram label ($measuredFirst px) to fit inside $firstMaxWidth px",
        )
        val firstLeft =
            clampLabelX(
                centers[selected[0]] - measuredFirst / 2f,
                measuredFirst,
                plotLeft,
                canvasWidth,
            )
        assertTrue(firstLeft + measuredFirst + gapPx <= secondLeft)

        // Single label spans the full available width from minX to canvasWidth.
        assertEquals(
            canvasWidth - plotLeft,
            categoryLabelSpanMaxWidth(
                center = 100f,
                minX = plotLeft,
                canvasWidth = canvasWidth,
                available = 200f,
                leftLimit = plotLeft,
                rightLimit = canvasWidth,
                isFirst = true,
                isLast = true,
            ),
        )

        // Right-edge clamped last label stays clear of previous label.
        val lastCenter = 885f
        val prevRight = 760f
        val lastMax =
            categoryLabelSpanMaxWidth(
                center = lastCenter,
                minX = plotLeft,
                canvasWidth = canvasWidth,
                available = 120f,
                leftLimit = prevRight + gapPx,
                rightLimit = canvasWidth,
                isFirst = false,
                isLast = true,
            )
        val lastWidth = min(80f, lastMax)
        val lastLeft = clampLabelX(lastCenter - lastWidth / 2f, lastWidth, plotLeft, canvasWidth)
        assertTrue(lastLeft >= prevRight + gapPx)
    }

    @Test
    fun layoutHorizontalCategoryLabelsSortsDecimatesAndPreventsNeighborOverlap() {
        val plotLeft = 72f
        val plotWidth = 800f
        val canvasWidth = 900f
        val gapPx = 6f

        assertTrue(
            layoutHorizontalCategoryLabels(
                    entries = emptyList(),
                    plotLeft = plotLeft,
                    plotWidth = plotWidth,
                    canvasWidth = canvasWidth,
                    gapPx = gapPx,
                    measureFitted = { _, w -> w },
                    measuredWidth = { it },
                )
                .isEmpty(),
        )

        // Unsorted scatter entries: cluster near plotLeft collapses to one label while isolated
        // right-edge point stays labeled and clamped inside canvasWidth.
        val scatterEntries =
            listOf(
                CategoryLabelEntry("far", "900", center = 885f, naturalWidth = 72f),
                CategoryLabelEntry("c1", "10", center = 92f, naturalWidth = 52f),
                CategoryLabelEntry("c2", "11", center = 96f, naturalWidth = 52f),
                CategoryLabelEntry("mid", "400", center = 480f, naturalWidth = 60f),
            )
        val naturalWidths = scatterEntries.associate { it.text to it.naturalWidth }
        val scatterPlaced =
            layoutHorizontalCategoryLabels(
                entries = scatterEntries,
                plotLeft = plotLeft,
                plotWidth = plotWidth,
                canvasWidth = canvasWidth,
                gapPx = gapPx,
                measureFitted = { text, maxWidth -> min(naturalWidths.getValue(text), maxWidth) },
                measuredWidth = { it },
            )
        assertEquals(listOf("c1", "mid", "far"), scatterPlaced.map { it.entry.xKey })
        assertTrue(scatterPlaced.first().drawX >= plotLeft)
        assertTrue(scatterPlaced.last().drawX + scatterPlaced.last().measured <= canvasWidth)
        scatterPlaced.zipWithNext().forEach { (left, right) ->
            assertTrue(
                left.drawX + left.measured + gapPx <= right.drawX,
                "Expected ${left.entry.xKey} right edge to stay clear of ${right.entry.xKey}",
            )
        }

        // Single entry gets the full canvas span from plotLeft to canvasWidth.
        val singlePlaced =
            layoutHorizontalCategoryLabels(
                entries =
                    listOf(CategoryLabelEntry("only", "Only", center = 100f, naturalWidth = 140f)),
                plotLeft = plotLeft,
                plotWidth = plotWidth,
                canvasWidth = canvasWidth,
                gapPx = gapPx,
                measureFitted = { _, maxWidth -> min(140f, maxWidth) },
                measuredWidth = { it },
            )
        assertEquals(1, singlePlaced.size)
        assertEquals(canvasWidth - plotLeft, singlePlaced.single().maxWidth)

        // Dense 20-bin histogram: every placed label fits inside [plotLeft, canvasWidth] with at
        // least gapPx clearance between consecutive fitted labels.
        val histogramEntries =
            List(20) { index ->
                val text = "${index * 50} – ${(index + 1) * 50}"
                val natural = if (index == 0) 52f else 72f
                CategoryLabelEntry(
                    xKey = "bin-$index",
                    text = text,
                    center = plotLeft + 20f + index * 40f,
                    naturalWidth = natural,
                )
            }
        val histWidths = histogramEntries.associate { it.text to it.naturalWidth }
        val histPlaced =
            layoutHorizontalCategoryLabels(
                entries = histogramEntries,
                plotLeft = plotLeft,
                plotWidth = plotWidth,
                canvasWidth = canvasWidth,
                gapPx = gapPx,
                measureFitted = { text, maxWidth -> min(histWidths.getValue(text), maxWidth) },
                measuredWidth = { it },
            )
        assertEquals("bin-0", histPlaced[0].entry.xKey)
        assertEquals("bin-3", histPlaced[1].entry.xKey)
        assertEquals(52f, histPlaced[0].measured)
        histPlaced.forEach { placed ->
            assertTrue(placed.drawX >= plotLeft)
            assertTrue(placed.drawX + placed.measured <= canvasWidth)
        }
        histPlaced.zipWithNext().forEach { (left, right) ->
            assertTrue(left.drawX + left.measured + gapPx <= right.drawX)
        }
    }

    @Test
    fun chartPlotInsetsForCoversHorizontalVerticalSecondaryAndClearance() {
        val horizontal =
            chartPlotInsetsFor(
                horizontal = true,
                hasSecondaryAxis = false,
                categoryExtent = 20f,
                valueTickWidth = 80f,
                secondaryTickWidth = 0f,
                lastCategoryWidth = 0f,
                lineHeight = 12f,
                gapPx = 6f,
                minGutter = 44f,
                maxGutter = 180f,
                clearance = PlotClearance(),
            )
        assertEquals(46f, horizontal.left)
        assertEquals(46f, horizontal.right)
        assertEquals(12f, horizontal.top)
        assertEquals(24f, horizontal.bottom)

        val clampedLeft =
            chartPlotInsetsFor(
                horizontal = true,
                hasSecondaryAxis = false,
                categoryExtent = 200f,
                valueTickWidth = 80f,
                secondaryTickWidth = 0f,
                lastCategoryWidth = 0f,
                lineHeight = 12f,
                gapPx = 6f,
                minGutter = 44f,
                maxGutter = 180f,
                clearance = PlotClearance(),
            )
        assertEquals(180f, clampedLeft.left)

        val wideRight =
            chartPlotInsetsFor(
                horizontal = true,
                hasSecondaryAxis = false,
                categoryExtent = 20f,
                valueTickWidth = 100f,
                secondaryTickWidth = 0f,
                lastCategoryWidth = 0f,
                lineHeight = 12f,
                gapPx = 6f,
                minGutter = 44f,
                maxGutter = 180f,
                clearance = PlotClearance(),
            )
        assertEquals(56f, wideRight.right)

        val veryWideRight =
            chartPlotInsetsFor(
                horizontal = true,
                hasSecondaryAxis = false,
                categoryExtent = 20f,
                valueTickWidth = 1000f,
                secondaryTickWidth = 0f,
                lastCategoryWidth = 0f,
                lineHeight = 12f,
                gapPx = 6f,
                minGutter = 44f,
                maxGutter = 180f,
                clearance = PlotClearance(),
            )
        assertEquals(506f, veryWideRight.right)

        val verticalSecondary =
            chartPlotInsetsFor(
                horizontal = false,
                hasSecondaryAxis = true,
                categoryExtent = 0f,
                valueTickWidth = 30f,
                secondaryTickWidth = 40f,
                lastCategoryWidth = 10f,
                lineHeight = 12f,
                gapPx = 6f,
                minGutter = 44f,
                maxGutter = 180f,
                clearance = PlotClearance(),
            )
        assertEquals(44f, verticalSecondary.left)
        assertEquals(52f, verticalSecondary.right)

        val topCleared =
            chartPlotInsetsFor(
                horizontal = false,
                hasSecondaryAxis = false,
                categoryExtent = 0f,
                valueTickWidth = 30f,
                secondaryTickWidth = 0f,
                lastCategoryWidth = 10f,
                lineHeight = 12f,
                gapPx = 6f,
                minGutter = 44f,
                maxGutter = 180f,
                clearance = PlotClearance(top = 20f),
            )
        assertEquals(20f, topCleared.top)

        val bottomCleared =
            chartPlotInsetsFor(
                horizontal = true,
                hasSecondaryAxis = false,
                categoryExtent = 20f,
                valueTickWidth = 80f,
                secondaryTickWidth = 0f,
                lastCategoryWidth = 0f,
                lineHeight = 12f,
                gapPx = 6f,
                minGutter = 44f,
                maxGutter = 180f,
                clearance = PlotClearance(bottom = 22f),
            )
        assertEquals(46f, bottomCleared.left)
        assertEquals(12f, bottomCleared.top)
        assertEquals(46f, bottomCleared.right)
        assertEquals(24f, bottomCleared.bottom)

        val raisedBottomCleared =
            chartPlotInsetsFor(
                horizontal = true,
                hasSecondaryAxis = false,
                categoryExtent = 20f,
                valueTickWidth = 80f,
                secondaryTickWidth = 0f,
                lastCategoryWidth = 0f,
                lineHeight = 6f,
                gapPx = 6f,
                minGutter = 44f,
                maxGutter = 180f,
                clearance = PlotClearance(bottom = 22f),
            )
        assertEquals(46f, raisedBottomCleared.left)
        assertEquals(9f, raisedBottomCleared.top)
        assertEquals(46f, raisedBottomCleared.right)
        assertEquals(22f, raisedBottomCleared.bottom)
    }

    @Test
    fun chartPlotInsetsPreviewSelectsScatterMaxXOrLastBarCategoryForRightEdge() {
        val marks =
            listOf(
                mark("m1", "k1", "LongMaxXLabel", "one", 10.0, 100.0),
                mark("m2", "k2", "Short", "one", 5.0, 10.0),
            )
        val measure = { text: String -> text.length * 10f }

        val scatterInsets =
            chartPlotInsets(
                preview = VisualizationPreview(ChartType.Scatter, "Scatter", marks),
                config = VisualizationConfig(chartType = ChartType.Scatter),
                lineHeight = 12f,
                gapPx = 6f,
                measureLabelWidth = measure,
            )
        // "LongMaxXLabel" (13 chars * 10) / 2 + 6 = 71
        assertEquals(71f, scatterInsets.right)

        val barInsets =
            chartPlotInsets(
                preview = VisualizationPreview(ChartType.Bar, "Bar", marks),
                config = VisualizationConfig(chartType = ChartType.Bar),
                lineHeight = 12f,
                gapPx = 6f,
                measureLabelWidth = measure,
            )
        // "Short" (5 chars * 10) / 2 + 6 = 31
        assertEquals(31f, barInsets.right)
    }

    @Test
    fun barLabelCenterAndEstimatesEnforcePaddingAndOrientationAnchors() {
        assertEquals(18.6f, estimatedBarLabelWidth("ABC"), 0.001f)
        assertEquals(10f, estimatedBarLabelHeight())

        // Height gate: labelHeight <= bounds.height - 2 (11f rejects 10f, 12f accepts 10f).
        assertNull(barLabelCenter(Rect(0f, 0f, 100f, 11f), 50f, 10f, horizontalBars = false))
        assertEquals(
            Offset(50f, 7f),
            barLabelCenter(Rect(0f, 0f, 100f, 12f), 50f, 10f, horizontalBars = false),
        )

        // Width gate: labelWidth <= bounds.width - 4 (97f rejected for width 100f).
        assertNull(barLabelCenter(Rect(0f, 0f, 100f, 20f), 97f, 10f, horizontalBars = false))

        // Non-finite inputs are rejected.
        val box = Rect(0f, 0f, 100f, 20f)
        assertNull(barLabelCenter(box, Float.NaN, 10f, horizontalBars = false))
        assertNull(barLabelCenter(box, 10f, Float.NaN, horizontalBars = false))
        assertNull(barLabelCenter(box, Float.POSITIVE_INFINITY, 10f, horizontalBars = false))
        assertNull(barLabelCenter(box, 10f, Float.NEGATIVE_INFINITY, horizontalBars = false))

        // Vertical anchor sits at top + 2 + height / 2; horizontal anchor is bounds.center.
        assertEquals(
            Offset(50f, 7f),
            barLabelCenter(box, 50f, 10f, horizontalBars = false),
        )
        assertEquals(
            box.center,
            barLabelCenter(box, 50f, 10f, horizontalBars = true),
        )
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

    private fun mark(
        id: String,
        key: String,
        label: String,
        series: String,
        y: Double,
        x: Double,
        axis: ValueAxis = ValueAxis.Primary,
    ) =
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
            axis = axis,
            sourceRowIndices = listOf(0),
        )
}
