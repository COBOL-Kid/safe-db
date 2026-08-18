package com.safedb.export

import com.safedb.explore.BarOrientation
import com.safedb.explore.ChartType
import com.safedb.explore.ExplorePreviewResult
import com.safedb.explore.ExploreSession
import com.safedb.explore.PivotRowKind
import com.safedb.explore.VisualizationConfig
import com.safedb.explore.VisualizationMark
import com.safedb.explore.VisualizationPreview
import com.safedb.explore.WorksheetRowKind
import com.safedb.explore.WorksheetTableProjection
import com.safedb.explore.displayColumnLabels
import com.safedb.explore.pivotCellLineageKey
import com.safedb.model.QueryResult
import com.safedb.model.ResultCell
import com.safedb.ui.PlotInsets
import com.safedb.ui.categoryLabelIndices
import com.safedb.ui.compactNumber
import com.safedb.ui.visualizationGeometry
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Data embedded in the exported document. The DTOs are flattened for the report's JavaScript:
// drill indices, header spans, and chart geometry are precomputed here so the script only
// instantiates what it is given.
@Serializable
internal data class HtmlReport(
    val meta: HtmlReportMeta,
    val source: HtmlSourceTable? = null,
    val pivot: HtmlPivotSection? = null,
    val table: HtmlTableSection? = null,
    val chart: HtmlChartSection? = null,
)

@Serializable
internal data class HtmlReportMeta(
    val title: String,
    val connectionLabel: String,
    val mode: String,
    val sampleRowCount: Int,
    val sampleTruncated: Boolean,
    val sampledAt: String,
    val generatedAt: String,
    val warnings: List<String>,
)

@Serializable
internal data class HtmlCell(
    val t: String,
    val n: Double? = null,
    val d: List<Int>? = null,
)

@Serializable
internal data class HtmlSourceTable(val columns: List<String>, val rows: List<List<String>>)

// start/span are in value-column units so header rows that do not tile every leaf can still be
// positioned with gap fillers.
@Serializable
internal data class HtmlHeaderCell(
    val label: String,
    val start: Int,
    val span: Int,
    val isTotal: Boolean = false,
)

@Serializable
internal data class HtmlPivotRow(
    val label: String? = null,
    val depth: Int = 0,
    val kind: String = "leaf",
    val cells: List<HtmlCell>,
)

@Serializable
internal data class HtmlPivotSection(
    val headerRows: List<List<HtmlHeaderCell>>,
    val leafHeaders: List<HtmlHeaderCell>,
    val hasRowLabels: Boolean,
    val rows: List<HtmlPivotRow>,
    val overflowMessage: String? = null,
)

@Serializable internal data class HtmlTableColumn(val label: String, val numeric: Boolean = false)

@Serializable
internal data class HtmlTableRow(
    val cells: List<HtmlCell>,
    val kind: String = "detail",
    val depth: Int = 0,
    val d: List<Int>? = null,
)

// Fields that JS reads unconditionally have no defaults: the compact serializer omits
// default-valued fields, and a missing list would crash the script.
@Serializable
internal data class HtmlTableSection(
    val columns: List<HtmlTableColumn>,
    val rows: List<HtmlTableRow>,
    val sortable: Boolean,
)

@Serializable
internal data class HtmlChartShape(
    val kind: String,
    val x: Double? = null,
    val y: Double? = null,
    val w: Double? = null,
    val h: Double? = null,
    val cx: Double? = null,
    val cy: Double? = null,
    val r: Double? = null,
    val points: String? = null,
    val series: Int = 0,
    val tooltip: String? = null,
    val d: List<Int>? = null,
)

@Serializable internal data class HtmlAxisTick(val pos: Double, val label: String)

@Serializable
internal data class HtmlKpi(
    val value: String,
    val label: String,
    val sublabel: String,
    val d: List<Int>? = null,
)

@Serializable
internal data class HtmlChartSection(
    val title: String,
    val width: Int,
    val height: Int,
    val plot: List<Double>,
    val horizontal: Boolean,
    val shapes: List<HtmlChartShape>,
    val valueTicks: List<HtmlAxisTick>,
    val categoryTicks: List<HtmlAxisTick>,
    val legend: List<String>,
    val legendMore: Int,
    val kpis: List<HtmlKpi>,
)

