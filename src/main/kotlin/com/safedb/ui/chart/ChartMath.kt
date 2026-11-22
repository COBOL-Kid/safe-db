package com.safedb.ui.chart

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.safedb.explore.BarOrientation
import com.safedb.explore.ChartType
import com.safedb.explore.NumberFormatKind
import com.safedb.explore.ValueAxis
import com.safedb.explore.VisualizationConfig
import com.safedb.explore.VisualizationMark
import com.safedb.explore.VisualizationPreview
import com.safedb.explore.VisualizationSeries
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

internal data class VisualizationHitRegion(val markId: String, val bounds: Rect) {
    fun contains(offset: Offset): Boolean = bounds.contains(offset)
}

internal data class NiceValueAxis(val min: Double, val max: Double, val ticks: List<Double>) {
    fun fraction(value: Double): Double {
        val span = (max - min).takeIf { it > 0.0 } ?: 1.0
        return (value - min) / span
    }
}

private val DefaultValueAxis = NiceValueAxis(0.0, 1.0, listOf(0.0, 0.25, 0.5, 0.75, 1.0))

internal data class VisualizationGeometry(
    val regions: List<VisualizationHitRegion>,
    val points: Map<String, Offset>,
    val plot: Rect,
    val valueAxis: NiceValueAxis = DefaultValueAxis,
    // xKey -> center along the category axis: x for vertical bars/line/scatter, y for horizontal
    // bars.
    val categoryCenters: Map<String, Float> = emptyMap(),
    val secondaryValueAxis: NiceValueAxis? = null,
)

internal data class PlotInsets(
    val left: Float = 72f,
    val top: Float = 24f,
    val right: Float = 28f,
    val bottom: Float = 58f,
)

internal fun niceValueAxis(
    rawMin: Double,
    rawMax: Double,
    targetIntervals: Int = 4,
): NiceValueAxis {
    if (!rawMin.isFinite() || !rawMax.isFinite()) {
        return DefaultValueAxis
    }
    val hi = if (rawMax <= rawMin) rawMin + max(1.0, abs(rawMin) * 1e-6) else rawMax
    val roughStep = (hi - rawMin) / targetIntervals.coerceAtLeast(1)
    if (!roughStep.isFinite() || roughStep <= 0.0) {
        return DefaultValueAxis
    }
    val magnitude = 10.0.pow(floor(log10(roughStep)))
    val fraction = roughStep / magnitude
    val niceFraction =
        when {
            fraction <= 1.0 -> 1.0
            fraction <= 2.0 -> 2.0
            fraction <= 2.5 -> 2.5
            fraction <= 5.0 -> 5.0
            else -> 10.0
        }
    val step = niceFraction * magnitude
    val eps = 1e-9
    val minTick = floor(rawMin / step + eps) * step
    val maxTick = ceil(hi / step - eps) * step
    val rawCount = (maxTick - minTick) / step
    if (!minTick.isFinite() || !maxTick.isFinite() || !rawCount.isFinite()) {
        return DefaultValueAxis
    }
    val count = rawCount.roundToInt().coerceAtLeast(1)
    val ticks =
        List(count + 1) { index ->
            val value = minTick + index * step
            if (abs(value) < step * eps) 0.0 else value
        }
    return NiceValueAxis(ticks.first(), ticks.last(), ticks)
}

internal fun visualizationValueAxis(
    preview: VisualizationPreview,
    config: VisualizationConfig,
): NiceValueAxis {
    if (preview.marks.isEmpty()) return DefaultValueAxis
    val stacked = config.isStackedBar(preview.chartType)
    val marks =
        if (!stacked && preview.hasSecondaryAxis) {
            preview.marks.filter { it.axis == ValueAxis.Primary }.ifEmpty { preview.marks }
        } else {
            preview.marks
        }
    val rawMin: Double
    val rawMax: Double
    if (stacked) {
        val grouped = marks.groupBy { it.xKey }
        rawMin = grouped.minOf { (_, groupedMarks) -> groupedMarks.sumOf { min(0.0, it.y) } }
        rawMax = grouped.maxOf { (_, groupedMarks) -> groupedMarks.sumOf { max(0.0, it.y) } }
    } else {
        rawMin = marks.minOf { min(0.0, it.y) }
        rawMax = marks.maxOf { max(0.0, it.y) }
    }
    return niceValueAxis(rawMin, rawMax)
}

