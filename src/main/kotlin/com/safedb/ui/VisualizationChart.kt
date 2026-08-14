package com.safedb.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.safedb.explore.BarArrangement
import com.safedb.explore.BarOrientation
import com.safedb.explore.ChartType
import com.safedb.explore.VisualizationConfig
import com.safedb.explore.VisualizationMark
import com.safedb.explore.VisualizationPreview
import com.safedb.ui.theme.DataMono
import com.safedb.ui.theme.SafeDbTheme
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

private const val LegendEntryLimit = 12

internal data class VisualizationHitRegion(val markId: String, val bounds: Rect) {
    fun contains(offset: Offset): Boolean = bounds.contains(offset)
}

internal data class VisualizationGeometry(
    val regions: List<VisualizationHitRegion>,
    val points: Map<String, Offset>,
    val plot: Rect,
    val yMin: Double,
    val yMax: Double,
    // xKey -> center along the category axis: x for vertical bars/line/scatter, y for horizontal
    // bars
    val categoryCenters: Map<String, Float> = emptyMap(),
)

internal data class PlotInsets(
    val left: Float = 72f,
    val top: Float = 24f,
    val right: Float = 28f,
    val bottom: Float = 58f,
)

// Exposed so the chart can build tick strings (and measure them) before the plot rect exists.
internal fun visualizationValueRange(
    preview: VisualizationPreview,
    config: VisualizationConfig,
): ClosedFloatingPointRange<Double> {
    if (preview.marks.isEmpty()) return 0.0..1.0
    val stacked =
        preview.chartType == ChartType.Bar && config.barArrangement == BarArrangement.Stacked
    val rawMin = preview.marks.minOf { min(0.0, it.y) }
    val rawMax =
        if (stacked) {
            preview.marks.groupBy { it.xKey }.maxOf { (_, marks) -> marks.sumOf { max(0.0, it.y) } }
        } else {
            preview.marks.maxOf { max(0.0, it.y) }
        }
    return rawMin..(if (rawMax == rawMin) rawMax + 1.0 else rawMax)
}

