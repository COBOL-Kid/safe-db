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
import com.safedb.explore.BarOrientation
import com.safedb.explore.ChartType
import com.safedb.explore.VisualizationConfig
import com.safedb.explore.VisualizationMark
import com.safedb.explore.VisualizationPreview
import com.safedb.ui.chart.CategoryLabelEntry
import com.safedb.ui.chart.NiceValueAxis
import com.safedb.ui.chart.PlotInsets
import com.safedb.ui.chart.barValueLabelText
import com.safedb.ui.chart.categoryLabelStep
import com.safedb.ui.chart.clampLabelX
import com.safedb.ui.chart.compactNumber
import com.safedb.ui.chart.contrastingBarLabelColor
import com.safedb.ui.chart.layoutHorizontalCategoryLabels
import com.safedb.ui.chart.legendSeriesLabel
import com.safedb.ui.chart.tooltipAlignsToStart
import com.safedb.ui.chart.valueTickMaxWidth
import com.safedb.ui.chart.visualizationGeometry
import com.safedb.ui.chart.visualizationSecondaryValueAxis
import com.safedb.ui.chart.visualizationValueAxis
import com.safedb.ui.theme.DataMono
import com.safedb.ui.theme.SafeDbTheme
import kotlin.math.max

private const val LegendEntryLimit = 12

private enum class ValueAxisSide {
    Left,
    Right,
    Top,
    Bottom,
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
                val horizontalBars =
                    preview.chartType == ChartType.Bar &&
                        config.barOrientation == BarOrientation.Horizontal
                VisualizationLegend(
                    preview = preview,
                    horizontalBars = horizontalBars,
                    modifier = Modifier.padding(top = 8.dp),
                )
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
private fun VisualizationLegend(
    preview: VisualizationPreview,
    horizontalBars: Boolean,
    modifier: Modifier = Modifier,
) {
    val seriesPalette = SafeDbTheme.colors.series
    // Cap the rows so a high-cardinality series field cannot squeeze the plot to nothing.
    val shown = preview.series.take(LegendEntryLimit)
    val labelMaxWidth = if (preview.hasSecondaryAxis) 200.dp else 160.dp
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
                            .background(seriesPalette[index % seriesPalette.size], CircleShape),
                )
                Text(
                    legendSeriesLabel(series, preview.hasSecondaryAxis, horizontalBars),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 5.dp).widthIn(max = labelMaxWidth),
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
    var pointerX by remember { mutableStateOf(0f) }
    // Default cache of 8 thrashes once a chart has more than a handful of distinct labels.
    val textMeasurer = rememberTextMeasurer(cacheSize = 64)
    val axisColor = MaterialTheme.colorScheme.outline
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val surfaceColor = MaterialTheme.colorScheme.surface
    val seriesPalette = SafeDbTheme.colors.series
    val labelStyle = DataMono.copy(color = labelColor)
    val seriesIndex =
        remember(preview) {
            preview.series.mapIndexed { index, series -> series.key to index }.toMap()
        }
    val marksById = remember(preview) { preview.marks.associateBy { it.id } }
    val marksBySeries = remember(preview) { preview.marks.groupBy { it.seriesKey } }
    val categoryMarks = remember(preview) { preview.marks.distinctBy { it.xKey } }
    val horizontalBars =
        preview.chartType == ChartType.Bar && config.barOrientation == BarOrientation.Horizontal
    val density = LocalDensity.current
    val gapPx = with(density) { 6.dp.toPx() }
    val minGutter = with(density) { 44.dp.toPx() }
    val maxGutter = with(density) { 180.dp.toPx() }
    val categoryLabelWidths =
        remember(preview, labelStyle, textMeasurer) {
            categoryMarks.associate { mark ->
                mark.xKey to textMeasurer.measure(mark.xLabel, labelStyle).size.width.toFloat()
            }
        }

