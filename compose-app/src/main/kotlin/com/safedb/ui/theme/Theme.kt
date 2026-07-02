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
        thickness = 8.dp,
        shape = RoundedCornerShape(4.dp),
        hoverDurationMillis = 200,
        unhoverColor = if (isDark) Color(0xFF475569) else Color(0xFFCBD5E1), // slate-600 / slate-300
        hoverColor = if (isDark) Color(0xFF64748B) else Color(0xFF94A3B8), // slate-500 / slate-400
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
