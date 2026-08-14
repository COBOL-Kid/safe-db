package com.safedb.export

import com.safedb.explore.ChartType
import com.safedb.explore.VisualizationConfig
import com.safedb.explore.VisualizationMark
import com.safedb.explore.VisualizationPreview
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
            bytes.take(8).toByteArray().contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
        )
    }

    private fun mark(id: String, key: String, label: String, y: Double, x: Double) =
        VisualizationMark(
            id = id,
            xKey = key,
            xLabel = label,
            xValue = x,
            y = y,
            formattedY = y.toString(),
            seriesKey = "series",
            seriesLabel = "Series",
            measureAlias = "value",
            measureLabel = "Value",
            sourceRowIndices = listOf(0),
        )
}
