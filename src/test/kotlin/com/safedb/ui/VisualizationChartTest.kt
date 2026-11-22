package com.safedb.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.safedb.explore.BarArrangement
import com.safedb.explore.BarOrientation
import com.safedb.explore.ChartType
import com.safedb.explore.MeasureFn
import com.safedb.explore.NumberFormatKind
import com.safedb.explore.PivotGrouping
import com.safedb.explore.PivotNumberFormat
import com.safedb.explore.ValueAxis
import com.safedb.explore.VisualizationConfig
import com.safedb.explore.VisualizationField
import com.safedb.explore.VisualizationMark
import com.safedb.explore.VisualizationMeasure
import com.safedb.explore.VisualizationPreview
import com.safedb.explore.VisualizationSeries
import com.safedb.model.QueryResult
import com.safedb.model.ResultCell
import com.safedb.model.ResultColumn
import com.safedb.model.ThemePalette
import com.safedb.ui.chart.DarkBarLabelColor
import com.safedb.ui.chart.NiceValueAxis
import com.safedb.ui.chart.PlotInsets
import com.safedb.ui.chart.barValueLabelText
import com.safedb.ui.chart.categoryLabelIndices
import com.safedb.ui.chart.categoryLabelSpanMaxWidth
import com.safedb.ui.chart.categoryLabelStep
import com.safedb.ui.chart.clampLabelX
import com.safedb.ui.chart.compactNumber
import com.safedb.ui.chart.contrastingBarLabelColor
import com.safedb.ui.chart.inBarLabelOrNull
import com.safedb.ui.chart.legendSeriesLabel
import com.safedb.ui.chart.niceValueAxis
import com.safedb.ui.chart.plotRect
import com.safedb.ui.chart.toCssHex
import com.safedb.ui.chart.tooltipAlignsToStart
import com.safedb.ui.chart.valueTickMaxWidth
import com.safedb.ui.chart.visualizationGeometry
import com.safedb.ui.chart.visualizationValueAxis
import com.safedb.ui.theme.SafeDbTheme
import com.safedb.ui.theme.paletteSpecFor
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
    fun visualizationSidebarAddValueAddsSecondMeasureAndTemplatesOpensDialog() {
        val sample =
            QueryResult(
                columns =
                    listOf(
                        ResultColumn("t0__category", "varchar"),
                        ResultColumn("t0__amount", "decimal"),
                        ResultColumn("t0__qty", "integer"),
                    ),
                rows =
                    listOf(
                        listOf(
                            ResultCell.text("Services"),
                            ResultCell.float(100.0),
                            ResultCell.integer(2),
                        ),
                    ),
                rowCount = 1,
                truncated = false,
                warnings = emptyList(),
            )
        val fields = buildExploreFieldOptions(sample, emptyList())
        var config =
            VisualizationConfig(
                chartType = ChartType.Bar,
                x = VisualizationField("t0__category", "category"),
                values =
                    listOf(
                        VisualizationMeasure(
                            "sum_amount",
                            MeasureFn.Sum,
                            "t0__amount",
                            "Sum amount",
                        ),
                    ),
            )

        withComposeScene(
            width = 480,
            height = 900,
            content = {
                SafeDbTheme(isDark = false) {
                    VisualizationConfigPanel(
                        config = config,
                        sample = sample,
                        tables = emptyList(),
                        fields = fields,
                        memberOptionsFor = { emptyList() },
                        onConfigChange = { config = it },
                        onApplyTemplate = { config = it },
                        onReset = {},
                        onHide = {},
                    )
                }
            },
        ) { scene ->
            val addValueNode = scene.requireContaining("Add value")
            scene.click(addValueNode.boundsInRoot.center)
            scene.settle()

            // Value picker opens with "Count rows", not "Replace X field".
            val countRows = scene.requireContaining("Count rows")
            scene.click(countRows.boundsInRoot.center)
            scene.settle()

            assertEquals("t0__category", config.x?.column)
            assertEquals(2, config.values.size)
            assertEquals(MeasureFn.Count, config.values[1].fn)

            val templatesButton = scene.requireExact("Templates")
            scene.click(templatesButton.boundsInRoot.center)
            scene.settle()

            assertNotNull(scene.findContaining("Visualization templates"))
        }
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
    fun legendSeriesLabelNormalizeAndSidebarAxisToggleWorkTogether() {
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

        val single = listOf(VisualizationMeasure("b", MeasureFn.Count, axis = ValueAxis.Secondary))
        assertEquals(ValueAxis.Primary, normalizeMeasureAxes(single).single().axis)
        val allSecondary =
            listOf(
                VisualizationMeasure("a", MeasureFn.Count, axis = ValueAxis.Secondary),
                VisualizationMeasure("b", MeasureFn.Count, axis = ValueAxis.Secondary),
            )
        assertEquals(
            listOf(ValueAxis.Primary, ValueAxis.Secondary),
            normalizeMeasureAxes(allSecondary).map { it.axis },
        )

        val sample =
            QueryResult(
                columns =
                    listOf(
                        ResultColumn("t0__category", "varchar"),
                        ResultColumn("t0__amount", "decimal"),
                    ),
                rows = listOf(listOf(ResultCell.text("Services"), ResultCell.float(100.0))),
                rowCount = 1,
                truncated = false,
                warnings = emptyList(),
            )
        val fields = buildExploreFieldOptions(sample, emptyList())
        var config by
            mutableStateOf(
                VisualizationConfig(
                    chartType = ChartType.Bar,
                    x = VisualizationField("t0__category", "category"),
                    values =
                        listOf(
                            VisualizationMeasure(
                                "sum_amount",
                                MeasureFn.Sum,
                                "t0__amount",
                                "Sum amount",
                            ),
                            VisualizationMeasure("count", MeasureFn.Count, label = "Count"),
                        ),
                ),
            )
        withComposeScene(
            width = 480,
            height = 900,
            content = {
                SafeDbTheme(isDark = false) {
                    VisualizationConfigPanel(
                        config = config,
                        sample = sample,
                        tables = emptyList(),
                        fields = fields,
                        memberOptionsFor = { emptyList() },
                        resolvedChartType = ChartType.Bar,
                        onConfigChange = { config = it },
                        onApplyTemplate = { config = it },
                        onReset = {},
                        onHide = {},
                    )
                }
            },
        ) { scene ->
            // Toggle second measure to Right axis.
            val rightPills = scene.nodesLabeled("Right axis")
            assertEquals(2, rightPills.size)
            scene.click(rightPills[1].boundsInRoot.center)
            scene.settle()
            assertEquals(ValueAxis.Secondary, config.values[1].axis)

            // First measure is the only Primary left, so clicking its Right axis pill is a no-op.
            scene.click(scene.nodesLabeled("Right axis")[0].boundsInRoot.center)
            scene.settle()
            assertEquals(ValueAxis.Primary, config.values[0].axis)

            // Switching to Stacked shows the shared-axis note while preserving the stored axis.
            config = config.copy(barArrangement = BarArrangement.Stacked)
            scene.settle()
            assertNotNull(scene.findContaining("Stacked bars share one axis."))
            assertEquals(ValueAxis.Secondary, config.values[1].axis)

            // Switching to Horizontal shows Bottom/Top axis labels.
            config =
                config.copy(
                    barArrangement = BarArrangement.Grouped,
                    barOrientation = BarOrientation.Horizontal,
                )
            scene.settle()
            assertEquals(2, scene.nodesLabeled("Top axis").size)
            assertEquals(2, scene.nodesLabeled("Bottom axis").size)

            // Switching to KPI via forChartType resets axis to Primary.
            val kpiConfig = config.forChartType(ChartType.Kpi, fields)
            assertEquals(ValueAxis.Primary, kpiConfig.values.single().axis)
        }
    }

    @Test
    fun histogramTransitionPromotesNumericMeasureClearsNoticeAndHidesDeadOptions() {
        val sample =
            QueryResult(
                columns =
                    listOf(
                        ResultColumn("t0__category", "varchar"),
                        ResultColumn("t0__region", "varchar"),
                        ResultColumn("t0__amount", "decimal"),
                    ),
                rows =
                    listOf(
                        listOf(
                            ResultCell.text("Services"),
                            ResultCell.text("West"),
                            ResultCell.float(100.0),
                        ),
                    ),
                rowCount = 1,
                truncated = false,
                warnings = emptyList(),
            )
        val fields = buildExploreFieldOptions(sample, emptyList())
        val barWithSeries =
            VisualizationConfig(
                chartType = ChartType.Bar,
                x = VisualizationField("t0__category", "category"),
                values =
                    listOf(
                        VisualizationMeasure(
                            "sum_amount",
                            MeasureFn.Sum,
                            "t0__amount",
                            "Sum amount",
                        ),
                    ),
                series = VisualizationField("t0__region", "region"),
            )
        val promoted = barWithSeries.forChartType(ChartType.Histogram, fields)
        assertEquals("t0__amount", promoted.x?.column)
        assertEquals("amount", promoted.x?.label)
        assertTrue(promoted.values.isEmpty())
        assertNull(promoted.series)
        assertEquals(
            "Moved amount to X for histogram; removed incompatible series assignment.",
            chartTypeTransitionMessage(barWithSeries, promoted),
        )

        val barWithoutSeries = barWithSeries.copy(series = null)
        assertEquals(
            "Moved amount to X for histogram.",
            chartTypeTransitionMessage(
                barWithoutSeries,
                barWithoutSeries.forChartType(ChartType.Histogram, fields),
            ),
        )

        // Count-rows-only bar has no numeric measure to promote.
        val countOnlyBar = barWithoutSeries.copy(values = listOf(VisualizationMeasure.countRows()))
        val countOnlyHistogram = countOnlyBar.forChartType(ChartType.Histogram, fields)
        assertNull(countOnlyHistogram.x)
        assertEquals(
            "Removed incompatible X, values assignments.",
            chartTypeTransitionMessage(countOnlyBar, countOnlyHistogram),
        )

        // Options summary adapts to active chart type.
        assertEquals(
            "value sort · Top 20",
            visualizationOptionsSummary(barWithoutSeries, ChartType.Bar),
        )
        assertEquals("value sort", visualizationOptionsSummary(barWithoutSeries, ChartType.Line))
        assertEquals("Auto bins", visualizationOptionsSummary(promoted, ChartType.Histogram))
        assertEquals(
            "Bin size 250",
            visualizationOptionsSummary(
                promoted.copy(x = promoted.x?.copy(grouping = PivotGrouping.NumberBin(" 250 "))),
                ChartType.Histogram,
            ),
        )
        listOf("0", "-10", "abc").forEach { invalid ->
            assertEquals(
                "Auto bins",
                visualizationOptionsSummary(
                    promoted.copy(
                        x = promoted.x?.copy(grouping = PivotGrouping.NumberBin(invalid)),
                    ),
                    ChartType.Histogram,
                ),
            )
        }
        assertEquals(
            "Format & title",
            visualizationOptionsSummary(barWithoutSeries, ChartType.Scatter),
        )
        assertEquals("Title & display", visualizationOptionsSummary(VisualizationConfig(), null))

        var config by mutableStateOf(barWithoutSeries)
        // Keep resolvedChartType pinned to stale ChartType.Bar to verify explicit chartType
        // switches update Options immediately before debounced preview recomputation finishes.
        withComposeScene(
            width = 480,
            height = 1100,
            content = {
                SafeDbTheme(isDark = false) {
                    VisualizationConfigPanel(
                        config = config,
                        sample = sample,
                        tables = emptyList(),
                        fields = fields,
                        memberOptionsFor = { emptyList() },
                        resolvedChartType = ChartType.Bar,
                        onConfigChange = { config = it },
                        onApplyTemplate = { config = it },
                        onReset = { config = VisualizationConfig() },
                        onHide = {},
                    )
                }
            },
        ) { scene ->
            // Switch Bar -> Histogram: promotes amount to X and shows the transition note.
            scene.click(scene.requireExact("Histogram").boundsInRoot.center)
            scene.settle()
            assertEquals("t0__amount", config.x?.column)
            assertNotNull(scene.findContaining("Moved amount to X for histogram."))
            assertNotNull(scene.findContaining("Auto bins"))

            // Expand Options: Histogram hides Sort and Top categories, shows Bin size and value
            // labels even while preview resolvedChartType is still stale Bar.
            scene.click(scene.requireContaining("Options").boundsInRoot.center)
            scene.settle()
            val binSizeField = scene.requireContaining("Bin size (blank = automatic)")
            assertNotNull(scene.findContaining("Show value labels"))
            assertNull(scene.findExact("Sort"))
            assertNull(scene.findExact("Top categories"))

            // Editing Bin size preserves any existing start and clearing it with no start resets
            // grouping to None instead of leaving an invalid NumberBin("").
            config =
                config.copy(
                    x = config.x?.copy(grouping = PivotGrouping.NumberBin("250", start = "17")),
                )
            scene.settle()
            val setText =
                binSizeField.config[androidx.compose.ui.semantics.SemanticsActions.SetText].action!!
            setText.invoke(androidx.compose.ui.text.AnnotatedString("500"))
            scene.settle()
            assertEquals(PivotGrouping.NumberBin("500", start = "17"), config.x?.grouping)

            config = config.copy(x = config.x?.copy(grouping = PivotGrouping.NumberBin("500")))
            scene.settle()
            setText.invoke(androidx.compose.ui.text.AnnotatedString(""))
            scene.settle()
            assertEquals(PivotGrouping.Exact, config.x?.grouping)

            // Re-trigger transition note via Bar -> Histogram, then toggle Show value labels to
            // verify it clears immediately and reverting does not resurrect it.
            config = barWithoutSeries
            scene.settle()
            scene.click(scene.requireExact("Histogram").boundsInRoot.center)
            scene.settle()
            assertNotNull(scene.findContaining("Moved amount to X for histogram."))
            scene.click(scene.requireContaining("Show value labels").boundsInRoot.center)
            scene.settle()
            assertNull(scene.findContaining("Moved amount to X for histogram."))
            scene.click(scene.requireContaining("Show value labels").boundsInRoot.center)
            scene.settle()
            assertNull(scene.findContaining("Moved amount to X for histogram."))

            // Switch to Line: shows Sort, hides Top categories and Show value labels.
            scene.click(scene.requireExact("Line").boundsInRoot.center)
            scene.settle()
            assertNotNull(scene.findExact("Sort"))
            assertNull(scene.findExact("Top categories"))
            assertNull(scene.findContaining("Show value labels"))
            assertNotNull(scene.findContaining("value sort"))
        }
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
