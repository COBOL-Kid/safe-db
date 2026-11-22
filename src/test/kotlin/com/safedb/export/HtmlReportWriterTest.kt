package com.safedb.export

import com.safedb.explore.BarOrientation
import com.safedb.explore.ChartType
import com.safedb.explore.ExploreConfig
import com.safedb.explore.ExploreSession
import com.safedb.explore.MeasureFn
import com.safedb.explore.NumberFormatKind
import com.safedb.explore.PivotDimension
import com.safedb.explore.PivotMeasure
import com.safedb.explore.PivotNumberFormat
import com.safedb.explore.ValueAxis
import com.safedb.explore.VisualizationConfig
import com.safedb.explore.VisualizationField
import com.safedb.explore.VisualizationMeasure
import com.safedb.explore.WorksheetCell
import com.safedb.explore.WorksheetDisplayColumn
import com.safedb.explore.WorksheetProjectedRow
import com.safedb.explore.WorksheetRowKind
import com.safedb.explore.WorksheetTableProjection
import com.safedb.explore.applyExplore
import com.safedb.explore.applyVisualization
import com.safedb.model.FilterGroup
import com.safedb.model.QueryResult
import com.safedb.model.QuerySpec
import com.safedb.model.ResultCell
import com.safedb.model.ResultColumn
import com.safedb.model.TableRef
import com.safedb.ui.chart.contrastingBarLabelColor
import com.safedb.ui.chart.toCssHex
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class HtmlReportWriterTest {
    @Test
    fun pivotReportBuildsHeadersRowsAndDrill() {
        val session = session()
        val preview = applyExplore(session.sample, pivotConfig())

        val report = buildPivotReport(session, preview)

        val pivot = assertNotNull(report.pivot)
        assertTrue(pivot.hasRowLabels)
        assertEquals(preview.layout.columnLeaves.size * 2, pivot.leafHeaders.size)
        assertEquals(listOf("Count", "Total amount"), pivot.leafHeaders.take(2).map { it.label })
        assertEquals(listOf("pending", "shipped", "Total"), pivot.rows.map { it.label })
        val pending = pivot.rows.first { it.label == "pending" }
        assertEquals("leaf", pending.kind)
        assertEquals(listOf(0, 1), pending.cells[0].d)
        assertEquals("2", pending.cells[0].t)
        val total = pivot.rows.first { it.label == "Total" }
        assertEquals("grandtotal", total.kind)
        assertEquals(listOf(0, 1, 2), total.cells[0].d)

        val source = assertNotNull(report.source)
        assertEquals(listOf("id", "status", "amount"), source.columns)
        assertEquals(3, source.rows.size)
    }

    @Test
    fun pivotHeaderSpansMultiplyByMeasureCount() {
        val session = session()
        val preview =
            applyExplore(
                session.sample,
                pivotConfig()
                    .copy(columnDimensions = listOf(PivotDimension(column = "t0__amount"))),
            )

        val report = buildPivotReport(session, preview)

        val pivot = assertNotNull(report.pivot)
        val headerRow = pivot.headerRows.first()
        preview.layout.columnHeaderRows.first().forEachIndexed { index, expected ->
            assertEquals(expected.leafSpan * 2, headerRow[index].span)
            assertEquals(expected.startLeafIndex * 2, headerRow[index].start)
        }
    }

    @Test
    fun hierarchicalPivotOmitsGroupRowsAndKeepsDrillOnLeaves() {
        val session = session()
        val preview =
            applyExplore(
                session.sample,
                pivotConfig()
                    .copy(
                        rowDimensions =
                            listOf(
                                PivotDimension("t0__status"),
                                PivotDimension("t0__id"),
                            ),
                        showColumnTotals = false,
                    ),
            )

        val pivot = assertNotNull(buildPivotReport(session, preview).pivot)

        assertTrue(pivot.rows.none { it.kind == "group" })
        val pendingTotal = pivot.rows.first { it.label == "pending total" }
        assertEquals("subtotal", pendingTotal.kind)
        val leaf = pivot.rows.first { it.kind == "leaf" }
        assertTrue(leaf.cells.any { it.d != null })
    }

    @Test
    fun pivotReportFallsBackToPlainTableOnLayoutMismatch() {
        val session = session()
        val preview = applyExplore(session.sample, pivotConfig())
        val broken = preview.copy(layout = preview.layout.copy(formattedRows = emptyList()))

        val report = buildPivotReport(session, broken)

        assertNull(report.pivot)
        val table = assertNotNull(report.table)
        assertEquals(preview.result.rows.size, table.rows.size)
        assertTrue(table.rows.none { it.d != null })
    }

    @Test
    fun worksheetReportMapsErrorsGroupsAndRowDrill() {
        val session = session()
        val projection =
            WorksheetTableProjection(
                resolvedColumns = emptyList(),
                columns =
                    listOf(
                        WorksheetDisplayColumn("c1", "Amount", "bigint", "t0__amount"),
                        WorksheetDisplayColumn("c2", "Doubled", "decimal", null, "calc"),
                        WorksheetDisplayColumn("c3", "Broken", "decimal", null, "calc"),
                    ),
                rows =
                    listOf(
                        WorksheetProjectedRow(
                            kind = WorksheetRowKind.Group,
                            depth = 0,
                            pathKey = "g",
                            rowLabel = "pending",
                            expanded = true,
                            cells =
                                listOf(
                                    WorksheetCell(ResultCell.integer(300)),
                                    WorksheetCell(ResultCell.Null),
                                    WorksheetCell(ResultCell.Null),
                                ),
                            sourceRowIndex = null,
                        ),
                        WorksheetProjectedRow(
                            kind = WorksheetRowKind.Detail,
                            depth = 1,
                            pathKey = "g/0",
                            rowLabel = null,
                            expanded = true,
                            cells =
                                listOf(
                                    WorksheetCell(ResultCell.integer(100)),
                                    WorksheetCell(error = "bad formula"),
                                    WorksheetCell(error = "bad formula"),
                                ),
                            sourceRowIndex = 1,
                        ),
                        WorksheetProjectedRow(
                            kind = WorksheetRowKind.Detail,
                            depth = 1,
                            pathKey = "g/1",
                            rowLabel = null,
                            expanded = true,
                            cells =
                                listOf(
                                    WorksheetCell(ResultCell.integer(200)),
                                    WorksheetCell(ResultCell.float(2.5)),
                                    WorksheetCell(error = "bad formula"),
                                ),
                            sourceRowIndex = 2,
                        ),
                        WorksheetProjectedRow(
                            kind = WorksheetRowKind.GrandTotal,
                            depth = 0,
                            pathKey = "total",
                            rowLabel = "Grand total",
                            expanded = true,
                            cells =
                                listOf(
                                    WorksheetCell(ResultCell.integer(300)),
                                    WorksheetCell(ResultCell.float(2.5)),
                                    WorksheetCell(error = "bad formula"),
                                ),
                            sourceRowIndex = null,
                        ),
                    ),
                hasRowLabels = true,
            )

        val report = buildWorksheetReport(session, projection, listOf("careful"))

        val table = assertNotNull(report.table)
        assertEquals(listOf("Group", "Amount", "Doubled", "Broken"), table.columns.map { it.label })
        assertTrue(table.columns[1].numeric)
        // A column stays numeric when only some rows error; an all-error column does not.
        assertTrue(table.columns[2].numeric)
        assertFalse(table.columns[3].numeric)
        assertFalse(table.sortable)
        assertEquals(listOf("group", "detail", "detail", "total"), table.rows.map { it.kind })
        assertEquals("Error: bad formula", table.rows[1].cells[2].t)
        assertNull(table.rows[1].cells[2].n)
        assertEquals(listOf(1), table.rows[1].d)
        assertNull(table.rows[0].d)
        assertNull(table.rows.last().d)
        assertEquals(listOf("careful"), report.meta.warnings)
    }

    @Test
    fun visualizationReportComputesBarGeometryAndMarkDrill() {
        val session = session()
        val config =
            VisualizationConfig(
                chartType = ChartType.Bar,
                x = VisualizationField("t0__status", "Status"),
                values = listOf(VisualizationMeasure("count", MeasureFn.Count)),
            )
        val preview = applyVisualization(session.sample, config, session.baseSpec.tables)

        val report = buildVisualizationReport(session, preview, config)

        val chart = assertNotNull(report.chart)
        assertEquals(CHART_EXPORT_WIDTH, chart.width)
        val bars = chart.shapes.filter { it.kind == "rect" }
        assertEquals(2, bars.size)
        bars.forEach { bar ->
            val x = assertNotNull(bar.x)
            val y = assertNotNull(bar.y)
            assertTrue(x >= 0.0 && x + assertNotNull(bar.w) <= chart.width)
            assertTrue(y >= 0.0 && y + assertNotNull(bar.h) <= chart.height)
        }
        // Pending counts 2 rows, shipped 1: the bar heights keep that 2:1 ratio.
        val heights = bars.map { assertNotNull(it.h) }.sortedDescending()
        assertTrue(abs(heights[0] - 2 * heights[1]) < 1.0)
        assertEquals(5, chart.valueTicks.size)
        val pendingBar = bars.first { it.d?.size == 2 }
        assertEquals(listOf(0, 1), pendingBar.d)
        assertTrue(assertNotNull(pendingBar.tooltip).contains("pending"))
        assertNotNull(report.table)
    }

    @Test
    fun horizontalBarReportUsesCategoryGutterAndHorizontalTicks() {
        val session = session(statusText = "pending-" + "x".repeat(30))
        val config =
            VisualizationConfig(
                chartType = ChartType.Bar,
                x = VisualizationField("t0__status", "Status"),
                values = listOf(VisualizationMeasure.countRows()),
                barOrientation = BarOrientation.Horizontal,
            )
        val preview = applyVisualization(session.sample, config, session.baseSpec.tables)

        val chart = buildChartSection(preview, config)

        assertTrue(chart.horizontal)
        assertEquals(180.0, chart.plot[0])
        assertTrue(chart.valueTicks.zipWithNext().all { (first, second) -> first.pos < second.pos })
        assertEquals(preview.marks.size, chart.shapes.size)
        chart.shapes.forEach { bar ->
            assertEquals("rect", bar.kind)
            val x = assertNotNull(bar.x)
            val y = assertNotNull(bar.y)
            assertTrue(x >= chart.plot[0])
            assertTrue(x + assertNotNull(bar.w) <= chart.plot[2])
            assertTrue(y >= chart.plot[1])
            assertTrue(y + assertNotNull(bar.h) <= chart.plot[3])
        }
    }

    @Test
    fun visualizationKpiReportBuildsTile() {
        val session = session()
        val config =
            VisualizationConfig(
                chartType = ChartType.Kpi,
                values = listOf(VisualizationMeasure("amount", MeasureFn.Sum, "t0__amount")),
            )
        val preview = applyVisualization(session.sample, config, session.baseSpec.tables)

        val chart = assertNotNull(buildVisualizationReport(session, preview, config).chart)

        assertTrue(chart.shapes.isEmpty())
        val kpi = chart.kpis.single()
        assertEquals(preview.marks.first().formattedY, kpi.value)
        assertEquals(listOf(0, 1, 2), kpi.d)
    }

    @Test
    fun documentEscapesDataAndIsSelfContained() {
        val session =
            session(
                connectionLabel = "Evil </script> & <b>Lab</b>",
                statusText = "</script><img src=x>",
            )
        val output = ByteArrayOutputStream()

        writePivotReportHtml(session, applyExplore(session.sample, pivotConfig()), output)
        val html = output.toString(Charsets.UTF_8)

        assertTrue(html.startsWith("<!DOCTYPE html>"))
        assertFalse(html.contains("<img"))
        assertFalse(html.contains("</script><img"))
        assertTrue(html.contains("\\u003c/script"))
        assertTrue(html.contains("Evil &lt;/script&gt; &amp; &lt;b&gt;Lab&lt;/b&gt;"))
        assertFalse(html.contains("href="))
        assertFalse(html.contains("<link"))
        assertEquals(1, Regex("<style>").findAll(html).count())
    }

    @Test
    fun embeddedJsonRoundTrips() {
        val session = session()
        val output = ByteArrayOutputStream()

        writePivotReportHtml(session, applyExplore(session.sample, pivotConfig()), output)
        val html = output.toString(Charsets.UTF_8)

        val json =
            html
                .substringAfter("<script id=\"report-data\" type=\"application/json\">")
                .substringBefore("</script>")
        val report = Json.parseToJsonElement(json).jsonObject
        val meta = report.getValue("meta").jsonObject
        assertEquals("Local", meta.getValue("connectionLabel").jsonPrimitive.content)
        assertEquals("pivot", meta.getValue("mode").jsonPrimitive.content)
        assertEquals(
            3,
            report.getValue("source").jsonObject.getValue("rows").jsonArray.size,
        )
    }

    @Test
    fun binaryCellsRenderPlaceholderNotBase64() {
        val binary = ResultCell.binary("BLOB-PAYLOAD".toByteArray())
        val base64 = (binary as ResultCell.BinaryCell).value.base64
        val sample =
            QueryResult(
                columns = listOf(ResultColumn("t0__blob", "blob")),
                rows = listOf(listOf(binary)),
                rowCount = 1,
                truncated = false,
                warnings = emptyList(),
            )
        val session = session().copy(sample = sample)
        val output = ByteArrayOutputStream()

        writePivotReportHtml(session, applyExplore(sample, ExploreConfig()), output)
        val html = output.toString(Charsets.UTF_8)

        assertTrue(html.contains("(binary)"))
        assertFalse(html.contains(base64))
    }

    @Test
    fun nonFiniteDoublesEncodeAsTextWithoutNumericValue() {
        val result =
            QueryResult(
                columns = listOf(ResultColumn("t0__value", "double precision")),
                rows =
                    listOf(
                        listOf(ResultCell.float(Double.NaN)),
                        listOf(ResultCell.float(Double.POSITIVE_INFINITY)),
                        listOf(ResultCell.float(1.5)),
                    ),
                rowCount = 3,
                truncated = false,
                warnings = emptyList(),
            )

        val table = tableSectionFrom(result)

        assertEquals(listOf("NaN", "Infinity", "1.5"), table.rows.map { it.cells[0].t })
        assertEquals(listOf(null, null, 1.5), table.rows.map { it.cells[0].n })
        val meta =
            HtmlReportMeta(
                title = "t",
                connectionLabel = "Local",
                mode = "worksheet",
                sampleRowCount = 3,
                sampleTruncated = false,
                sampledAt = "",
                generatedAt = "",
                warnings = emptyList(),
            )
        // The default Json rejects non-finite doubles; encoding must not throw.
        val html = renderHtmlDocument(HtmlReport(meta = meta, table = table))
        assertTrue(html.contains("NaN"))
    }

    @Test
    fun categoryTicksSurviveLongLabels() {
        val labels = (0 until 4).map { "category-$it-" + "x".repeat(24) }
        val sample =
            QueryResult(
                columns = listOf(ResultColumn("t0__status", "varchar")),
                rows = labels.map { listOf(ResultCell.text(it)) },
                rowCount = 4,
                truncated = false,
                warnings = emptyList(),
            )
        val config =
            VisualizationConfig(
                chartType = ChartType.Bar,
                x = VisualizationField("t0__status", "Status"),
                values = listOf(VisualizationMeasure("count", MeasureFn.Count)),
            )
        val preview = applyVisualization(sample, config, session().baseSpec.tables)

        val chart = buildChartSection(preview, config)

        // The gap is measured on the truncated label and capped, so no tick is dropped.
        assertEquals(4, chart.categoryTicks.size)
        assertTrue(chart.categoryTicks.all { it.label.length == 24 && it.label.endsWith("…") })
    }

    @Test
    fun linePolylinesPrecedeCirclesAndCarryNoInteraction() {
        val session = session()
        val config =
            VisualizationConfig(
                chartType = ChartType.Line,
                x = VisualizationField("t0__status", "Status"),
                values =
                    listOf(
                        VisualizationMeasure("count", MeasureFn.Count),
                        VisualizationMeasure("amount", MeasureFn.Sum, "t0__amount"),
                    ),
            )
        val preview = applyVisualization(session.sample, config, session.baseSpec.tables)

        val chart = assertNotNull(buildVisualizationReport(session, preview, config).chart)

        val kinds = chart.shapes.map { it.kind }
        assertEquals(2, kinds.count { it == "polyline" })
        assertTrue(kinds.lastIndexOf("polyline") < kinds.indexOf("circle"))
        chart.shapes
            .filter { it.kind == "polyline" }
            .forEach { polyline ->
                assertNull(polyline.tooltip)
                assertNull(polyline.d)
            }
        assertTrue(REPORT_JS.contains("'pointer-events': 'none'"))
    }

    @Test
    fun scatterReportBuildsReversedDrillableCirclesInsidePlot() {
        val session = session()
        val config =
            VisualizationConfig(
                chartType = ChartType.Scatter,
                x = VisualizationField("t0__id", "ID"),
                values =
                    listOf(
                        VisualizationMeasure(
                            "amount",
                            MeasureFn.Sum,
                            "t0__amount",
                            "Amount",
                            aggregate = false,
                        ),
                    ),
            )
        val preview = applyVisualization(session.sample, config, session.baseSpec.tables)

        val chart = buildChartSection(preview, config)

        assertEquals(preview.marks.size, chart.shapes.size)
        assertTrue(chart.shapes.all { it.kind == "circle" })
        chart.shapes.forEach { point ->
            assertTrue(assertNotNull(point.cx) in chart.plot[0]..chart.plot[2])
            assertTrue(assertNotNull(point.cy) in chart.plot[1]..chart.plot[3])
        }
        assertEquals(
            preview.marks.map { it.sourceRowIndices }.asReversed(),
            chart.shapes.map { it.d },
        )
    }

    @Test
    fun overflowMessageRendersOnceInPivotSection() {
        val session = session()
        val preview = applyExplore(session.sample, pivotConfig())
        val message = "This pivot overflows."
        val overflowing =
            preview.copy(
                warnings = preview.warnings + listOf("other", message),
                layout = preview.layout.copy(overflowMessage = message),
            )

        val report = buildPivotReport(session, overflowing)

        assertEquals(message, assertNotNull(report.pivot).overflowMessage)
        assertEquals(preview.warnings + "other", report.meta.warnings)
    }

    @Test
    fun detailKindOmittedFromJsonAndDefaultedByScript() {
        val session = session()
        val projection =
            WorksheetTableProjection(
                resolvedColumns = emptyList(),
                columns = listOf(WorksheetDisplayColumn("c1", "Amount", "bigint", "t0__amount")),
                rows =
                    listOf(
                        WorksheetProjectedRow(
                            kind = WorksheetRowKind.Detail,
                            depth = 0,
                            pathKey = "0",
                            rowLabel = null,
                            expanded = true,
                            cells = listOf(WorksheetCell(ResultCell.integer(100))),
                            sourceRowIndex = 0,
                        ),
                    ),
                hasRowLabels = false,
            )
        val output = ByteArrayOutputStream()

        writeWorksheetReportHtml(session, projection, emptyList(), output)
        val html = output.toString(Charsets.UTF_8)

        val json =
            html
                .substringAfter("<script id=\"report-data\" type=\"application/json\">")
                .substringBefore("</script>")
        val rows =
            Json.parseToJsonElement(json)
                .jsonObject
                .getValue("table")
                .jsonObject
                .getValue("rows")
                .jsonArray
        assertTrue(rows.none { "kind" in it.jsonObject })
        // The bootstrap must default the omitted kind instead of treating flat rows as grouped.
        assertTrue(REPORT_JS.contains("(r.kind || 'detail')"))
    }

    @Test
    fun visualizationChartEmitsBarLabelsOnlyWhenShowLabelsEnabledAndUsesNiceTicks() {
        val sample =
            QueryResult(
                columns =
                    listOf(
                        ResultColumn("t0__status", "varchar"),
                        ResultColumn("t0__amount", "decimal"),
                    ),
                rows =
                    listOf(
                        listOf(ResultCell.text("Services"), ResultCell.float(3_624_326.7)),
                        listOf(ResultCell.text("Subscription"), ResultCell.float(1_620_951.78)),
                    ),
                rowCount = 2,
                truncated = false,
                warnings = emptyList(),
            )
        val baseConfig =
            VisualizationConfig(
                chartType = ChartType.Bar,
                x = VisualizationField("t0__status", "Status"),
                values = listOf(VisualizationMeasure("sum", MeasureFn.Sum, "t0__amount")),
                barOrientation = BarOrientation.Horizontal,
                showLabels = false,
            )

        val hiddenPreview = applyVisualization(sample, baseConfig, session().baseSpec.tables)
        val hiddenChart = buildChartSection(hiddenPreview, baseConfig)
        assertTrue(hiddenChart.shapes.all { it.label == null })
        assertEquals(listOf("0", "1M", "2M", "3M", "4M"), hiddenChart.valueTicks.map { it.label })

        val shownConfig = baseConfig.copy(showLabels = true)
        val shownPreview = applyVisualization(sample, shownConfig, session().baseSpec.tables)
        val shownChart = buildChartSection(shownPreview, shownConfig)
        assertEquals(listOf("3.6M", "1.6M"), shownChart.shapes.map { it.label })
        assertTrue(shownChart.shapes.all { it.lx != null && it.ly != null })
        HTML_SERIES_COLORS.forEachIndexed { index, color ->
            val expectedHex = contrastingBarLabelColor(color).toCssHex()
            assertTrue(REPORT_CSS.contains("--s$index: ${color.toCssHex()};"))
            assertTrue(REPORT_CSS.contains("--s$index-label: $expectedHex;"))
        }
    }

    @Test
    fun visualizationChartPositionsVerticalLabelsOmitsTinyBarsAndAssignsSeriesIndices() {
        val sample =
            QueryResult(
                columns =
                    listOf(
                        ResultColumn("t0__status", "varchar"),
                        ResultColumn("t0__region", "varchar"),
                        ResultColumn("t0__amount", "decimal"),
                    ),
                rows =
                    listOf(
                        listOf(
                            ResultCell.text("Alpha"),
                            ResultCell.text("A-Series"),
                            ResultCell.float(1_000_000.0),
                        ),
                        listOf(
                            ResultCell.text("Alpha"),
                            ResultCell.text("B-Series"),
                            ResultCell.float(500_000.0),
                        ),
                        listOf(
                            ResultCell.text("Tiny"),
                            ResultCell.text("A-Series"),
                            ResultCell.float(1.0),
                        ),
                    ),
                rowCount = 3,
                truncated = false,
                warnings = emptyList(),
            )
        val config =
            VisualizationConfig(
                chartType = ChartType.Bar,
                x = VisualizationField("t0__status", "Status"),
                values = listOf(VisualizationMeasure("sum", MeasureFn.Sum, "t0__amount")),
                series = VisualizationField("t0__region", "Region"),
                barOrientation = BarOrientation.Vertical,
                showLabels = true,
            )
        val preview = applyVisualization(sample, config, session().baseSpec.tables)
        val chart = buildChartSection(preview, config)
        val labeled = chart.shapes.filter { it.label != null }
        assertEquals(2, labeled.size)
        // Vertical bar labels sit near the top of the bar (top + 10.0), and Tiny bar is omitted.
        assertTrue(labeled.all { shape -> shape.ly == shape.y!! + 10.0 })
        assertEquals(listOf(0, 1), labeled.map { it.series })
        assertNull(chart.shapes.last().label)
    }

    @Test
    fun groupedBarReportEmitsAllBarLabelsWhileOmittingOverflowingLabels() {
        val categories = listOf("Services", "Subscription", "Hardware", "Add-on")
        val segments = listOf("Enterprise", "Mid-Market", "SMB")
        val rows = categories.flatMapIndexed { cIndex, category ->
            segments.mapIndexed { sIndex, segment ->
                listOf(
                    ResultCell.text(category),
                    ResultCell.text(segment),
                    ResultCell.float(514_400.0 - cIndex * 12_000.0 - sIndex * 5_000.0),
                )
            }
        }
        val sample =
            QueryResult(
                columns =
                    listOf(
                        ResultColumn("t0__category", "varchar"),
                        ResultColumn("t0__segment", "varchar"),
                        ResultColumn("t0__amount", "decimal"),
                    ),
                rows = rows,
                rowCount = rows.size,
                truncated = false,
                warnings = emptyList(),
            )
        val config =
            VisualizationConfig(
                chartType = ChartType.Bar,
                x = VisualizationField("t0__category", "Category"),
                values = listOf(VisualizationMeasure("sum", MeasureFn.Sum, "t0__amount")),
                series = VisualizationField("t0__segment", "Segment"),
                barOrientation = BarOrientation.Vertical,
                showLabels = true,
            )
        val preview = applyVisualization(sample, config, session().baseSpec.tables)
        val chart = buildChartSection(preview, config)

        assertEquals(12, chart.shapes.size)
        assertEquals(12, chart.shapes.count { it.label != null })

        val longLabelConfig =
            config.copy(
                values =
                    listOf(
                        VisualizationMeasure(
                            "sum",
                            MeasureFn.Sum,
                            "t0__amount",
                            numberFormat =
                                PivotNumberFormat(NumberFormatKind.Currency, decimals = 2),
                        ),
                    ),
            )
        val longPreview = applyVisualization(sample, longLabelConfig, session().baseSpec.tables)
        val longChart = buildChartSection(longPreview, longLabelConfig)
        assertTrue(longChart.shapes.all { it.label == null })
    }

    @Test
    fun visualizationScatterTableOmitsUnsetSeriesAndSizeAndIncludesThemWhenConfigured() {
        val sample =
            QueryResult(
                columns =
                    listOf(
                        ResultColumn("t0__region", "varchar"),
                        ResultColumn("t0__qty", "decimal"),
                        ResultColumn("t0__amount", "decimal"),
                        ResultColumn("t0__weight", "decimal"),
                    ),
                rows =
                    listOf(
                        listOf(
                            ResultCell.text("Europe"),
                            ResultCell.float(12.5),
                            ResultCell.float(400.0),
                            ResultCell.float(3.0),
                        ),
                    ),
                rowCount = 1,
                truncated = false,
                warnings = emptyList(),
            )
        val session = session().copy(sample = sample)
        val plainConfig =
            VisualizationConfig(
                chartType = ChartType.Scatter,
                x = VisualizationField("t0__qty", "Qty"),
                values =
                    listOf(
                        VisualizationMeasure(
                            "amt",
                            MeasureFn.Sum,
                            "t0__amount",
                            "Amount",
                            aggregate = false,
                        ),
                    ),
            )
        val plainPreview = applyVisualization(sample, plainConfig, session.baseSpec.tables)
        val plainTable =
            assertNotNull(buildVisualizationReport(session, plainPreview, plainConfig).table)
        assertEquals(listOf("Qty", "Amount", "Source rows"), plainTable.columns.map { it.label })
        assertEquals(listOf(true, true, true), plainTable.columns.map { it.numeric })

        val fullConfig =
            plainConfig.copy(
                series = VisualizationField("t0__region", "Region"),
                size = VisualizationField("t0__weight", "Weight"),
            )
        val fullPreview = applyVisualization(sample, fullConfig, session.baseSpec.tables)
        val fullTable =
            assertNotNull(buildVisualizationReport(session, fullPreview, fullConfig).table)
        assertEquals(
            listOf("Qty", "Amount", "Series", "Size", "Source rows"),
            fullTable.columns.map { it.label },
        )
        assertEquals(listOf(true, true, false, true, true), fullTable.columns.map { it.numeric })
    }

    @Test
    fun visualizationTableDeduplicatesSeriesAndKeepsFormattedNumericValuesSortable() {
        val sample =
            QueryResult(
                columns =
                    listOf(
                        ResultColumn("t0__status", "varchar"),
                        ResultColumn("t0__region", "varchar"),
                        ResultColumn("t0__amount", "decimal"),
                    ),
                rows =
                    listOf(
                        listOf(
                            ResultCell.text("Services"),
                            ResultCell.text("Europe"),
                            ResultCell.float(3_624_326.7),
                        ),
                        listOf(
                            ResultCell.text("Subscription"),
                            ResultCell.text("Asia"),
                            ResultCell.float(1_620_951.78),
                        ),
                    ),
                rowCount = 2,
                truncated = false,
                warnings = emptyList(),
            )
        val session = session().copy(sample = sample)
        val singleConfig =
            VisualizationConfig(
                chartType = ChartType.Bar,
                x = VisualizationField("t0__status", "Status"),
                values =
                    listOf(
                        VisualizationMeasure("sum", MeasureFn.Sum, "t0__amount", "Sum amount"),
                    ),
            )
        val singlePreview = applyVisualization(sample, singleConfig, session.baseSpec.tables)
        val singleTable =
            assertNotNull(buildVisualizationReport(session, singlePreview, singleConfig).table)

        assertEquals(
            listOf("Status", "Measure", "Value", "Source rows"),
            singleTable.columns.map { it.label },
        )
        assertTrue(singleTable.columns[2].numeric)
        assertEquals(3_624_326.7, singleTable.rows.first().cells[2].n)
        assertTrue(
            singleTable.rows.first().cells[2].t.contains("3,624,326.7") ||
                singleTable.rows.first().cells[2].t.contains("3.624.326,7"),
        )

        val multiConfig = singleConfig.copy(series = VisualizationField("t0__region", "Region"))
        val multiPreview = applyVisualization(sample, multiConfig, session.baseSpec.tables)
        val multiTable =
            assertNotNull(buildVisualizationReport(session, multiPreview, multiConfig).table)

        assertEquals(
            listOf("Status", "Series", "Measure", "Value", "Source rows"),
            multiTable.columns.map { it.label },
        )
    }

    @Test
    fun dualAxisHtmlReportEmitsSecondaryAxisTicksExpandedPlotAndLegendLabels() {
        val sample =
            QueryResult(
                columns =
                    listOf(
                        ResultColumn("t0__category", "varchar"),
                        ResultColumn("t0__amount", "decimal"),
                    ),
                rows =
                    listOf(
                        listOf(ResultCell.text("Services"), ResultCell.float(3_624_326.7)),
                        listOf(ResultCell.text("Subscription"), ResultCell.float(1_620_951.78)),
                    ),
                rowCount = 2,
                truncated = false,
                warnings = emptyList(),
            )
        val session = session().copy(sample = sample)
        val verticalConfig =
            VisualizationConfig(
                chartType = ChartType.Bar,
                x = VisualizationField("t0__category", "Category"),
                values =
                    listOf(
                        VisualizationMeasure("sum", MeasureFn.Sum, "t0__amount", "Sum line_total"),
                        VisualizationMeasure(
                            "distinct",
                            MeasureFn.CountDistinct,
                            "t0__category",
                            "Distinct category",
                            axis = ValueAxis.Secondary,
                        ),
                    ),
            )
        val verticalPreview = applyVisualization(sample, verticalConfig, session.baseSpec.tables)
        val verticalChart =
            assertNotNull(buildVisualizationReport(session, verticalPreview, verticalConfig).chart)
        assertTrue(verticalChart.secondaryValueTicks.isNotEmpty())
        assertTrue(verticalChart.plot[2] < CHART_EXPORT_WIDTH - 28.0)
        assertEquals(
            listOf("Sum line_total (left)", "Distinct category (right)"),
            verticalChart.legend,
        )
        assertEquals(4, verticalChart.shapes.size)
        assertTrue(verticalChart.shapes.all { (it.h ?: 0.0) > 100.0 })

        val horizontalConfig = verticalConfig.copy(barOrientation = BarOrientation.Horizontal)
        val horizontalPreview =
            applyVisualization(sample, horizontalConfig, session.baseSpec.tables)
        val horizontalChart =
            assertNotNull(
                buildVisualizationReport(session, horizontalPreview, horizontalConfig).chart,
            )
        assertTrue(horizontalChart.secondaryValueTicks.isNotEmpty())
        assertTrue(horizontalChart.plot[1] >= 34.0)
        assertEquals(
            listOf("Sum line_total (bottom)", "Distinct category (top)"),
            horizontalChart.legend,
        )

        val lineConfig = verticalConfig.copy(chartType = ChartType.Line)
        val linePreview = applyVisualization(sample, lineConfig, session.baseSpec.tables)
        val lineChart =
            assertNotNull(buildVisualizationReport(session, linePreview, lineConfig).chart)
        assertTrue(lineChart.secondaryValueTicks.isNotEmpty())
        assertEquals(
            listOf("Sum line_total (left)", "Distinct category (right)"),
            lineChart.legend,
        )
        assertTrue(REPORT_JS.contains("chart.secondaryValueTicks"))
    }

    private fun pivotConfig() =
        ExploreConfig(
            rowDimensions = listOf(PivotDimension(column = "t0__status")),
            measures =
                listOf(
                    PivotMeasure.countRows(),
                    PivotMeasure("total", MeasureFn.Sum, "t0__amount", label = "Total amount"),
                ),
        )

    private fun session(
        connectionLabel: String = "Local",
        statusText: String = "pending",
    ): ExploreSession {
        val spec =
            QuerySpec(
                tables = listOf(TableRef("public", "orders", "t0")),
                columns = emptyList(),
                joins = emptyList(),
                filters = FilterGroup.empty(),
                limit = 100,
            )
        val sample =
            QueryResult(
                columns =
                    listOf(
                        ResultColumn("t0__id", "bigint"),
                        ResultColumn("t0__status", "varchar"),
                        ResultColumn("t0__amount", "bigint"),
                    ),
                rows =
                    listOf(
                        listOf(
                            ResultCell.integer(1),
                            ResultCell.text(statusText),
                            ResultCell.integer(100),
                        ),
                        listOf(
                            ResultCell.integer(2),
                            ResultCell.text(statusText),
                            ResultCell.integer(200),
                        ),
                        listOf(
                            ResultCell.integer(3),
                            ResultCell.text("shipped"),
                            ResultCell.integer(300),
                        ),
                    ),
                rowCount = 3,
                truncated = false,
                warnings = emptyList(),
            )
        return ExploreSession(
            connectionId = "c1",
            connectionLabel = connectionLabel,
            baseSpec = spec,
            baseSpecHash = "hash",
            sample = sample,
            sampleFetchedAtEpochSec = 0,
            builderLimit = 100,
        )
    }
}