internal const val CHART_EXPORT_WIDTH = 960
internal const val CHART_EXPORT_HEIGHT = 420

private val reportJson = Json

fun writePivotReportHtml(
    session: ExploreSession,
    preview: ExplorePreviewResult,
    output: OutputStream,
) {
    output.write(
        renderHtmlDocument(buildPivotReport(session, preview)).toByteArray(StandardCharsets.UTF_8)
    )
}

fun writeWorksheetReportHtml(
    session: ExploreSession,
    projection: WorksheetTableProjection,
    warnings: List<String>,
    output: OutputStream,
) {
    output.write(
        renderHtmlDocument(buildWorksheetReport(session, projection, warnings))
            .toByteArray(StandardCharsets.UTF_8)
    )
}

fun writeVisualizationReportHtml(
    session: ExploreSession,
    preview: VisualizationPreview,
    config: VisualizationConfig,
    output: OutputStream,
) {
    output.write(
        renderHtmlDocument(buildVisualizationReport(session, preview, config))
            .toByteArray(StandardCharsets.UTF_8)
    )
}

internal fun buildPivotReport(session: ExploreSession, preview: ExplorePreviewResult): HtmlReport {
    val layout = preview.layout
    val result = preview.result
    val meta = reportMeta(session, "pivot", preview.warnings)
    val aligned =
        layout.formattedRows.size == result.rows.size && layout.rowEntries.size == result.rows.size
    if (!aligned) return HtmlReport(meta = meta, table = tableSectionFrom(result))

    val measures = layout.measures
    val hasRowLabels = layout.rowDimensions.isNotEmpty()
    val offset = if (hasRowLabels) 1 else 0
    val headerRows =
        layout.columnHeaderRows.map { row ->
            row.map { header ->
                HtmlHeaderCell(
                    label = header.label,
                    start = header.startLeafIndex * measures.size,
                    span = header.leafSpan * measures.size,
                    isTotal = header.isTotal,
                )
            }
        }
    val leafHeaders =
        layout.columnLeaves.flatMapIndexed { leafIndex, leaf ->
            measures.mapIndexed { measureIndex, measure ->
                HtmlHeaderCell(
                    label = measure.label,
                    start = leafIndex * measures.size + measureIndex,
                    span = 1,
                    isTotal = leaf.isSubtotal || leaf.isGrandTotal,
                )
            }
        }
    val rows =
        result.rows.mapIndexed { rowIndex, cells ->
            val entry = layout.rowEntries[rowIndex]
            val formatted = layout.formattedRows[rowIndex]
            val dataCells =
                (offset until cells.size).map { column ->
                    val leaf = layout.columnLeaves.getOrNull((column - offset) / measures.size)
                    val measure = measures.getOrNull((column - offset) % measures.size)
                    // Group rows carry no values, mirroring the in-app drillable rule.
                    val drill =
                        if (entry.kind != PivotRowKind.Group && leaf != null && measure != null) {
                            layout.cellLineage[
                                    pivotCellLineageKey(entry.pathKey, leaf.pathKey, measure.alias)]
                                ?.takeIf { it.isNotEmpty() }
                        } else {
                            null
                        }
                    HtmlCell(
                        t =
                            if (cells[column] is ResultCell.Null) ""
                            else formatted.getOrElse(column) { "" },
                        d = drill,
                    )
                }
            HtmlPivotRow(
                label = if (hasRowLabels) entry.label else null,
                depth = entry.depth,
                kind = entry.kind.name.lowercase(),
                cells = dataCells,
            )
        }
    return HtmlReport(
        meta = meta,
        source = buildSourceTable(session),
        pivot =
            HtmlPivotSection(
                headerRows = headerRows,
                leafHeaders = leafHeaders,
                hasRowLabels = hasRowLabels,
                rows = rows,
                overflowMessage = layout.overflowMessage,
            ),
    )
}