internal fun visualizationSecondaryValueAxis(
    preview: VisualizationPreview,
    config: VisualizationConfig,
): NiceValueAxis? {
    if (preview.marks.isEmpty()) return null
    val stacked = config.isStackedBar(preview.chartType)
    if (stacked || !preview.hasSecondaryAxis) return null
    val marks = preview.marks.filter { it.axis == ValueAxis.Secondary }
    if (marks.isEmpty()) return null
    val rawMin = marks.minOf { min(0.0, it.y) }
    val rawMax = marks.maxOf { max(0.0, it.y) }
    return niceValueAxis(rawMin, rawMax)
}

private fun stackedValueSpans(
    marks: List<VisualizationMark>,
): List<Triple<VisualizationMark, Double, Double>> {
    var positiveCumulative = 0.0
    var negativeCumulative = 0.0
    return marks.map { mark ->
        val start = if (mark.y >= 0.0) positiveCumulative else negativeCumulative
        val end = start + mark.y
        if (mark.y >= 0.0) {
            positiveCumulative = end
        } else {
            negativeCumulative = end
        }
        Triple(mark, start, end)
    }
}

internal fun visualizationGeometry(
    preview: VisualizationPreview,
    config: VisualizationConfig,
    width: Float,
    height: Float,
    insets: PlotInsets = PlotInsets(),
): VisualizationGeometry {
    val plot = plotRect(width, height, insets)
    if (preview.marks.isEmpty() || plot.width <= 0f || plot.height <= 0f) {
        return VisualizationGeometry(emptyList(), emptyMap(), plot)
    }
    val type = preview.chartType
    val stacked = config.isStackedBar(type)
    val axis = visualizationValueAxis(preview, config)
    val secondaryAxis = visualizationSecondaryValueAxis(preview, config)
    fun yPositionFor(value: Double, target: NiceValueAxis): Float =
        (plot.bottom - target.fraction(value) * plot.height).toFloat()
    fun xPositionFor(value: Double, target: NiceValueAxis): Float =
        (plot.left + target.fraction(value) * plot.width).toFloat()
    fun yPosition(value: Double): Float = yPositionFor(value, axis)
    fun xPosition(value: Double): Float = xPositionFor(value, axis)
    fun markAxis(mark: VisualizationMark): NiceValueAxis =
        if (mark.axis == ValueAxis.Secondary && secondaryAxis != null) secondaryAxis else axis

    val regions = mutableListOf<VisualizationHitRegion>()
    val points = linkedMapOf<String, Offset>()
    val categoryCenters = linkedMapOf<String, Float>()
    when (type) {
        ChartType.Bar,
        ChartType.Histogram -> {
            val categories = preview.marks.distinctBy { it.xKey }
            val byCategory = preview.marks.groupBy { it.xKey }
            val band = plot.width / categories.size.coerceAtLeast(1)
            categories.forEachIndexed { categoryIndex, category ->
                val marks = byCategory.getValue(category.xKey)
                if (config.barOrientation == BarOrientation.Horizontal && type == ChartType.Bar) {
                    val horizontalBand = plot.height / categories.size.coerceAtLeast(1)
                    categoryCenters[category.xKey] =
                        plot.top + (categoryIndex + 0.5f) * horizontalBand
                    if (stacked) {
                        stackedValueSpans(marks).forEach { (mark, start, end) ->
                            val left = xPosition(start)
                            val right = xPosition(end)
                            val top =
                                plot.top + categoryIndex * horizontalBand + horizontalBand * 0.15f
                            val rect =
                                Rect(
                                    min(left, right),
                                    top,
                                    max(left, right),
                                    top + horizontalBand * 0.7f,
                                )
                            regions += VisualizationHitRegion(mark.id, rect)
                            points[mark.id] = rect.center
                        }
                    } else {
                        marks.forEachIndexed { seriesIndex, mark ->
                            val barHeight = horizontalBand * 0.7f / marks.size.coerceAtLeast(1)
                            val top =
                                plot.top +
                                    categoryIndex * horizontalBand +
                                    horizontalBand * 0.15f +
                                    seriesIndex * barHeight
                            val targetAxis = markAxis(mark)
                            val zero = xPositionFor(0.0, targetAxis)
                            val value = xPositionFor(mark.y, targetAxis)
                            val rect =
                                Rect(
                                    min(zero, value),
                                    top,
                                    max(zero, value),
                                    top + barHeight * 0.82f,
                                )
                            regions += VisualizationHitRegion(mark.id, rect)
                            points[mark.id] = rect.center
                        }
                    }
                } else if (stacked) {
                    categoryCenters[category.xKey] = plot.left + (categoryIndex + 0.5f) * band
                    stackedValueSpans(marks).forEach { (mark, start, end) ->
                        val y0 = yPosition(start)
                        val y1 = yPosition(end)
                        val rect =
                            Rect(
                                plot.left + categoryIndex * band + band * 0.16f,
                                min(y0, y1),
                                plot.left + (categoryIndex + 1) * band - band * 0.16f,
                                max(y0, y1),
                            )
                        regions += VisualizationHitRegion(mark.id, rect)
                        points[mark.id] = rect.center
                    }
                } else {
                    categoryCenters[category.xKey] = plot.left + (categoryIndex + 0.5f) * band
                    val barWidth = band * 0.72f / marks.size.coerceAtLeast(1)
                    marks.forEachIndexed { seriesIndex, mark ->
                        val left =
                            plot.left + categoryIndex * band + band * 0.14f + seriesIndex * barWidth
                        val targetAxis = markAxis(mark)
                        val zero = yPositionFor(0.0, targetAxis)
                        val value = yPositionFor(mark.y, targetAxis)
                        val rect =
                            Rect(left, min(zero, value), left + barWidth * 0.86f, max(zero, value))
                        regions += VisualizationHitRegion(mark.id, rect)
                        points[mark.id] = rect.center
                    }
                }
            }
        }
        ChartType.Line -> {
            val categories = preview.marks.distinctBy { it.xKey }
            val band = plot.width / categories.size.coerceAtLeast(1)
            val categoryIndex = categories.mapIndexed { index, mark -> mark.xKey to index }.toMap()
            preview.marks.forEach { mark ->
                val x = plot.left + (categoryIndex.getValue(mark.xKey) + 0.5f) * band
                val point = Offset(x, yPositionFor(mark.y, markAxis(mark)))
                points[mark.id] = point
                categoryCenters[mark.xKey] = x
                regions +=
                    VisualizationHitRegion(
                        mark.id,
                        Rect(point.x - 8f, point.y - 8f, point.x + 8f, point.y + 8f),
                    )
            }
        }
        ChartType.Scatter -> {
            val minX = preview.marks.minOf { it.xValue ?: 0.0 }
            val maxX =
                preview.marks.maxOf { it.xValue ?: 0.0 }.let { if (it == minX) it + 1.0 else it }
            // Points use the same scale as the value ticks; only the edge band is coerced so
            // circles stay inside the plot. The pad shrinks to half the plot on tiny canvases
            // so the coerce bounds can never invert.
            val padX = min(8f, plot.width / 2f)
            val padY = min(8f, plot.height / 2f)
            preview.marks.forEach { mark ->
                val x =
                    (plot.left + ((mark.xValue ?: minX) - minX) / (maxX - minX) * plot.width)
                        .toFloat()
                        .coerceIn(plot.left + padX, plot.right - padX)
                val y = yPosition(mark.y).coerceIn(plot.top + padY, plot.bottom - padY)
                val point = Offset(x, y)
                val radius = (mark.size?.let { 4f + min(10.0, abs(it) / 10.0).toFloat() } ?: 6f)
                points[mark.id] = point
                categoryCenters.putIfAbsent(mark.xKey, x)
                regions +=
                    VisualizationHitRegion(
                        mark.id,
                        Rect(
                            point.x - radius,
                            point.y - radius,
                            point.x + radius,
                            point.y + radius,
                        ),
                    )
            }
        }
        ChartType.Kpi,
        ChartType.Auto,
        null -> Unit
    }
    return VisualizationGeometry(
        regions = regions,
        points = points,
        plot = plot,
        valueAxis = axis,
        categoryCenters = categoryCenters,
        secondaryValueAxis = secondaryAxis,
    )
}

