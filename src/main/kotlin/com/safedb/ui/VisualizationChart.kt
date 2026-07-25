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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.safedb.explore.BarArrangement
import com.safedb.explore.BarOrientation
import com.safedb.explore.ChartType
import com.safedb.explore.VisualizationConfig
import com.safedb.explore.VisualizationMark
import com.safedb.explore.VisualizationPreview
import com.safedb.ui.theme.DataMono
import com.safedb.ui.theme.SafeDbTheme
import com.safedb.ui.theme.VisualizationSeriesPalette
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal data class VisualizationHitRegion(
    val markId: String,
    val bounds: Rect,
) {
    fun contains(offset: Offset): Boolean = bounds.contains(offset)
}

internal data class VisualizationGeometry(
    val regions: List<VisualizationHitRegion>,
    val points: Map<String, Offset>,
    val plot: Rect,
    val yMin: Double,
    val yMax: Double,
)

internal fun visualizationGeometry(
    preview: VisualizationPreview,
    config: VisualizationConfig,
    width: Float,
    height: Float,
): VisualizationGeometry {
    val plot = Rect(72f, 24f, max(92f, width - 28f), max(54f, height - 58f))
    if (preview.marks.isEmpty() || plot.width <= 0f || plot.height <= 0f) {
        return VisualizationGeometry(emptyList(), emptyMap(), plot, 0.0, 1.0)
    }
    val type = preview.chartType
    val stacked = type == ChartType.Bar && config.barArrangement == BarArrangement.Stacked
    val rawMin = preview.marks.minOf { min(0.0, it.y) }
    val rawMax = if (stacked) {
        preview.marks.groupBy { it.xKey }.maxOf { (_, marks) -> marks.sumOf { max(0.0, it.y) } }
    } else {
        preview.marks.maxOf { max(0.0, it.y) }
    }
    val yMin = rawMin
    val yMax = if (rawMax == rawMin) rawMax + 1.0 else rawMax
    fun yPosition(value: Double): Float =
        (plot.bottom - ((value - yMin) / (yMax - yMin) * plot.height)).toFloat()
    fun xPosition(value: Double): Float =
        (plot.left + ((value - yMin) / (yMax - yMin) * plot.width)).toFloat()

    val regions = mutableListOf<VisualizationHitRegion>()
    val points = linkedMapOf<String, Offset>()
    when (type) {
        ChartType.Bar, ChartType.Histogram -> {
            val categories = preview.marks.distinctBy { it.xKey }
            val byCategory = preview.marks.groupBy { it.xKey }
            val band = plot.width / categories.size.coerceAtLeast(1)
            categories.forEachIndexed { categoryIndex, category ->
                val marks = byCategory.getValue(category.xKey)
                if (config.barOrientation == BarOrientation.Horizontal && type == ChartType.Bar) {
                    val horizontalBand = plot.height / categories.size.coerceAtLeast(1)
                    if (stacked) {
                        var positiveCumulative = 0.0
                        var negativeCumulative = 0.0
                        marks.forEach { mark ->
                            val start = if (mark.y >= 0.0) positiveCumulative else negativeCumulative
                            val end = start + mark.y
                            if (mark.y >= 0.0) {
                                positiveCumulative = end
                            } else {
                                negativeCumulative = end
                            }
                            val left = xPosition(start)
                            val right = xPosition(end)
                            val top = plot.top + categoryIndex * horizontalBand + horizontalBand * 0.15f
                            val rect = Rect(min(left, right), top, max(left, right), top + horizontalBand * 0.7f)
                            regions += VisualizationHitRegion(mark.id, rect)
                            points[mark.id] = rect.center
                        }
                    } else {
                        marks.forEachIndexed { seriesIndex, mark ->
                            val barHeight = horizontalBand * 0.7f / marks.size.coerceAtLeast(1)
                            val top = plot.top + categoryIndex * horizontalBand + horizontalBand * 0.15f + seriesIndex * barHeight
                            val zero = xPosition(0.0)
                            val value = xPosition(mark.y)
                            val rect = Rect(min(zero, value), top, max(zero, value), top + barHeight * 0.82f)
                            regions += VisualizationHitRegion(mark.id, rect)
                            points[mark.id] = rect.center
                        }
                    }
                } else if (stacked) {
                    var cumulative = 0.0
                    marks.forEach { mark ->
                        val next = cumulative + mark.y
                        val rect = Rect(
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
                    val barWidth = band * 0.72f / marks.size.coerceAtLeast(1)
                    marks.forEachIndexed { seriesIndex, mark ->
                        val left = plot.left + categoryIndex * band + band * 0.14f + seriesIndex * barWidth
                        val zero = yPosition(0.0)
                        val value = yPosition(mark.y)
                        val rect = Rect(left, min(zero, value), left + barWidth * 0.86f, max(zero, value))
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
                val x = if (categories.size <= 1) plot.center.x else plot.left + categoryIndex.getValue(mark.xKey) * band
                val point = Offset(x, yPosition(mark.y))
                points[mark.id] = point
                regions += VisualizationHitRegion(mark.id, Rect(point.x - 8f, point.y - 8f, point.x + 8f, point.y + 8f))
            }
        }
        ChartType.Scatter -> {
            val minX = preview.marks.minOf { it.xValue ?: 0.0 }
            val maxX = preview.marks.maxOf { it.xValue ?: 0.0 }.let { if (it == minX) it + 1.0 else it }
            preview.marks.forEach { mark ->
                val padding = 8f
                val x = plot.left + padding +
                    (((mark.xValue ?: minX) - minX) / (maxX - minX) * (plot.width - padding * 2f)).toFloat()
                val y = yPosition(mark.y).coerceIn(plot.top + padding, plot.bottom - padding)
                val point = Offset(x, y)
                val radius = (mark.size?.let { 4f + min(10.0, abs(it) / 10.0).toFloat() } ?: 6f)
                points[mark.id] = point
                regions += VisualizationHitRegion(mark.id, Rect(point.x - radius, point.y - radius, point.x + radius, point.y + radius))
            }
        }
        ChartType.Kpi, ChartType.Auto, null -> Unit
    }
    return VisualizationGeometry(regions, points, plot, yMin, yMax)
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
        modifier = modifier.semantics {
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
                Text(preview.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
            if (preview.series.size > 1) {
                VisualizationLegend(preview, Modifier.padding(top = 8.dp))
            }
            val message = preview.blockingMessage
            if (message != null) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else if (preview.chartType == ChartType.Kpi) {
                KpiChart(preview, onMarkClick, Modifier.weight(1f).fillMaxWidth())
            } else {
                PlotChart(preview, config, onMarkClick, Modifier.weight(1f).fillMaxWidth().padding(top = 12.dp))
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
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        preview.series.forEachIndexed { index, series ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(9.dp).background(
                        VisualizationSeriesPalette[index % VisualizationSeriesPalette.size],
                        CircleShape,
                    ),
                )
                Text(series.label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 5.dp))
            }
        }
    }
}

@Composable
private fun KpiChart(preview: VisualizationPreview, onMarkClick: (String) -> Unit, modifier: Modifier) {
    val mark = preview.marks.first()
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Surface(
            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand).pointerInput(mark.id) {
                detectTapGestures { onMarkClick(mark.id) }
            },
            color = SafeDbTheme.colors.accentContainer,
            shape = RoundedCornerShape(4.dp),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 52.dp, vertical = 34.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(mark.formattedY, style = MaterialTheme.typography.headlineLarge, color = SafeDbTheme.colors.onAccentContainer)
                Text(mark.measureLabel, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 7.dp))
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
    val geometry = remember(preview, config, size) {
        visualizationGeometry(preview, config, size.width, size.height)
    }
    var hovered by remember(preview) { mutableStateOf<VisualizationMark?>(null) }
    val textMeasurer = rememberTextMeasurer()
    val axisColor = MaterialTheme.colorScheme.outline
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val surfaceColor = MaterialTheme.colorScheme.surface
    val labelStyle = DataMono.copy(color = labelColor)
    val seriesIndex = preview.series.mapIndexed { index, series -> series.key to index }.toMap()

    Box(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .onPointerEvent(PointerEventType.Move) { event ->
                    val position = event.changes.firstOrNull()?.position
                    hovered = position?.let { pointer ->
                            geometry.regions.firstOrNull { it.contains(pointer) }?.markId
                        }
                        ?.let { id -> preview.marks.firstOrNull { it.id == id } }
                }
                .onPointerEvent(PointerEventType.Exit) { hovered = null }
                .pointerInput(geometry, preview) {
                    detectTapGestures { offset ->
                        geometry.regions.firstOrNull { it.contains(offset) }?.let { onMarkClick(it.markId) }
                    }
                },
        ) {
            size = this.size
            val plot = geometry.plot
            val horizontalBars = preview.chartType == ChartType.Bar && config.barOrientation == BarOrientation.Horizontal
            repeat(5) { tick ->
                val fraction = tick / 4f
                val value = geometry.yMin + (geometry.yMax - geometry.yMin) * fraction
                if (horizontalBars) {
                    val x = plot.left + plot.width * fraction
                    drawLine(gridColor, Offset(x, plot.top), Offset(x, plot.bottom), strokeWidth = 1f)
                    drawText(
                        textMeasurer,
                        compactNumber(value),
                        Offset(x - 16f, plot.bottom + 8f),
                        labelStyle,
                    )
                } else {
                    val y = plot.bottom - plot.height * fraction
                    drawLine(gridColor, Offset(plot.left, y), Offset(plot.right, y), strokeWidth = 1f)
                    drawText(
                        textMeasurer = textMeasurer,
                        text = compactNumber(value),
                        topLeft = Offset(4f, y - 8f),
                        style = labelStyle,
                    )
                }
            }
            drawLine(axisColor, Offset(plot.left, plot.top), Offset(plot.left, plot.bottom), strokeWidth = 1.5f)
            drawLine(axisColor, Offset(plot.left, plot.bottom), Offset(plot.right, plot.bottom), strokeWidth = 1.5f)

            when (preview.chartType) {
                ChartType.Bar, ChartType.Histogram -> geometry.regions.forEach { region ->
                    val mark = preview.marks.first { it.id == region.markId }
                    val color = VisualizationSeriesPalette[seriesIndex[mark.seriesKey]?.rem(VisualizationSeriesPalette.size) ?: 0]
                    drawRect(color, region.bounds.topLeft, region.bounds.size)
                    if (hovered?.id == mark.id) drawRect(labelColor, region.bounds.topLeft, region.bounds.size, style = Stroke(2f))
                    if (config.showLabels && region.bounds.height > 16f) {
                        drawText(textMeasurer, mark.formattedY, region.bounds.topLeft + Offset(2f, 1f), labelStyle)
                    }
                }
                ChartType.Line -> {
                    preview.marks.groupBy { it.seriesKey }.forEach { (key, marks) ->
                        val color = VisualizationSeriesPalette[seriesIndex[key]?.rem(VisualizationSeriesPalette.size) ?: 0]
                        marks.zipWithNext().forEach { (left, right) ->
                            drawLine(color, geometry.points.getValue(left.id), geometry.points.getValue(right.id), strokeWidth = 3f)
                        }
                        marks.forEach { mark ->
                            val point = geometry.points.getValue(mark.id)
                            drawCircle(color, if (hovered?.id == mark.id) 7f else 5f, point)
                            drawCircle(surfaceColor, 2f, point)
                        }
                    }
                }
                ChartType.Scatter -> preview.marks.forEach { mark ->
                    val color = VisualizationSeriesPalette[seriesIndex[mark.seriesKey]?.rem(VisualizationSeriesPalette.size) ?: 0]
                    val region = geometry.regions.first { it.markId == mark.id }
                    drawCircle(color.copy(alpha = 0.82f), region.bounds.width / 2f, region.bounds.center)
                    if (hovered?.id == mark.id) drawCircle(labelColor, region.bounds.width / 2f + 2f, region.bounds.center, style = Stroke(2f))
                }
                ChartType.Kpi, ChartType.Auto, null -> Unit
            }

            val categoryMarks = preview.marks.distinctBy { it.xKey }
            if (horizontalBars) {
                categoryMarks.forEach { mark ->
                    val y = geometry.points[mark.id]?.y ?: return@forEach
                    drawText(
                        textMeasurer,
                        mark.xLabel.take(10),
                        Offset(4f, y - 8f),
                        labelStyle,
                    )
                }
            } else {
                val maxLabels = (geometry.plot.width / 100f).toInt().coerceAtLeast(2)
                val step = kotlin.math.ceil(categoryMarks.size.toDouble() / maxLabels).toInt().coerceAtLeast(1)
                categoryMarks.filterIndexed { index, _ -> index % step == 0 }.forEach { mark ->
                    val x = geometry.points[mark.id]?.x ?: return@forEach
                    drawText(
                        textMeasurer,
                        mark.xLabel.take(12),
                        Offset((x - 30f).coerceAtLeast(geometry.plot.left), geometry.plot.bottom + 8f),
                        labelStyle,
                    )
                }
            }
        }
        hovered?.let { mark ->
            Surface(
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                color = MaterialTheme.colorScheme.inverseSurface,
                shape = RoundedCornerShape(3.dp),
                shadowElevation = 4.dp,
            ) {
                Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                    if (mark.xLabel.isNotBlank()) Text(mark.xLabel, color = MaterialTheme.colorScheme.inverseOnSurface, fontWeight = FontWeight.SemiBold)
                    Text("${mark.seriesLabel}: ${mark.formattedY}", color = MaterialTheme.colorScheme.inverseOnSurface, style = DataMono)
                    Text(
                        "${mark.sourceRowIndices.size} contributing row${if (mark.sourceRowIndices.size == 1) "" else "s"}",
                        color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

private fun compactNumber(value: Double): String = when {
    abs(value) >= 1_000_000 -> "%.1fM".format(value / 1_000_000)
    abs(value) >= 1_000 -> "%.1fK".format(value / 1_000)
    abs(value) >= 10 -> "%.0f".format(value)
    else -> "%.2f".format(value).trimEnd('0').trimEnd('.')
}
