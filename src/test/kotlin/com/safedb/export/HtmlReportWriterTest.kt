package com.safedb.export

import com.safedb.explore.ChartType
import com.safedb.explore.ExploreConfig
import com.safedb.explore.ExploreSession
import com.safedb.explore.MeasureFn
import com.safedb.explore.PivotDimension
import com.safedb.explore.PivotMeasure
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
                                ),
                            sourceRowIndex = 1,
                        ),
                    ),
                hasRowLabels = true,
            )

        val report = buildWorksheetReport(session, projection, listOf("careful"))

        val table = assertNotNull(report.table)
        assertEquals(listOf("Group", "Amount", "Doubled"), table.columns.map { it.label })
        assertTrue(table.columns[1].numeric)
        assertFalse(table.columns[2].numeric)
        assertFalse(table.sortable)
        assertEquals(listOf("group", "detail"), table.rows.map { it.kind })
        assertEquals("Error: bad formula", table.rows[1].cells[2].t)
        assertEquals(listOf(1), table.rows[1].d)
        assertNull(table.rows[0].d)
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
        // pending counts 2 rows, shipped 1: the bar heights keep that 2:1 ratio.
        val heights = bars.map { assertNotNull(it.h) }.sortedDescending()
        assertTrue(abs(heights[0] - 2 * heights[1]) < 1.0)
        assertEquals(5, chart.valueTicks.size)
        val pendingBar = bars.first { it.d?.size == 2 }
        assertEquals(listOf(0, 1), pendingBar.d)
        assertTrue(assertNotNull(pendingBar.tooltip).contains("pending"))
        assertNotNull(report.table)
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