internal fun legendSeriesLabel(
    series: VisualizationSeries,
    hasSecondaryAxis: Boolean,
    horizontalBars: Boolean,
): String {
    if (!hasSecondaryAxis) return series.label
    val side =
        when {
            horizontalBars && series.axis == ValueAxis.Secondary -> "top"
            horizontalBars -> "bottom"
            series.axis == ValueAxis.Secondary -> "right"
            else -> "left"
        }
    return "${series.label} ($side)"
}

internal val DarkBarLabelColor = Color(0xFF0F172A)
private val DarkBarLabelLuminance = DarkBarLabelColor.luminance()

internal fun contrastingBarLabelColor(barColor: Color): Color {
    val lum = barColor.luminance()
    val whiteContrast = (1.0f + 0.05f) / (lum + 0.05f)
    val darkContrast = (lum + 0.05f) / (DarkBarLabelLuminance + 0.05f)
    return if (whiteContrast >= darkContrast) Color.White else DarkBarLabelColor
}

internal fun Color.toCssHex(): String {
    val r = (red * 255f).roundToInt().coerceIn(0, 255)
    val g = (green * 255f).roundToInt().coerceIn(0, 255)
    val b = (blue * 255f).roundToInt().coerceIn(0, 255)
    return "#%02x%02x%02x".format(Locale.ROOT, r, g, b)
}

