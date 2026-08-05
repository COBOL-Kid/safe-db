package com.safedb.ui.theme

import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.safedb.model.ThemePalette

object SafeDbTheme {
    val colors: SafeDbColors
        @Composable get() = LocalSafeDbColors.current

    val palette: ThemePalette
        @Composable get() = LocalThemePalette.current
}

val LocalSafeDbColors =
    staticCompositionLocalOf<SafeDbColors> {
        error("SafeDbColors not provided. Wrap content in SafeDbTheme.")
    }

val LocalThemePalette = staticCompositionLocalOf { ThemePalette.DEFAULT }

@Composable
fun SafeDbTheme(
    isDark: Boolean = isSystemInDarkTheme(),
    palette: ThemePalette = ThemePalette.DEFAULT,
    content: @Composable () -> Unit,
) {
    val (colorScheme, safeDbColors) = if (isDark) darkScheme(palette) else lightScheme(palette)

    val scrollbarStyle =
        ScrollbarStyle(
            minimalHeight = 32.dp,
            thickness = 6.dp,
            shape = RoundedCornerShape(1.dp),
            hoverDurationMillis = 120,
            unhoverColor = if (isDark) Color(0x405F7182) else Color(0x50687889),
            hoverColor = if (isDark) Color(0xA06F8497) else Color(0xA0526576),
        )

    CompositionLocalProvider(
        LocalSafeDbColors provides safeDbColors,
        LocalThemePalette provides palette,
        LocalScrollbarStyle provides scrollbarStyle,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = SafeDbTypography,
            shapes = SafeDbShapes,
            content = content,
        )
    }
}