internal fun buildWorksheetReport(
    session: ExploreSession,
    projection: WorksheetTableProjection,
    warnings: List<String>,
): HtmlReport {
    val rows =
        projection.rows.map { row ->
            val cells = buildList {
                if (projection.hasRowLabels) add(HtmlCell(row.rowLabel.orEmpty()))
                row.cells.forEach { cell ->
                    val (text, numeric) =
                        cell.error?.let { "Error: $it" to null } ?: cellToHtmlDisplay(cell.value)
                    add(HtmlCell(text, numeric))
                }
            }
            HtmlTableRow(
                cells = cells,
                kind =
                    when (row.kind) {
                        WorksheetRowKind.Detail -> "detail"
                        WorksheetRowKind.Group -> "group"
                        WorksheetRowKind.GrandTotal -> "total"
                    },
                depth = row.depth,
                d =
                    row.sourceRowIndex
                        ?.takeIf { row.kind == WorksheetRowKind.Detail }
                        ?.let(::listOf),
            )
        }
    val columns = buildList {
        if (projection.hasRowLabels) add("Group")
        projection.columns.mapTo(this) { it.label }
    }
    val flat = rows.all { it.kind == "detail" }
    return HtmlReport(
        meta = reportMeta(session, "worksheet", warnings),
        source = buildSourceTable(session),
        table =
            HtmlTableSection(
                columns = columnsWithNumericFlags(columns, rows),
                rows = rows,
                // Sorting a grouped worksheet would jumble rows out of their groups.
                sortable = flat,
            ),
    )
}

internal fun buildVisualizationReport(
    session: ExploreSession,
    preview: VisualizationPreview,
    config: VisualizationConfig,
): HtmlReport =
    HtmlReport(
        meta = reportMeta(session, "visualization", preview.warnings),
        source = buildSourceTable(session),
        chart = buildChartSection(preview, config),
        table = preview.exportResult?.let(::tableSectionFrom),
    )

internal fun buildSourceTable(session: ExploreSession): HtmlSourceTable {
    val labels = displayColumnLabels(session.sample.columns, session.baseSpec.tables)
    return HtmlSourceTable(
        columns = session.sample.columns.map { labels.getValue(it.name) },
        rows = session.sample.rows.map { row -> row.map { cellToHtmlDisplay(it).first } },
    )
}