internal fun barValueLabelText(mark: VisualizationMark, config: VisualizationConfig): String {
    val measure = config.values.firstOrNull { it.alias == mark.measureAlias }
    return if (measure != null && measure.numberFormat.kind == NumberFormatKind.Auto) {
        compactNumber(mark.y)
    } else {
        mark.formattedY
    }
}

internal fun compactNumber(value: Double): String {
    val normalized = if (value == 0.0) 0.0 else value
    return when {
        abs(normalized) >= 1_000_000_000_000 -> compactUnit(normalized / 1_000_000_000_000, "T")
        abs(normalized) >= 1_000_000_000 -> compactUnit(normalized / 1_000_000_000, "B")
        abs(normalized) >= 1_000_000 -> compactUnit(normalized / 1_000_000, "M")
        abs(normalized) >= 1_000 -> compactUnit(normalized / 1_000, "K")
        abs(normalized) >= 10 -> "%.0f".format(Locale.ROOT, normalized)
        else -> "%.2f".format(Locale.ROOT, normalized).trimEnd('0').trimEnd('.')
    }
}

private fun compactUnit(scaled: Double, suffix: String): String =
    "%.1f".format(Locale.ROOT, scaled).removeSuffix(".0") + suffix

internal fun plotRect(width: Float, height: Float, insets: PlotInsets): Rect {
    val minPlotWidth = 20f
    val minPlotHeight = 30f
    val maxLeft = (width - insets.right.coerceAtLeast(0f) - minPlotWidth).coerceAtLeast(0f)
    val left = insets.left.coerceIn(0f, maxLeft)
    // Trailing insets shrink before the plot drops below its minimum size.
    val right = (width - insets.right).coerceAtLeast(left + minPlotWidth).coerceAtMost(width)
    val maxTop = (height - insets.bottom.coerceAtLeast(0f) - minPlotHeight).coerceAtLeast(0f)
    val top = insets.top.coerceIn(0f, maxTop)
    val bottom = (height - insets.bottom).coerceAtLeast(top + minPlotHeight).coerceAtMost(height)
    return Rect(left, top, right, bottom)
}

internal fun valueTickMaxWidth(horizontalBars: Boolean, plot: Rect, gapPx: Float): Float =
    if (horizontalBars) max(plot.width / 4f, 1f) else max(plot.left - gapPx, 0f)

// Walks centers in axis order and labels one only when it sits at least requiredGap after the
// previous labeled center, so uneven scatter spacing cannot overlap labels or skip isolated
// points. Centers must be sorted ascending.
internal fun categoryLabelIndices(
    sortedCenters: List<Float>,
    requiredGap: Float,
    minX: Float = Float.NEGATIVE_INFINITY,
): List<Int> {
    val selected = mutableListOf<Int>()
    var lastEffectiveCenter = Float.NEGATIVE_INFINITY
    sortedCenters.forEachIndexed { index, center ->
        val effectiveCenter = max(center, minX + requiredGap / 2f)
        if (selected.isEmpty() || center - lastEffectiveCenter >= requiredGap) {
            selected += index
            lastEffectiveCenter = effectiveCenter
        }
    }
    return selected
}

