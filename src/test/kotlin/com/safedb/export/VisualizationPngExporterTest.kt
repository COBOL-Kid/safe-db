package com.safedb.export

import com.safedb.explore.BarOrientation
import com.safedb.explore.ChartType
import com.safedb.explore.ValueAxis
import com.safedb.explore.VisualizationConfig
import com.safedb.explore.VisualizationMark
import com.safedb.explore.VisualizationPreview
import com.safedb.explore.VisualizationSeries
import java.nio.file.Files
import kotlin.io.path.createTempFile
import kotlin.test.Test
import kotlin.test.assertTrue

class VisualizationPngExporterTest {
    @Test
    fun lineChartExportSurvivesTheEmptyFirstFrame() {
        assertExportsPng(ChartType.Line)
    }

    @Test
    fun scatterChartExportSurvivesTheEmptyFirstFrame() {
        assertExportsPng(ChartType.Scatter)
    }

    @Test
    fun dualAxisBarChartsExportValidPng() {
        val preview =
            VisualizationPreview(
                chartType = ChartType.Bar,
                title = "Revenue vs Distinct",
                marks =
                    listOf(
                        mark("a-rev", "a", "Services", 3_624_326.7, 1.0, "rev", ValueAxis.Primary),
                        mark("a-cat", "a", "Services", 1.0, 1.0, "cat", ValueAxis.Secondary),
                    ),
                series =
                    listOf(
                        VisualizationSeries("rev", "Sum line_total", ValueAxis.Primary),
                        VisualizationSeries("cat", "Distinct category", ValueAxis.Secondary),
                    ),
            )
        listOf(BarOrientation.Vertical, BarOrientation.Horizontal).forEach { orientation ->
            val path = createTempFile(suffix = ".png")
            writeVisualizationPng(
                preview = preview,
                config =
                    VisualizationConfig(chartType = ChartType.Bar, barOrientation = orientation),
                sampleRowCount = 2,
                sampleTruncated = false,
                isDark = false,
                path = path,
            )
            val bytes = Files.readAllBytes(path)
            assertTrue(
                bytes
                    .take(8)
                    .toByteArray()
                    .contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)),
            )
        }
    }

    private fun assertExportsPng(type: ChartType) {
        val preview =
            VisualizationPreview(
                chartType = type,
                title = "Chart",
                marks =
                    listOf(
                        mark("m1", "a", "A", 10.0, 1.0),
                        mark("m2", "b", "B", 5.0, 2.0),
                        mark("m3", "c", "C", 8.0, 3.0),
                    ),
            )
        val path = createTempFile(suffix = ".png")

        writeVisualizationPng(
            preview = preview,
            config = VisualizationConfig(chartType = type),
            sampleRowCount = 3,
            sampleTruncated = false,
            isDark = false,
            path = path,
        )

        val bytes = Files.readAllBytes(path)
        assertTrue(
            bytes
                .take(8)
                .toByteArray()
                .contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)),
        )
    }

    private fun mark(
        id: String,
        key: String,
        label: String,
        y: Double,
        x: Double,
        seriesKey: String = "series",
        axis: ValueAxis = ValueAxis.Primary,
    ) =
        VisualizationMark(
            id = id,
            xKey = key,
            xLabel = label,
            xValue = x,
            y = y,
            formattedY = y.toString(),
            seriesKey = seriesKey,
            seriesLabel = seriesKey,
            measureAlias = "value",
            measureLabel = "Value",
            axis = axis,
            sourceRowIndices = listOf(0),
        )
}