    // Gutters are derived from measured label sizes, not fixed guesses, so nothing is clipped.
    // Deliberately independent of the canvas size to avoid a measure/layout feedback loop.
    val insets =
        remember(
            preview,
            config,
            labelStyle,
            textMeasurer,
            gapPx,
            horizontalBars,
            categoryLabelWidths,
            minGutter,
            maxGutter,
        ) {
            val valueAxis = visualizationValueAxis(preview, config)
            val secondaryAxis = visualizationSecondaryValueAxis(preview, config)
            val ticks =
                valueAxis.ticks.map { value ->
                    textMeasurer.measure(compactNumber(value), labelStyle).size
                }
            val secondaryTicks =
                secondaryAxis
                    ?.ticks
                    ?.map { value -> textMeasurer.measure(compactNumber(value), labelStyle).size }
                    .orEmpty()
            val tickWidth = ticks.maxOf { it.width }.toFloat()
            val secondaryTickWidth = secondaryTicks.maxOfOrNull { it.width }?.toFloat() ?: 0f
            val lineHeight = ticks.maxOf { it.height }.toFloat()
            val bottom = lineHeight + gapPx * 2f
            val top =
                if (horizontalBars && secondaryAxis != null) {
                    lineHeight + gapPx * 2f
                } else {
                    lineHeight / 2f + gapPx
                }
            // Scatter marks keep sample order, so the right edge belongs to the max X value.
            val lastCategory =
                if (preview.chartType == ChartType.Scatter)
                    preview.marks.maxByOrNull { it.xValue ?: Double.NEGATIVE_INFINITY }
                else categoryMarks.lastOrNull()
            val lastCategoryWidth = lastCategory?.let { categoryLabelWidths[it.xKey] ?: 0f } ?: 0f
            if (horizontalBars) {
                val category = categoryLabelWidths.values.maxOrNull() ?: 0f
                val edgeTickWidth = max(tickWidth, secondaryTickWidth)
                PlotInsets(
                    left =
                        max(category + gapPx * 2f, edgeTickWidth / 2f + gapPx)
                            .coerceIn(minGutter, maxGutter),
                    top = top,
                    right = max(28f, edgeTickWidth / 2f + gapPx),
                    bottom = bottom,
                )
            } else {
                val defaultRight = max(28f, lastCategoryWidth / 2f + gapPx).coerceAtMost(maxGutter)
                val right =
                    if (secondaryAxis != null) {
                        max(defaultRight, secondaryTickWidth + gapPx * 2f)
                            .coerceIn(minGutter, maxGutter)
                    } else {
                        defaultRight
                    }
                PlotInsets(
                    left = (tickWidth + gapPx * 2f).coerceIn(minGutter, maxGutter),
                    top = top,
                    right = right,
                    bottom = bottom,
                )
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
                        if (position != null) pointerX = position.x
                        hovered =
                            position
                                ?.let { pointer ->
                                    geometry.regions.firstOrNull { it.contains(pointer) }?.markId
                                }
                                ?.let(marksById::get)
                    }
                    .onPointerEvent(PointerEventType.Exit) { hovered = null }
                    .pointerInput(geometry, preview) {
                        detectTapGestures { offset ->
                            geometry.regions
                                .firstOrNull { it.contains(offset) }
                                ?.let { onMarkClick(it.markId) }
                        }
                    },
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
            fun clampX(x: Float, labelWidth: Float, minX: Float = 0f): Float =
                clampLabelX(x, labelWidth, minX, canvasWidth)

            fun drawValueTicks(axis: NiceValueAxis, side: ValueAxisSide, drawGrid: Boolean) {
                val horizontalAxis = side == ValueAxisSide.Bottom || side == ValueAxisSide.Top
                val maxLabelWidth =
                    if (side == ValueAxisSide.Right) max(canvasWidth - plot.right - gapPx, 0f)
                    else valueTickMaxWidth(horizontalAxis, plot, gapPx)
                axis.ticks.forEach { value ->
                    val fraction = axis.fraction(value).toFloat().coerceIn(0f, 1f)
                    val label = fitted(compactNumber(value), maxLabelWidth)
                    if (horizontalAxis) {
                        val x = plot.left + plot.width * fraction
                        if (drawGrid) {
                            drawLine(
                                gridColor,
                                Offset(x, plot.top),
                                Offset(x, plot.bottom),
                                strokeWidth = 1f,
                            )
                        }
                        val labelY =
                            if (side == ValueAxisSide.Top)
                                max(0f, plot.top - gapPx - label.size.height)
                            else plot.bottom + gapPx
                        drawText(
                            label,
                            topLeft =
                                Offset(
                                    clampX(x - label.size.width / 2f, label.size.width.toFloat()),
                                    labelY,
                                ),
                        )
                    } else {
                        val y = plot.bottom - plot.height * fraction
                        if (drawGrid) {
                            drawLine(
                                gridColor,
                                Offset(plot.left, y),
                                Offset(plot.right, y),
                                strokeWidth = 1f,
                            )
                        }
                        val labelX =
                            if (side == ValueAxisSide.Right) plot.right + gapPx
                            else max(0f, plot.left - gapPx - label.size.width)
                        drawText(label, topLeft = Offset(labelX, y - label.size.height / 2f))
                    }
                }
            }

            clipRect {
                drawValueTicks(
                    geometry.valueAxis,
                    if (horizontalBars) ValueAxisSide.Bottom else ValueAxisSide.Left,
                    drawGrid = true,
                )
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
                geometry.secondaryValueAxis
                    ?.takeIf { it.ticks.isNotEmpty() }
                    ?.let { secondaryAxis ->
                        if (horizontalBars) {
                            drawLine(
                                axisColor,
                                Offset(plot.left, plot.top),
                                Offset(plot.right, plot.top),
                                strokeWidth = 1.5f,
                            )
                            drawValueTicks(secondaryAxis, ValueAxisSide.Top, drawGrid = false)
                        } else {
                            drawLine(
                                axisColor,
                                Offset(plot.right, plot.top),
                                Offset(plot.right, plot.bottom),
                                strokeWidth = 1.5f,
                            )
                            drawValueTicks(secondaryAxis, ValueAxisSide.Right, drawGrid = false)
                        }
                    }

                when (preview.chartType) {
                    ChartType.Bar,
                    ChartType.Histogram ->
                        geometry.regions.forEach { region ->
                            val mark = marksById[region.markId] ?: return@forEach
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
                                val labelText = barValueLabelText(mark, config)
                                val label =
                                    textMeasurer.measure(
                                        labelText,
                                        DataMono.copy(color = contrastingBarLabelColor(color)),
                                    )
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
                        marksBySeries.forEach { (key, marks) ->
                            val color =
                                seriesPalette[seriesIndex[key]?.rem(seriesPalette.size) ?: 0]
                            marks.zipWithNext().forEach { (left, right) ->
                                // The first frame draws before the measured size feeds back
                                // into geometry, so points can be missing.
                                val start = geometry.points[left.id] ?: return@forEach
                                val end = geometry.points[right.id] ?: return@forEach
                                drawLine(color, start, end, strokeWidth = 3f)
                            }
                            marks.forEach { mark ->
                                val point = geometry.points[mark.id] ?: return@forEach
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
                        geometry.regions.forEach { region ->
                            val mark = marksById[region.markId] ?: return@forEach
                            val color =
                                seriesPalette[
                                    seriesIndex[mark.seriesKey]?.rem(seriesPalette.size) ?: 0]
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

                if (categoryMarks.isEmpty()) return@clipRect
                if (horizontalBars) {
                    val bandHeight = plot.height / categoryMarks.size.coerceAtLeast(1)
                    val lineHeight = textMeasurer.measure("0", labelStyle).size.height.toFloat()
                    val step = categoryLabelStep(lineHeight + 2f, bandHeight)
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
                    val entries = categoryMarks.mapNotNull { mark ->
                        geometry.categoryCenters[mark.xKey]?.let { center ->
                            CategoryLabelEntry(
                                xKey = mark.xKey,
                                text = mark.xLabel,
                                center = center,
                                naturalWidth = categoryLabelWidths[mark.xKey] ?: 0f,
                            )
                        }
                    }
                    layoutHorizontalCategoryLabels(
                            entries = entries,
                            plotLeft = plot.left,
                            plotWidth = plot.width,
                            canvasWidth = canvasWidth,
                            gapPx = gapPx,
                            measureFitted = ::fitted,
                            measuredWidth = { it.size.width.toFloat() },
                        )
                        .forEach { placed ->
                            drawText(
                                placed.measured,
                                topLeft = Offset(placed.drawX, plot.bottom + gapPx),
                            )
                        }
                }
            }
        }
        hovered?.let { mark ->
            // Sit on the opposite side of the plot from the pointer so it never covers the mark.
            val alignment =
                if (tooltipAlignsToStart(pointerX, geometry.plot.center.x)) Alignment.TopStart
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
