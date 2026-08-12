package com.safedb.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import com.safedb.model.ThemePalette

val VisualizationSeriesPalette =
    listOf(
        Color(0xFF0B5ED7),
        Color(0xFF087E8B),
        Color(0xFFB76A00),
        Color(0xFF6941C6),
        Color(0xFFB4233C),
        Color(0xFF1976A3),
        Color(0xFF527A22),
        Color(0xFFC14F18),
    )

@Immutable
data class SafeDbColors(
    val actionPrimary: Color,
    val onActionPrimary: Color,
    val actionPrimaryHover: Color,
    val accentContainer: Color,
    val onAccentContainer: Color,
    val workspaceBackground: Color,
    val workspaceHeader: Color,
    val workspacePanel: Color,
    val workspaceCanvas: Color,
    val navigationBackground: Color,
    val navigationBorder: Color,
    val navigationHover: Color,
    val navigationSelected: Color,
    val onNavigation: Color,
    val onNavigationMuted: Color,
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val info: Color,
    val onInfo: Color,
    val infoContainer: Color,
    val onInfoContainer: Color,
    val uq: Color,
    val onUq: Color,
    val uqContainer: Color,
    val onUqContainer: Color,
    val series: List<Color>,
)

fun lightScheme(palette: ThemePalette = ThemePalette.DEFAULT): Pair<ColorScheme, SafeDbColors> =
    paletteSpecFor(palette, isDark = false).toScheme(isDark = false)

fun darkScheme(palette: ThemePalette = ThemePalette.DEFAULT): Pair<ColorScheme, SafeDbColors> =
    paletteSpecFor(palette, isDark = true).toScheme(isDark = true)
