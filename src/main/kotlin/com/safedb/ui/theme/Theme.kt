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
        thickness = 6.dp,
        shape = RoundedCornerShape(1.dp),
        hoverDurationMillis = 120,
        unhoverColor = if (isDark) Color(0x405F7182) else Color(0x50687889),
        hoverColor = if (isDark) Color(0xA06F8497) else Color(0xA0526576),
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
