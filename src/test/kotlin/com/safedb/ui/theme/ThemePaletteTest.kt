package com.safedb.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.safedb.model.ThemePalette
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ThemePaletteTest {
    @Test
    fun everyPaletteResolvesInBothModesWithCompleteSeries() {
        ThemePalette.entries.forEach { palette ->
            listOf(lightScheme(palette), darkScheme(palette)).forEach { (material, colors) ->
                assertEquals(8, colors.series.size)
                assertEquals(8, colors.series.distinct().size)
                assertNotEquals(colors.workspaceBackground, colors.workspaceCanvas)
                assertNotEquals(colors.navigationBackground, colors.workspaceBackground)
                assertTrue(contrast(material.onSurface, material.surface) >= 7.0)
                assertTrue(contrast(colors.onActionPrimary, colors.actionPrimary) >= 3.0)
                assertTrue(contrast(material.onPrimary, material.primary) >= 4.5)
            }
        }
    }

    @Test
    fun schemesHaveDistinctSignalsAndControlBlueRemainsTheDefault() {
        val lightSignals = ThemePalette.entries.map { lightScheme(it).second.actionPrimary }
        val darkSignals = ThemePalette.entries.map { darkScheme(it).second.actionPrimary }

        assertEquals(lightSignals.size, lightSignals.distinct().size)
        assertEquals(darkSignals.size, darkSignals.distinct().size)
        assertEquals(Color(0xFF0B5ED7), lightScheme().second.actionPrimary)
        assertEquals(Color(0xFF18222D), lightScheme().second.navigationBackground)
        assertEquals(Color(0xFF4C8DFF), darkScheme().second.actionPrimary)
        assertEquals(Color(0xFF6EA2FF), darkScheme().first.primary)
    }

    @Test
    fun darkSeriesColorsStayLegibleOnTheDarkChartCanvas() {
        ThemePalette.entries.forEach { palette ->
            val colors = darkScheme(palette).second
            colors.series.forEach { series ->
                assertTrue(
                    contrast(series, colors.workspaceCanvas) >= 3.0,
                    "$palette dark series $series lacks contrast on ${colors.workspaceCanvas}",
                )
            }
        }
    }

    @Test
    fun controlBlueDarkSeriesDivergesFromTheSharedLightPalette() {
        assertNotEquals(
            VisualizationSeriesPalette,
            darkScheme(ThemePalette.ControlBlue).second.series,
        )
    }

    private fun contrast(foreground: Color, background: Color): Double {
        val high = maxOf(foreground.luminance(), background.luminance()).toDouble()
        val low = minOf(foreground.luminance(), background.luminance()).toDouble()
        return (high + 0.05) / (low + 0.05)
    }
}