internal fun buildChartSection(
    preview: VisualizationPreview,
    config: VisualizationConfig,
): HtmlChartSection {
    val width = CHART_EXPORT_WIDTH
    val height = CHART_EXPORT_HEIGHT
    if (preview.chartType == ChartType.Kpi) {
        val mark = preview.marks.first()
        return HtmlChartSection(
            title = preview.title,
            width = width,
            height = height,
            plot = emptyList(),
            horizontal = false,
            shapes = emptyList(),
            valueTicks = emptyList(),
            categoryTicks = emptyList(),
            legend = emptyList(),
            legendMore = 0,
            kpis =
                listOf(
                    HtmlKpi(
                        value = mark.formattedY,
                        label = mark.measureLabel,
                        sublabel = "${mark.sourceRowIndices.size} contributing rows",
                        d = mark.sourceRowIndices.takeIf { it.isNotEmpty() },
                    )
                ),
        )
    }

    val horizontal =
        preview.chartType == ChartType.Bar && config.barOrientation == BarOrientation.Horizontal
    val maxCategoryExtent =
        preview.marks.distinctBy { it.xKey }.maxOfOrNull { estimatedLabelWidth(it.xLabel) } ?: 0f
    val insets =
        if (horizontal) {
            PlotInsets(left = (maxCategoryExtent + 12f).coerceIn(44f, 180f))
        } else {
            PlotInsets()
        }
    val geometry = visualizationGeometry(preview, config, width.toFloat(), height.toFloat(), insets)
    val plot = geometry.plot
    val multiSeries = preview.series.size > 1
    val seriesIndex = preview.series.mapIndexed { index, series -> series.key to index }.toMap()

    val shapes = mutableListOf<HtmlChartShape>()
    when (preview.chartType) {
        ChartType.Bar,
        ChartType.Histogram ->
            geometry.regions.mapTo(shapes) { region ->
                val mark = preview.marks.first { it.id == region.markId }
                HtmlChartShape(
                    kind = "rect",
                    x = region.bounds.left.rounded(),
                    y = region.bounds.top.rounded(),
                    w = region.bounds.width.rounded(),
                    h = region.bounds.height.rounded(),
                    series = seriesIndex[mark.seriesKey] ?: 0,
                    tooltip = markTooltip(mark, multiSeries),
                    d = mark.sourceRowIndices.takeIf { it.isNotEmpty() },
                )
            }
        ChartType.Line -> {
            preview.marks
                .groupBy { it.seriesKey }
                .forEach { (key, marks) ->
                    val points =
                        marks
                            .mapNotNull { geometry.points[it.id] }
                            .joinToString(" ") { "${it.x.rounded()},${it.y.rounded()}" }
                    shapes +=
                        HtmlChartShape(
                            kind = "polyline",
                            points = points,
                            series = seriesIndex[key] ?: 0,
                        )
                    marks.mapNotNullTo(shapes) { mark ->
                        val point = geometry.points[mark.id] ?: return@mapNotNullTo null
                        HtmlChartShape(
                            kind = "circle",
                            cx = point.x.rounded(),
                            cy = point.y.rounded(),
                            r = 4.5,
                            series = seriesIndex[key] ?: 0,
                            tooltip = markTooltip(mark, multiSeries),
                            d = mark.sourceRowIndices.takeIf { it.isNotEmpty() },
                        )
                    }
                }
        }
        ChartType.Scatter ->
            geometry.regions.mapTo(shapes) { region ->
                val mark = preview.marks.first { it.id == region.markId }
                HtmlChartShape(
                    kind = "circle",
                    cx = region.bounds.center.x.rounded(),
                    cy = region.bounds.center.y.rounded(),
                    r = (region.bounds.width / 2f).rounded(),
                    series = seriesIndex[mark.seriesKey] ?: 0,
                    tooltip = markTooltip(mark, multiSeries),
                    d = mark.sourceRowIndices.takeIf { it.isNotEmpty() },
                )
            }
        ChartType.Kpi,
        ChartType.Auto,
        null -> Unit
    }

    val valueTicks =
        (0..4).map { tick ->
            val fraction = tick / 4f
            val value = geometry.yMin + (geometry.yMax - geometry.yMin) * fraction
            HtmlAxisTick(
                pos =
                    if (horizontal) (plot.left + plot.width * fraction).rounded()
                    else (plot.bottom - plot.height * fraction).rounded(),
                label = compactNumber(value),
            )
        }
    val categories = preview.marks.distinctBy { it.xKey }
    val centered =
        categories
            .mapNotNull { mark ->
                geometry.categoryCenters[mark.xKey]?.let { center -> center to mark.xLabel }
            }
            .sortedBy { it.first }
    val requiredGap =
        if (horizontal) 16f
        else (centered.maxOfOrNull { estimatedLabelWidth(it.second) } ?: 0f) + 8f
    val categoryTicks =
        categoryLabelIndices(centered.map { it.first }, requiredGap).map { index ->
            HtmlAxisTick(
                pos = centered[index].first.rounded(),
                label =
                    centered[index].second.let { if (it.length > 24) it.take(23) + "…" else it },
            )
        }
    val shownLegend = if (multiSeries) preview.series.take(12) else emptyList()
    return HtmlChartSection(
        title = preview.title,
        width = width,
        height = height,
        plot =
            listOf(
                plot.left.rounded(),
                plot.top.rounded(),
                plot.right.rounded(),
                plot.bottom.rounded(),
            ),
        horizontal = horizontal,
        shapes = shapes,
        valueTicks = valueTicks,
        categoryTicks = categoryTicks,
        legend = shownLegend.map { it.label },
        legendMore = if (multiSeries) preview.series.size - shownLegend.size else 0,
        kpis = emptyList(),
    )
}

