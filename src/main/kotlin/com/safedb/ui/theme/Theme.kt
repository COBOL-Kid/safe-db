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

object SafeDbTheme {
    val colors: SafeDbColors
        @Composable get() = LocalSafeDbColors.current
}

val LocalSafeDbColors = staticCompositionLocalOf<SafeDbColors> {
    error("SafeDbColors not provided. Wrap content in SafeDbTheme.")
}

@Composable
fun SafeDbTheme(
    isDark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val (colorScheme, safeDbColors) = if (isDark) darkScheme() else lightScheme()

    val scrollbarStyle = ScrollbarStyle(
        minimalHeight = 32.dp,
        thickness = 7.dp,
        shape = RoundedCornerShape(4.dp),
        hoverDurationMillis = 200,
        unhoverColor = if (isDark) Color(0x4094A3B8) else Color(0x5994A3B8), // translucent slate-400
        hoverColor = if (isDark) Color(0x8094A3B8) else Color(0x9994A3B8),
    )

    CompositionLocalProvider(
        LocalSafeDbColors provides safeDbColors,
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