internal fun visualizationGeometry(
    preview: VisualizationPreview,
    config: VisualizationConfig,
    width: Float,
    height: Float,
    insets: PlotInsets = PlotInsets(),
): VisualizationGeometry {
    val plot =
        Rect(
            insets.left,
            insets.top,
            max(insets.left + 20f, width - insets.right),
            max(insets.top + 30f, height - insets.bottom),
        )
    if (preview.marks.isEmpty() || plot.width <= 0f || plot.height <= 0f) {
        return VisualizationGeometry(emptyList(), emptyMap(), plot, 0.0, 1.0)
    }
    val type = preview.chartType
    val stacked = type == ChartType.Bar && config.barArrangement == BarArrangement.Stacked
    val range = visualizationValueRange(preview, config)
    val rawMin = range.start
    val yMax = range.endInclusive
    fun yPosition(value: Double): Float =
        (plot.bottom - ((value - rawMin) / (yMax - rawMin) * plot.height)).toFloat()
    fun xPosition(value: Double): Float =
        (plot.left + ((value - rawMin) / (yMax - rawMin) * plot.width)).toFloat()

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
                        var positiveCumulative = 0.0
                        var negativeCumulative = 0.0
                        marks.forEach { mark ->
                            val start =
                                if (mark.y >= 0.0) positiveCumulative else negativeCumulative
                            val end = start + mark.y
                            if (mark.y >= 0.0) {
                                positiveCumulative = end
                            } else {
                                negativeCumulative = end
                            }
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
                            val zero = xPosition(0.0)
                            val value = xPosition(mark.y)
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
                    var cumulative = 0.0
                    marks.forEach { mark ->
                        val next = cumulative + mark.y
                        val rect =
                            Rect(
                                plot.left + categoryIndex * band + band * 0.16f,
                                yPosition(next),
                                plot.left + (categoryIndex + 1) * band - band * 0.16f,
                                yPosition(cumulative),
                            )
                        cumulative = next
                        regions += VisualizationHitRegion(mark.id, rect)
                        points[mark.id] = rect.center
                    }
                } else {
                    categoryCenters[category.xKey] = plot.left + (categoryIndex + 0.5f) * band
                    val barWidth = band * 0.72f / marks.size.coerceAtLeast(1)
                    marks.forEachIndexed { seriesIndex, mark ->
                        val left =
                            plot.left + categoryIndex * band + band * 0.14f + seriesIndex * barWidth
                        val zero = yPosition(0.0)
                        val value = yPosition(mark.y)
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
            val band = if (categories.size <= 1) 0f else plot.width / (categories.size - 1)
            val categoryIndex = categories.mapIndexed { index, mark -> mark.xKey to index }.toMap()
            preview.marks.forEach { mark ->
                val x =
                    if (categories.size <= 1) plot.center.x
                    else plot.left + categoryIndex.getValue(mark.xKey) * band
                val point = Offset(x, yPosition(mark.y))
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
            preview.marks.forEach { mark ->
                val padding = 8f
                val x =
                    plot.left +
                        padding +
                        (((mark.xValue ?: minX) - minX) / (maxX - minX) *
                                (plot.width - padding * 2f))
                            .toFloat()
                val y = yPosition(mark.y).coerceIn(plot.top + padding, plot.bottom - padding)
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
    return VisualizationGeometry(regions, points, plot, rawMin, yMax, categoryCenters)
}

@Composable
internal fun VisualizationChart(
    preview: VisualizationPreview,
    config: VisualizationConfig,
    sampleRowCount: Int,
    sampleTruncated: Boolean,
    onMarkClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    exportMode: Boolean = false,
) {
    Surface(
        modifier =
            modifier.semantics {
                contentDescription = buildString {
                    append(preview.title.ifBlank { "Visualization" })
                    preview.chartType?.let { append(", ${it.name.lowercase()} chart") }
                    config.x?.let { append(", X axis ${it.label}") }
                    if (config.values.isNotEmpty()) {
                        append(", values ${config.values.joinToString { it.label }}")
                    }
                    config.series?.let { append(", series ${it.label}") }
                    append(", ${preview.marks.size} plotted values")
                }
            },
        color = MaterialTheme.colorScheme.surface,
        shape = if (exportMode) RoundedCornerShape(0.dp) else RoundedCornerShape(4.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(if (exportMode) 44.dp else 20.dp)) {
            if (preview.title.isNotBlank()) {
                Text(
                    preview.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (preview.series.size > 1) {
                VisualizationLegend(preview, Modifier.padding(top = 8.dp))
            }
            val message = preview.blockingMessage
            if (message != null) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else if (preview.chartType == ChartType.Kpi) {
                KpiChart(preview, onMarkClick, Modifier.weight(1f).fillMaxWidth())
            } else {
                PlotChart(
                    preview,
                    config,
                    onMarkClick,
                    Modifier.weight(1f).fillMaxWidth().padding(top = 12.dp),
                )
            }
            if (exportMode) {
                Text(
                    "Based on $sampleRowCount sampled rows${if (sampleTruncated) " · sample truncated" else ""}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun VisualizationLegend(preview: VisualizationPreview, modifier: Modifier = Modifier) {
    val seriesPalette = SafeDbTheme.colors.series
    // Cap the rows so a high-cardinality series field cannot squeeze the plot to nothing.
    val shown = preview.series.take(LegendEntryLimit)
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        shown.forEachIndexed { index, series ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier =
                        Modifier.size(9.dp)
                            .background(seriesPalette[index % seriesPalette.size], CircleShape)
                )
                Text(
                    series.label,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 5.dp).widthIn(max = 160.dp),
                )
            }
        }
        if (preview.series.size > shown.size) {
            Text(
                "+${preview.series.size - shown.size} more",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun KpiChart(
    preview: VisualizationPreview,
    onMarkClick: (String) -> Unit,
    modifier: Modifier,
) {
    val mark = preview.marks.first()
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Surface(
            modifier =
                Modifier.pointerHoverIcon(PointerIcon.Hand).pointerInput(mark.id) {
                    detectTapGestures { onMarkClick(mark.id) }
                },
            color = SafeDbTheme.colors.accentContainer,
            shape = RoundedCornerShape(4.dp),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 52.dp, vertical = 34.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    mark.formattedY,
                    style = MaterialTheme.typography.headlineLarge,
                    color = SafeDbTheme.colors.onAccentContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 520.dp),
                )
                Text(
                    mark.measureLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 7.dp).widthIn(max = 520.dp),
                )
                Text(
                    "${mark.sourceRowIndices.size} contributing rows",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun PlotChart(
    preview: VisualizationPreview,
    config: VisualizationConfig,
    onMarkClick: (String) -> Unit,
    modifier: Modifier,
) {
    var size by remember { mutableStateOf(androidx.compose.ui.geometry.Size.Zero) }
    var hovered by remember(preview) { mutableStateOf<VisualizationMark?>(null) }
    // Default cache of 8 thrashes once a chart has more than a handful of distinct labels.
    val textMeasurer = rememberTextMeasurer(cacheSize = 64)
    val axisColor = MaterialTheme.colorScheme.outline
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val surfaceColor = MaterialTheme.colorScheme.surface
    val seriesPalette = SafeDbTheme.colors.series
    val labelStyle = DataMono.copy(color = labelColor)
    val seriesIndex = preview.series.mapIndexed { index, series -> series.key to index }.toMap()
    val horizontalBars =
        preview.chartType == ChartType.Bar && config.barOrientation == BarOrientation.Horizontal
    val gapPx = with(LocalDensity.current) { 6.dp.toPx() }

    // Gutters are derived from measured label sizes, not fixed guesses, so nothing is clipped.
    // Deliberately independent of the canvas size to avoid a measure/layout feedback loop.
    val insets =
        remember(preview, config, labelStyle, textMeasurer, gapPx, horizontalBars) {
            val range = visualizationValueRange(preview, config)
            val ticks =
                List(5) { tick ->
                    textMeasurer
                        .measure(
                            compactNumber(
                                range.start + (range.endInclusive - range.start) * tick / 4f
                            ),
                            labelStyle,
                        )
                        .size
                }
            val tickWidth = ticks.maxOf { it.width }.toFloat()
            val lineHeight = ticks.maxOf { it.height }.toFloat()
            val bottom = lineHeight + gapPx * 2f
            if (horizontalBars) {
                val category =
                    preview.marks
                        .distinctBy { it.xKey }
                        .maxOfOrNull {
                            textMeasurer.measure(it.xLabel, labelStyle).size.width.toFloat()
                        } ?: 0f
                PlotInsets(
                    left = max(category + gapPx * 2f, tickWidth / 2f + gapPx).coerceIn(44f, 180f),
                    // The last bottom tick is centered on plot.right.
                    right = max(28f, tickWidth / 2f + gapPx),
                    bottom = bottom,
                )
            } else {
                PlotInsets(left = (tickWidth + gapPx * 2f).coerceIn(44f, 180f), bottom = bottom)
            }
        }
    val geometry =
        remember(preview, config, size, insets) {
            visualizationGeometry(preview, config, size.width, size.height, insets)
        }

    Box(modifier = modifier) {
        Canvas(
            modifier =
                Modifier.fillMaxSize()
                    .onPointerEvent(PointerEventType.Move) { event ->
                        val position = event.changes.firstOrNull()?.position
                        hovered =
                            position
                                ?.let { pointer ->
                                    geometry.regions.firstOrNull { it.contains(pointer) }?.markId
                                }
                                ?.let { id -> preview.marks.firstOrNull { it.id == id } }
                    }
                    .onPointerEvent(PointerEventType.Exit) { hovered = null }
                    .pointerInput(geometry, preview) {
                        detectTapGestures { offset ->
                            geometry.regions
                                .firstOrNull { it.contains(offset) }
                                ?.let { onMarkClick(it.markId) }
                        }
                    }
        ) {
            size = this.size
            val plot = geometry.plot
            val canvasWidth = this.size.width

            fun fitted(text: String, maxWidth: Float) =
                textMeasurer.measure(
                    text,
                    labelStyle,
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 1,
                    constraints = Constraints(maxWidth = maxWidth.toInt().coerceAtLeast(0)),
                )
            // coerceIn would throw once the label is wider than the canvas.
            fun clampX(x: Float, labelWidth: Float): Float =
                min(max(0f, x), max(0f, canvasWidth - labelWidth))

            clipRect {
                repeat(5) { tick ->
                    val fraction = tick / 4f
                    val value = geometry.yMin + (geometry.yMax - geometry.yMin) * fraction
                    val label = fitted(compactNumber(value), plot.left - gapPx)
                    if (horizontalBars) {
                        val x = plot.left + plot.width * fraction
                        drawLine(
                            gridColor,
                            Offset(x, plot.top),
                            Offset(x, plot.bottom),
                            strokeWidth = 1f,
                        )
                        drawText(
                            label,
                            topLeft =
                                Offset(
                                    clampX(x - label.size.width / 2f, label.size.width.toFloat()),
                                    plot.bottom + gapPx,
                                ),
                        )
                    } else {
                        val y = plot.bottom - plot.height * fraction
                        drawLine(
                            gridColor,
                            Offset(plot.left, y),
                            Offset(plot.right, y),
                            strokeWidth = 1f,
                        )
                        drawText(
                            label,
                            topLeft =
                                Offset(
                                    max(0f, plot.left - gapPx - label.size.width),
                                    y - label.size.height / 2f,
                                ),
                        )
                    }
                }
                drawLine(
                    axisColor,
                    Offset(plot.left, plot.top),
                    Offset(plot.left, plot.bottom),
                    strokeWidth = 1.5f,
                )
                drawLine(
                    axisColor,
                    Offset(plot.left, plot.bottom),
                    Offset(plot.right, plot.bottom),
                    strokeWidth = 1.5f,
                )

                when (preview.chartType) {
                    ChartType.Bar,
                    ChartType.Histogram ->
                        geometry.regions.forEach { region ->
                            val mark = preview.marks.first { it.id == region.markId }
                            val color =
                                seriesPalette[
                                    seriesIndex[mark.seriesKey]?.rem(seriesPalette.size) ?: 0]
                            drawRect(color, region.bounds.topLeft, region.bounds.size)
                            if (hovered?.id == mark.id)
                                drawRect(
                                    labelColor,
                                    region.bounds.topLeft,
                                    region.bounds.size,
                                    style = Stroke(2f),
                                )
                            if (config.showLabels) {
                                val available = region.bounds.width - 4f
                                // Fall back to the compact form before giving up on a label.
                                var label = textMeasurer.measure(mark.formattedY, labelStyle)
                                if (label.size.width > available) {
                                    label = textMeasurer.measure(compactNumber(mark.y), labelStyle)
                                }
                                if (
                                    label.size.width <= available &&
                                        label.size.height <= region.bounds.height - 2f
                                ) {
                                    drawText(
                                        label,
                                        topLeft =
                                            Offset(
                                                region.bounds.center.x - label.size.width / 2f,
                                                if (horizontalBars)
                                                    region.bounds.center.y - label.size.height / 2f
                                                else region.bounds.top + 2f,
                                            ),
                                    )
                                }
                            }
                        }
                    ChartType.Line -> {
                        preview.marks
                            .groupBy { it.seriesKey }
                            .forEach { (key, marks) ->
                                val color =
                                    seriesPalette[seriesIndex[key]?.rem(seriesPalette.size) ?: 0]
                                marks.zipWithNext().forEach { (left, right) ->
                                    drawLine(
                                        color,
                                        geometry.points.getValue(left.id),
                                        geometry.points.getValue(right.id),
                                        strokeWidth = 3f,
                                    )
                                }
                                marks.forEach { mark ->
                                    val point = geometry.points.getValue(mark.id)
                                    drawCircle(
                                        color,
                                        if (hovered?.id == mark.id) 7f else 5f,
                                        point,
                                    )
                                    drawCircle(surfaceColor, 2f, point)
                                }
                            }
                    }
                    ChartType.Scatter ->
                        preview.marks.forEach { mark ->
                            val color =
                                seriesPalette[
                                    seriesIndex[mark.seriesKey]?.rem(seriesPalette.size) ?: 0]
                            val region = geometry.regions.first { it.markId == mark.id }
                            drawCircle(
                                color.copy(alpha = 0.82f),
                                region.bounds.width / 2f,
                                region.bounds.center,
                            )
                            if (hovered?.id == mark.id)
                                drawCircle(
                                    labelColor,
                                    region.bounds.width / 2f + 2f,
                                    region.bounds.center,
                                    style = Stroke(2f),
                                )
                        }
                    ChartType.Kpi,
                    ChartType.Auto,
                    null -> Unit
                }

                val categoryMarks = preview.marks.distinctBy { it.xKey }
                if (categoryMarks.isEmpty()) return@clipRect
                if (horizontalBars) {
                    val bandHeight = plot.height / categoryMarks.size.coerceAtLeast(1)
                    val lineHeight = textMeasurer.measure("0", labelStyle).size.height.toFloat()
                    val step =
                        ceil((lineHeight + 2f) / bandHeight.coerceAtLeast(1f))
                            .toInt()
                            .coerceAtLeast(1)
                    val available = plot.left - gapPx * 2f
                    categoryMarks
                        .filterIndexed { index, _ -> index % step == 0 }
                        .forEach { mark ->
                            val y = geometry.categoryCenters[mark.xKey] ?: return@forEach
                            val label = fitted(mark.xLabel, available)
                            drawText(
                                label,
                                topLeft =
                                    Offset(
                                        max(0f, plot.left - gapPx - label.size.width),
                                        y - label.size.height / 2f,
                                    ),
                            )
                        }
                } else {
                    val slot = plot.width / categoryMarks.size.coerceAtLeast(1)
                    val widest = categoryMarks.maxOf {
                        textMeasurer.measure(it.xLabel, labelStyle).size.width
                    }
                    val step =
                        ceil(min(widest + gapPx, 160f) / slot.coerceAtLeast(1f))
                            .toInt()
                            .coerceAtLeast(1)
                    val available = slot * step - gapPx
                    categoryMarks
                        .filterIndexed { index, _ -> index % step == 0 }
                        .forEach { mark ->
                            val x = geometry.categoryCenters[mark.xKey] ?: return@forEach
                            val label = fitted(mark.xLabel, available)
                            drawText(
                                label,
                                topLeft =
                                    Offset(
                                        clampX(
                                            x - label.size.width / 2f,
                                            label.size.width.toFloat(),
                                        ),
                                        plot.bottom + gapPx,
                                    ),
                            )
                        }
                }
            }
        }
        hovered?.let { mark ->
            // Sit on the opposite side of the plot from the hovered mark so it never covers it.
            val markX = geometry.regions.firstOrNull { it.markId == mark.id }?.bounds?.center?.x
            val alignment =
                if (markX != null && markX > geometry.plot.center.x) Alignment.TopStart
                else Alignment.TopEnd
            Surface(
                modifier = Modifier.align(alignment).padding(12.dp).widthIn(max = 280.dp),
                color = MaterialTheme.colorScheme.inverseSurface,
                shape = RoundedCornerShape(3.dp),
                shadowElevation = 4.dp,
            ) {
                Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                    if (mark.xLabel.isNotBlank())
                        Text(
                            mark.xLabel,
                            color = MaterialTheme.colorScheme.inverseOnSurface,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    Text(
                        "${mark.seriesLabel}: ${mark.formattedY}",
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        style = DataMono,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${mark.sourceRowIndices.size} contributing row${if (mark.sourceRowIndices.size == 1) "" else "s"}",
                        color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

internal fun compactNumber(value: Double): String =
    when {
        abs(value) >= 1_000_000_000_000 -> "%.1fT".format(value / 1_000_000_000_000)
        abs(value) >= 1_000_000_000 -> "%.1fB".format(value / 1_000_000_000)
        abs(value) >= 1_000_000 -> "%.1fM".format(value / 1_000_000)
        abs(value) >= 1_000 -> "%.1fK".format(value / 1_000)
        abs(value) >= 10 -> "%.0f".format(value)
        else -> "%.2f".format(value).trimEnd('0').trimEnd('.')
    }