internal fun categoryLabelSpanMaxWidth(
    center: Float,
    minX: Float,
    canvasWidth: Float,
    available: Float,
    leftLimit: Float,
    rightLimit: Float,
    isFirst: Boolean,
    isLast: Boolean,
): Float =
    when {
        isFirst && isLast -> (canvasWidth - minX).coerceAtLeast(0f)
        isFirst ->
            minOf(rightLimit - minX, 2f * (rightLimit - center), canvasWidth - minX)
                .coerceAtLeast(0f)
        isLast ->
            minOf(canvasWidth - leftLimit, 2f * (center - leftLimit), canvasWidth - minX)
                .coerceAtLeast(0f)
        else ->
            minOf(available, 2f * (center - leftLimit), 2f * (rightLimit - center))
                .coerceAtLeast(0f)
    }

internal fun categoryLabelStep(labelExtent: Float, band: Float): Int =
    ceil(labelExtent / band.coerceAtLeast(1e-3f)).toInt().coerceAtLeast(1)

internal data class CategoryLabelEntry(
    val xKey: String,
    val text: String,
    val center: Float,
    val naturalWidth: Float,
)

internal data class PlacedCategoryLabel<T>(
    val entry: CategoryLabelEntry,
    val maxWidth: Float,
    val drawX: Float,
    val measured: T,
)

// Decimates and places horizontal category labels in spatial order so clustered scatter points
// collapse to one label while dense edge labels stay clamped inside the plot without overlapping
// their neighbors.
internal inline fun <T> layoutHorizontalCategoryLabels(
    entries: List<CategoryLabelEntry>,
    plotLeft: Float,
    plotWidth: Float,
    canvasWidth: Float,
    gapPx: Float,
    measureFitted: (text: String, maxWidth: Float) -> T,
    measuredWidth: (T) -> Float,
): List<PlacedCategoryLabel<T>> {
    if (entries.isEmpty()) return emptyList()
    val ordered = entries.sortedBy { it.center }
    val widest = ordered.maxOf { it.naturalWidth }
    val selected =
        categoryLabelIndices(
            ordered.map { it.center },
            min(widest + gapPx, 160f),
            minX = plotLeft,
        )
    val selectedCenters = selected.map { ordered[it].center }
    val nominals = selectedCenters.mapIndexed { position, x ->
        val previousGap = if (position == 0) plotWidth else x - selectedCenters[position - 1]
        val nextGap =
            if (position == selectedCenters.lastIndex) plotWidth
            else selectedCenters[position + 1] - x
        (min(min(previousGap, nextGap), plotWidth) - gapPx).coerceAtLeast(0f)
    }
    val estimatedLefts = selected.mapIndexed { position, index ->
        val entry = ordered[index]
        val width = min(entry.naturalWidth, nominals[position])
        clampLabelX(entry.center - width / 2f, width, plotLeft, canvasWidth)
    }
    var previousRight = plotLeft
    return selected.mapIndexed { position, index ->
        val entry = ordered[index]
        val leftLimit = if (position == 0) plotLeft else previousRight + gapPx
        val rightLimit =
            if (position == selected.lastIndex) canvasWidth
            else estimatedLefts[position + 1] - gapPx
        val maxWidth =
            categoryLabelSpanMaxWidth(
                center = entry.center,
                minX = plotLeft,
                canvasWidth = canvasWidth,
                available = nominals[position],
                leftLimit = leftLimit,
                rightLimit = rightLimit,
                isFirst = position == 0,
                isLast = position == selected.lastIndex,
            )
        val measured = measureFitted(entry.text, maxWidth)
        val fittedWidth = measuredWidth(measured)
        val drawX = clampLabelX(entry.center - fittedWidth / 2f, fittedWidth, plotLeft, canvasWidth)
        previousRight = drawX + fittedWidth
        PlacedCategoryLabel(entry = entry, maxWidth = maxWidth, drawX = drawX, measured = measured)
    }
}

internal fun clampLabelX(x: Float, labelWidth: Float, minX: Float, canvasWidth: Float): Float =
    min(max(minX, x), max(minX, canvasWidth - labelWidth))

internal fun tooltipAlignsToStart(pointerX: Float, plotCenterX: Float): Boolean =
    pointerX > plotCenterX
