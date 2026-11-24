package com.safedb.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.geometry.Size
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
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.safedb.explore.ChartType
import com.safedb.explore.VisualizationConfig
import com.safedb.explore.VisualizationMark
import com.safedb.explore.VisualizationPreview
import com.safedb.ui.chart.CategoryLabelEntry
import com.safedb.ui.chart.LegendEntryLimit
import com.safedb.ui.chart.NiceValueAxis
import com.safedb.ui.chart.barLabelCenter
import com.safedb.ui.chart.barValueLabelText
import com.safedb.ui.chart.categoryLabelStep
import com.safedb.ui.chart.chartPlotInsets
import com.safedb.ui.chart.chooseBarLabelFontSizeSp
import com.safedb.ui.chart.clampLabelX
import com.safedb.ui.chart.compactNumber
import com.safedb.ui.chart.contrastingBarLabelColor
import com.safedb.ui.chart.layoutHorizontalCategoryLabels
import com.safedb.ui.chart.legendSeriesLabel
import com.safedb.ui.chart.tooltipAlignsToStart
import com.safedb.ui.chart.valueTickMaxWidth
import com.safedb.ui.chart.visualizationGeometry
import com.safedb.ui.theme.DataMono
import com.safedb.ui.theme.SafeDbTheme
import kotlin.math.max

private enum class ValueAxisSide {
    Left,
    Right,
    Top,
    Bottom,
}

private data class MeasuredBarLabel(val layout: TextLayoutResult, val topLeft: Offset)

private data class MeasuredValueTick(
    val layout: TextLayoutResult,
    val topLeft: Offset,
    val gridStart: Offset?,
    val gridEnd: Offset?,
)

private data class MeasuredCategoryLabel(val layout: TextLayoutResult, val topLeft: Offset)

private fun measureFittedLabel(
    textMeasurer: TextMeasurer,
    text: String,
    style: TextStyle,
    maxWidth: Float,
): TextLayoutResult =
    textMeasurer.measure(
        text,
        style,
        overflow = TextOverflow.Ellipsis,
        maxLines = 1,
        constraints = Constraints(maxWidth = maxWidth.toInt().coerceAtLeast(0)),
    )