internal fun tableSectionFrom(result: QueryResult): HtmlTableSection {
    val rows =
        result.rows.map { row ->
            HtmlTableRow(
                cells =
                    row.map { cell ->
                        val (text, numeric) = cellToHtmlDisplay(cell)
                        HtmlCell(text, numeric)
                    }
            )
        }
    return HtmlTableSection(
        columns = columnsWithNumericFlags(result.columns.map { it.name }, rows),
        rows = rows,
        sortable = true,
    )
}

private fun columnsWithNumericFlags(
    labels: List<String>,
    rows: List<HtmlTableRow>,
): List<HtmlTableColumn> = labels.mapIndexed { index, label ->
    val cells = rows.mapNotNull { it.cells.getOrNull(index) }.filter { it.t.isNotEmpty() }
    HtmlTableColumn(
        label = label,
        numeric = cells.isNotEmpty() && cells.all { it.n != null },
    )
}

private fun reportMeta(
    session: ExploreSession,
    mode: String,
    warnings: List<String>,
): HtmlReportMeta =
    HtmlReportMeta(
        title = "${session.connectionLabel} · ${mode.replaceFirstChar { it.uppercase() }} report",
        connectionLabel = session.connectionLabel,
        mode = mode,
        sampleRowCount = session.sample.rowCount,
        sampleTruncated = session.sample.truncated,
        sampledAt = formatTimestamp(Instant.ofEpochSecond(session.sampleFetchedAtEpochSec)),
        generatedAt = formatTimestamp(Instant.now()),
        warnings = warnings,
    )

private val timestampFormat =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT).withZone(ZoneId.systemDefault())

private fun formatTimestamp(instant: Instant): String = timestampFormat.format(instant)

private fun cellToHtmlDisplay(cell: ResultCell): Pair<String, Double?> =
    when (cell) {
        is ResultCell.Null -> "" to null
        is ResultCell.BoolCell -> cell.value.toString() to null
        is ResultCell.IntegerCell -> cell.value.toString() to cell.value.toDouble()
        is ResultCell.FloatCell -> cell.value.toString() to cell.value
        is ResultCell.TextCell -> cell.value.text to null
        // Base64 payloads are useless in a report and can be huge; a placeholder keeps the
        // document light. This intentionally diverges from the CSV export.
        is ResultCell.BinaryCell -> "(binary)" to null
    }

private fun markTooltip(mark: VisualizationMark, multiSeries: Boolean): String = buildString {
    append(mark.xLabel)
    append('\n')
    append(if (multiSeries) mark.seriesLabel else mark.measureLabel)
    append(": ")
    append(mark.formattedY)
    append('\n')
    val rows = mark.sourceRowIndices.size
    append("$rows contributing row${if (rows == 1) "" else "s"}")
}

private fun estimatedLabelWidth(label: String): Float = label.length * 7f + 4f

private fun Float.rounded(): Double = (this * 10f).roundToInt() / 10.0

internal fun String.htmlEscaped(): String =
    replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

internal fun renderHtmlDocument(report: HtmlReport): String {
    // All report data lives in the JSON island and reaches the page through
    // createElement/textContent in REPORT_JS, so markup escaping is only needed for the title.
    // Escaping "<" closes the script-injection door (</script>, <!--) in one move: it only
    // occurs inside JSON string literals, where < is a valid escape.
    val json = reportJson.encodeToString(HtmlReport.serializer(), report).replace("<", "\\u003c")
    return buildString {
        append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n")
        append("<meta charset=\"utf-8\">\n")
        append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
        append("<title>").append(report.meta.title.htmlEscaped()).append("</title>\n")
        append("<style>\n").append(REPORT_CSS).append("\n</style>\n")
        append("</head>\n<body>\n")
        append("<noscript>This report requires JavaScript.</noscript>\n")
        append("<div id=\"app\"></div>\n")
        append("<dialog id=\"drill\"></dialog>\n")
        append("<script id=\"report-data\" type=\"application/json\">")
        append(json)
        append("</script>\n")
        append("<script>\n").append(REPORT_JS).append("\n</script>\n")
        append("</body>\n</html>\n")
    }
}
