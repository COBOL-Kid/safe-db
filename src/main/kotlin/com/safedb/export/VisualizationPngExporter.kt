package com.safedb.export

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import com.safedb.explore.VisualizationConfig
import com.safedb.explore.VisualizationPreview
import com.safedb.model.ThemePalette
import com.safedb.ui.VisualizationChart
import com.safedb.ui.theme.SafeDbTheme
import java.nio.file.Files
import java.nio.file.Path
import org.jetbrains.skia.EncodedImageFormat

const val VISUALIZATION_EXPORT_WIDTH = 1600
const val VISUALIZATION_EXPORT_HEIGHT = 900

@OptIn(ExperimentalComposeUiApi::class)
fun writeVisualizationPng(
    preview: VisualizationPreview,
    config: VisualizationConfig,
    sampleRowCount: Int,
    sampleTruncated: Boolean,
    isDark: Boolean,
    palette: ThemePalette = ThemePalette.DEFAULT,
    path: Path,
) {
    require(preview.ready) { "Complete the chart before exporting." }
    ImageComposeScene(
            width = VISUALIZATION_EXPORT_WIDTH,
            height = VISUALIZATION_EXPORT_HEIGHT,
            density = Density(1f),
        ) {
            SafeDbTheme(isDark = isDark, palette = palette) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    VisualizationChart(
                        preview = preview,
                        config = config,
                        sampleRowCount = sampleRowCount,
                        sampleTruncated = sampleTruncated,
                        onMarkClick = {},
                        modifier = Modifier.fillMaxSize(),
                        exportMode = true,
                    )
                }
            }
        }
        .use { scene ->
            scene.render(0L)
            val image = scene.render(250_000_000L)
            val data =
                requireNotNull(image.encodeToData(EncodedImageFormat.PNG)) {
                    "Could not encode chart PNG"
                }
            Files.write(path, data.bytes)
        }
}