private fun measureValueTicks(
    axis: NiceValueAxis,
    side: ValueAxisSide,
    drawGrid: Boolean,
    plot: Rect,
    canvasWidth: Float,
    gapPx: Float,
    textMeasurer: TextMeasurer,
    labelStyle: TextStyle,
): List<MeasuredValueTick> {
    val horizontalAxis = side == ValueAxisSide.Bottom || side == ValueAxisSide.Top
    val maxLabelWidth =
        if (side == ValueAxisSide.Right) max(canvasWidth - plot.right - gapPx, 0f)
        else valueTickMaxWidth(horizontalAxis, plot, gapPx)
    return axis.ticks.map { value ->
        val fraction = axis.fraction(value).toFloat().coerceIn(0f, 1f)
        val label =
            measureFittedLabel(textMeasurer, compactNumber(value), labelStyle, maxLabelWidth)
        if (horizontalAxis) {
            val x = plot.left + plot.width * fraction
            val labelY =
                if (side == ValueAxisSide.Top) max(0f, plot.top - gapPx - label.size.height)
                else plot.bottom + gapPx
            MeasuredValueTick(
                layout = label,
                topLeft =
                    Offset(
                        clampLabelX(
                            x - label.size.width / 2f,
                            label.size.width.toFloat(),
                            0f,
                            canvasWidth,
                        ),
                        labelY,
                    ),
                gridStart = if (drawGrid) Offset(x, plot.top) else null,
                gridEnd = if (drawGrid) Offset(x, plot.bottom) else null,
            )
        } else {
            val y = plot.bottom - plot.height * fraction
            val labelX =
                if (side == ValueAxisSide.Right) plot.right + gapPx
                else max(0f, plot.left - gapPx - label.size.width)
            MeasuredValueTick(
                layout = label,
                topLeft = Offset(labelX, y - label.size.height / 2f),
                gridStart = if (drawGrid) Offset(plot.left, y) else null,
                gridEnd = if (drawGrid) Offset(plot.right, y) else null,
            )
        }
    }
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
                VisualizationLegend(
                    preview = preview,
                    horizontalBars = config.isHorizontalBar(preview.chartType),
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
    var hovered by remember(preview) { mutableStateOf<VisualizationMark?>(null) }
    var tooltipAtStart by remember { mutableStateOf(false) }
    // Default cache of 8 thrashes once a chart has more than a handful of distinct labels.
    val textMeasurer = rememberTextMeasurer(cacheSize = 128)
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
    val horizontalBars = config.isHorizontalBar(preview.chartType)
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

    val labelLineHeight =
        remember(labelStyle, textMeasurer) {
            textMeasurer.measure("0", labelStyle).size.height.toFloat()
        }

    // Gutters are derived from measured label sizes, not fixed guesses, so nothing is clipped.
    // Deliberately independent of the canvas size to avoid a measure/layout feedback loop.
    val insets =
        remember(
            preview,
            config,
            labelStyle,
            textMeasurer,
            labelLineHeight,
            gapPx,
            minGutter,
            maxGutter,
        ) {
            chartPlotInsets(
                preview = preview,
                config = config,
                lineHeight = labelLineHeight,
                gapPx = gapPx,
                minGutter = minGutter,
                maxGutter = maxGutter,
                measureLabelWidth = { text ->
                    textMeasurer.measure(text, labelStyle).size.width.toFloat()
                },
            )
        }

    BoxWithConstraints(modifier = modifier) {
        val canvasWidth = if (constraints.hasBoundedWidth) constraints.maxWidth.toFloat() else 0f
        val canvasHeight = if (constraints.hasBoundedHeight) constraints.maxHeight.toFloat() else 0f
        val chartSize = Size(canvasWidth, canvasHeight)
        val geometry =
            remember(preview, config, chartSize, insets) {
                visualizationGeometry(preview, config, chartSize.width, chartSize.height, insets)
            }
        val barLabels =
            remember(preview, config, geometry, horizontalBars, textMeasurer) {
                if (
                    !config.showLabels ||
                        (preview.chartType != ChartType.Bar &&
                            preview.chartType != ChartType.Histogram)
                ) {
                    emptyMap()
                } else {
                    val layoutsBySize = mutableMapOf<Float, Map<String, MeasuredBarLabel>>()
                    val barLabelFontSize =
                        chooseBarLabelFontSizeSp(maxPossible = geometry.regions.size) { candidateSp
                            ->
                            val style =
                                DataMono.copy(
                                    fontSize = candidateSp.sp,
                                    lineHeight = (candidateSp + 2f).sp,
                                )
                            val measured = buildMap {
                                geometry.regions.forEach { region ->
                                    val mark = marksById[region.markId] ?: return@forEach
                                    val layout =
                                        textMeasurer.measure(
                                            barValueLabelText(mark, config),
                                            style,
                                        )
                                    val center =
                                        barLabelCenter(
                                            bounds = region.bounds,
                                            labelWidth = layout.size.width.toFloat(),
                                            labelHeight = layout.size.height.toFloat(),
                                            horizontalBars = horizontalBars,
                                        ) ?: return@forEach
                                    put(
                                        mark.id,
                                        MeasuredBarLabel(
                                            layout = layout,
                                            topLeft =
                                                Offset(
                                                    center.x - layout.size.width / 2f,
                                                    center.y - layout.size.height / 2f,
                                                ),
                                        ),
                                    )
                                }
                            }
                            layoutsBySize[candidateSp] = measured
                            measured.size
                        }
                    layoutsBySize[barLabelFontSize].orEmpty()
                }
            }
        val primaryValueTicks =
            remember(geometry, horizontalBars, canvasWidth, gapPx, labelStyle, textMeasurer) {
                measureValueTicks(
                    axis = geometry.valueAxis,
                    side = if (horizontalBars) ValueAxisSide.Bottom else ValueAxisSide.Left,
                    drawGrid = true,
                    plot = geometry.plot,
                    canvasWidth = canvasWidth,
                    gapPx = gapPx,
                    textMeasurer = textMeasurer,
                    labelStyle = labelStyle,
                )
            }
        val secondaryValueTicks =
            remember(geometry, horizontalBars, canvasWidth, gapPx, labelStyle, textMeasurer) {
                geometry.secondaryValueAxis
                    ?.takeIf { it.ticks.isNotEmpty() }
                    ?.let { secondaryAxis ->
                        measureValueTicks(
                            axis = secondaryAxis,
                            side = if (horizontalBars) ValueAxisSide.Top else ValueAxisSide.Right,
                            drawGrid = false,
                            plot = geometry.plot,
                            canvasWidth = canvasWidth,
                            gapPx = gapPx,
                            textMeasurer = textMeasurer,
                            labelStyle = labelStyle,
                        )
                    }
                    .orEmpty()
            }
        val categoryLabels =
            remember(
                preview.chartType,
                categoryMarks,
                geometry,
                horizontalBars,
                canvasWidth,
                gapPx,
                labelLineHeight,
                categoryLabelWidths,
                labelStyle,
                textMeasurer,
            ) {
                if (categoryMarks.isEmpty()) {
                    emptyList()
                } else if (horizontalBars) {
                    val plot = geometry.plot
                    val bandHeight = plot.height / categoryMarks.size.coerceAtLeast(1)
                    val step = categoryLabelStep(labelLineHeight + 2f, bandHeight)
                    val available = plot.left - gapPx * 2f
                    categoryMarks
                        .filterIndexed { index, _ -> index % step == 0 }
                        .mapNotNull { mark ->
                            val y = geometry.categoryCenters[mark.xKey] ?: return@mapNotNull null
                            val label =
                                measureFittedLabel(textMeasurer, mark.xLabel, labelStyle, available)
                            MeasuredCategoryLabel(
                                layout = label,
                                topLeft =
                                    Offset(
                                        max(0f, plot.left - gapPx - label.size.width),
                                        y - label.size.height / 2f,
                                    ),
                            )
                        }
                } else {
                    val plot = geometry.plot
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
                            measureFitted = { text, maxWidth ->
                                measureFittedLabel(textMeasurer, text, labelStyle, maxWidth)
                            },
                            measuredWidth = { it.size.width.toFloat() },
                            evenlySpaced = preview.chartType != ChartType.Scatter,
                        )
                        .map { placed ->
                            MeasuredCategoryLabel(
                                layout = placed.measured,
                                topLeft = Offset(placed.drawX, plot.bottom + gapPx),
                            )
                        }
                }
            }

        Canvas(
            modifier =
                Modifier.fillMaxSize()
                    .onPointerEvent(PointerEventType.Move) { event ->
                        val position = event.changes.firstOrNull()?.position
                        if (position != null) {
                            tooltipAtStart =
                                tooltipAlignsToStart(position.x, geometry.plot.center.x)
                        }
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
            val plot = geometry.plot

            clipRect {
                primaryValueTicks.forEach { tick ->
                    val start = tick.gridStart
                    val end = tick.gridEnd
                    if (start != null && end != null) {
                        drawLine(gridColor, start, end, strokeWidth = 1f)
                    }
                    drawText(tick.layout, topLeft = tick.topLeft)
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
                if (secondaryValueTicks.isNotEmpty()) {
                    if (horizontalBars) {
                        drawLine(
                            axisColor,
                            Offset(plot.left, plot.top),
                            Offset(plot.right, plot.top),
                            strokeWidth = 1.5f,
                        )
                    } else {
                        drawLine(
                            axisColor,
                            Offset(plot.right, plot.top),
                            Offset(plot.right, plot.bottom),
                            strokeWidth = 1.5f,
                        )
                    }
                    secondaryValueTicks.forEach { tick ->
                        drawText(tick.layout, topLeft = tick.topLeft)
                    }
                }

                when (preview.chartType) {
                    ChartType.Bar,
                    ChartType.Histogram -> {
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
                            barLabels[mark.id]?.let { label ->
                                drawText(
                                    label.layout,
                                    color = contrastingBarLabelColor(color),
                                    topLeft = label.topLeft,
                                )
                            }
                        }
                    }
                    ChartType.Line -> {
                        marksBySeries.forEach { (key, marks) ->
                            val color =
                                seriesPalette[seriesIndex[key]?.rem(seriesPalette.size) ?: 0]
                            marks.zipWithNext().forEach { (left, right) ->
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

                categoryLabels.forEach { label -> drawText(label.layout, topLeft = label.topLeft) }
            }
        }
        hovered?.let { mark ->
            // Sit on the opposite side of the plot from the pointer so it never covers the mark.
            val alignment = if (tooltipAtStart) Alignment.TopStart else Alignment.TopEnd
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
