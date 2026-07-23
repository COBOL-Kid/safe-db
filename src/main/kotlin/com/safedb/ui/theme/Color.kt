package com.safedb.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

val VisualizationSeriesPalette = listOf(
    Color(0xFF4F46E5),
    Color(0xFF0F766E),
    Color(0xFFB45309),
    Color(0xFF7C3AED),
    Color(0xFFBE123C),
    Color(0xFF0369A1),
    Color(0xFF4D7C0F),
    Color(0xFFC2410C),
)

/**
 * Custom semantic color roles that extend the standard Material 3 [ColorScheme].
 *
 * Standard roles (primary, surface, outline, error, ...) are provided via
 * [MaterialTheme.colorScheme]; these custom roles (success, warning, info, uq,
 * action*) are exposed through [LocalSafeDbColors] and accessed via
 * `SafeDbTheme.colors`.
 *
 * Palette: Slate neutrals with a single restrained indigo accent. Primary
 * actions, selection, joins, and focus states all use [actionPrimary]
 * (indigo-600 in light, indigo-400 in dark) so interactive elements read
 * instantly against the quiet neutral chrome.
 */
@Immutable
data class SafeDbColors(
    val actionPrimary: Color,
    val onActionPrimary: Color,
    val actionPrimaryHover: Color,
    val accentContainer: Color,
    val onAccentContainer: Color,
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
)

// ------------------------------------------------------------------
// Light — Slate neutral core, indigo accent
// ------------------------------------------------------------------

private val LightBackground = Color(0xFFF1F5F9) // slate-100 — deeper canvas so white cards read distinctly
private val LightSurface = Color(0xFFFFFFFF) // white
private val LightSurfaceContainerLowest = Color(0xFFFFFFFF)
private val LightSurfaceContainerLow = Color(0xFFF8FAFC) // slate-50 — sidebar rail, lighter than content canvas
private val LightSurfaceContainer = Color(0xFFF1F5F9) // slate-100
private val LightSurfaceContainerHigh = Color(0xFFEBEEF2)
private val LightSurfaceContainerHighest = Color(0xFFE2E8F0) // slate-200
private val LightOutline = Color(0xFFCBD5E1) // slate-300 — stronger, visible resting borders
private val LightOutlineVariant = Color(0xFFE2E8F0) // slate-200 — subtle internal dividers
private val LightOnSurface = Color(0xFF0F172A) // slate-900
private val LightOnSurfaceVariant = Color(0xFF64748B) // slate-500

private val LightPrimary = Color(0xFF4F46E5) // indigo-600
private val LightOnPrimary = Color(0xFFFFFFFF)
private val LightPrimaryContainer = Color(0xFFEEF2FF) // indigo-50
private val LightOnPrimaryContainer = Color(0xFF3730A3) // indigo-800

private val LightSecondary = Color(0xFF475569) // slate-600
private val LightOnSecondary = Color(0xFFFFFFFF)
private val LightSecondaryContainer = Color(0xFFF1F5F9) // slate-100
private val LightOnSecondaryContainer = Color(0xFF334155) // slate-700

private val LightTertiary = Color(0xFFB45309) // amber-700
private val LightOnTertiary = Color(0xFFFFFFFF)
private val LightTertiaryContainer = Color(0xFFFFFBEB) // amber-50
private val LightOnTertiaryContainer = Color(0xFF92400E) // amber-800

private val LightError = Color(0xFFDC2626) // red-600
private val LightOnError = Color(0xFFFFFFFF)
private val LightErrorContainer = Color(0xFFFEF2F2) // red-50
private val LightOnErrorContainer = Color(0xFFB91C1C) // red-700

private val LightScrim = Color(0x66000000)

private val LightActionPrimary = Color(0xFF4F46E5) // indigo-600
private val LightOnActionPrimary = Color(0xFFFFFFFF)
private val LightActionPrimaryHover = Color(0xFF4338CA) // indigo-700
private val LightAccentContainer = Color(0xFFEEF2FF) // indigo-50
private val LightOnAccentContainer = Color(0xFF4338CA) // indigo-700
private val LightSuccess = Color(0xFF047857) // emerald-700
private val LightOnSuccess = Color(0xFFFFFFFF)
private val LightSuccessContainer = Color(0xFFECFDF5) // emerald-50
private val LightOnSuccessContainer = Color(0xFF047857) // emerald-700
private val LightWarning = Color(0xFFB45309) // amber-700
private val LightOnWarning = Color(0xFFFFFFFF)
private val LightWarningContainer = Color(0xFFFFFBEB) // amber-50
private val LightOnWarningContainer = Color(0xFF92400E) // amber-800
private val LightInfo = Color(0xFF475569) // slate-600
private val LightOnInfo = Color(0xFFFFFFFF)
private val LightInfoContainer = Color(0xFFF1F5F9) // slate-100
private val LightOnInfoContainer = Color(0xFF334155) // slate-700
private val LightUq = Color(0xFF7C3AED) // violet-600
private val LightOnUq = Color(0xFFFFFFFF)
private val LightUqContainer = Color(0xFFF5F3FF) // violet-50
private val LightOnUqContainer = Color(0xFF5B21B6) // violet-800

// ------------------------------------------------------------------
// Dark — deep slate neutral core, indigo accent
// ------------------------------------------------------------------

private val DarkBackground = Color(0xFF0B1120) // between slate-900 and 950
private val DarkSurface = Color(0xFF111827) // refined slate-900
private val DarkSurfaceContainerLowest = Color(0xFF0B1120)
private val DarkSurfaceContainerLow = Color(0xFF151D2E)
private val DarkSurfaceContainer = Color(0xFF1B2436) // slate-800 blend
private val DarkSurfaceContainerHigh = Color(0xFF222C40)
private val DarkSurfaceContainerHighest = Color(0xFF2C374D)
private val DarkOutline = Color(0xFF334155) // slate-700 — clearer card/panel edges
private val DarkOutlineVariant = Color(0xFF283349) // muted slate-700 — subtle dividers
private val DarkOnSurface = Color(0xFFF1F5F9) // slate-100
private val DarkOnSurfaceVariant = Color(0xFF94A3B8) // slate-400

private val DarkPrimary = Color(0xFFA5B4FC) // indigo-300
private val DarkOnPrimary = Color(0xFF1E1B4B) // indigo-950
private val DarkPrimaryContainer = Color(0xFF2A2A5C) // deep indigo
private val DarkOnPrimaryContainer = Color(0xFFC7D2FE) // indigo-200

private val DarkSecondary = Color(0xFF94A3B8) // slate-400
private val DarkOnSecondary = Color(0xFF0B1120)
private val DarkSecondaryContainer = Color(0xFF1B2436)
private val DarkOnSecondaryContainer = Color(0xFFE2E8F0) // slate-200

private val DarkTertiary = Color(0xFFFCD34D) // amber-300
private val DarkOnTertiary = Color(0xFF0B1120)
private val DarkTertiaryContainer = Color(0xFF2A1F0A)
private val DarkOnTertiaryContainer = Color(0xFFFDE68A) // amber-200

private val DarkError = Color(0xFFF87171) // red-400
private val DarkOnError = Color(0xFF0B1120)
private val DarkErrorContainer = Color(0xFF2A1414)
private val DarkOnErrorContainer = Color(0xFFFCA5A5) // red-300

private val DarkScrim = Color(0x99000000)

private val DarkActionPrimary = Color(0xFF6366F1) // indigo-500
private val DarkOnActionPrimary = Color(0xFFFFFFFF)
private val DarkActionPrimaryHover = Color(0xFF818CF8) // indigo-400
private val DarkAccentContainer = Color(0xFF272A55) // deep indigo tint
private val DarkOnAccentContainer = Color(0xFFA5B4FC) // indigo-300
private val DarkSuccess = Color(0xFF34D399) // emerald-400
private val DarkOnSuccess = Color(0xFF0B1120)
private val DarkSuccessContainer = Color(0xFF0A2818)
private val DarkOnSuccessContainer = Color(0xFF6EE7B7) // emerald-300
private val DarkWarning = Color(0xFFFCD34D) // amber-300
private val DarkOnWarning = Color(0xFF0B1120)
private val DarkWarningContainer = Color(0xFF2A1F0A)
private val DarkOnWarningContainer = Color(0xFFFDE68A) // amber-200
private val DarkInfo = Color(0xFFCBD5E1) // slate-300
private val DarkOnInfo = Color(0xFF0B1120)
private val DarkInfoContainer = Color(0xFF1B2436)
private val DarkOnInfoContainer = Color(0xFFCBD5E1) // slate-300
private val DarkUq = Color(0xFFC4B5FD) // violet-300
private val DarkOnUq = Color(0xFF0B1120)
private val DarkUqContainer = Color(0xFF221840)
private val DarkOnUqContainer = Color(0xFFC4B5FD) // violet-300

fun lightScheme(): Pair<ColorScheme, SafeDbColors> =
    lightColorScheme(
        primary = LightPrimary,
        onPrimary = LightOnPrimary,
        primaryContainer = LightPrimaryContainer,
        onPrimaryContainer = LightOnPrimaryContainer,
        secondary = LightSecondary,
        onSecondary = LightOnSecondary,
        secondaryContainer = LightSecondaryContainer,
        onSecondaryContainer = LightOnSecondaryContainer,
        tertiary = LightTertiary,
        onTertiary = LightOnTertiary,
        tertiaryContainer = LightTertiaryContainer,
        onTertiaryContainer = LightOnTertiaryContainer,
        error = LightError,
        onError = LightOnError,
        errorContainer = LightErrorContainer,
        onErrorContainer = LightOnErrorContainer,
        background = LightBackground,
        onBackground = LightOnSurface,
        surface = LightSurface,
        onSurface = LightOnSurface,
        surfaceVariant = LightSurfaceContainer,
        onSurfaceVariant = LightOnSurfaceVariant,
        surfaceTint = LightPrimary,
        inverseSurface = LightOnSurface,
        inverseOnSurface = LightBackground,
        inversePrimary = DarkPrimary,
        outline = LightOutline,
        outlineVariant = LightOutlineVariant,
        scrim = LightScrim,
        surfaceContainerLowest = LightSurfaceContainerLowest,
        surfaceContainerLow = LightSurfaceContainerLow,
        surfaceContainer = LightSurfaceContainer,
        surfaceContainerHigh = LightSurfaceContainerHigh,
        surfaceContainerHighest = LightSurfaceContainerHighest,
    ) to SafeDbColors(
        actionPrimary = LightActionPrimary,
        onActionPrimary = LightOnActionPrimary,
        actionPrimaryHover = LightActionPrimaryHover,
        accentContainer = LightAccentContainer,
        onAccentContainer = LightOnAccentContainer,
        success = LightSuccess,
        onSuccess = LightOnSuccess,
        successContainer = LightSuccessContainer,
        onSuccessContainer = LightOnSuccessContainer,
        warning = LightWarning,
        onWarning = LightOnWarning,
        warningContainer = LightWarningContainer,
        onWarningContainer = LightOnWarningContainer,
        info = LightInfo,
        onInfo = LightOnInfo,
        infoContainer = LightInfoContainer,
        onInfoContainer = LightOnInfoContainer,
        uq = LightUq,
        onUq = LightOnUq,
        uqContainer = LightUqContainer,
        onUqContainer = LightOnUqContainer,
    )

fun darkScheme(): Pair<ColorScheme, SafeDbColors> =
    darkColorScheme(
        primary = DarkPrimary,
        onPrimary = DarkOnPrimary,
        primaryContainer = DarkPrimaryContainer,
        onPrimaryContainer = DarkOnPrimaryContainer,
        secondary = DarkSecondary,
        onSecondary = DarkOnSecondary,
        secondaryContainer = DarkSecondaryContainer,
        onSecondaryContainer = DarkOnSecondaryContainer,
        tertiary = DarkTertiary,
        onTertiary = DarkOnTertiary,
        tertiaryContainer = DarkTertiaryContainer,
        onTertiaryContainer = DarkOnTertiaryContainer,
        error = DarkError,
        onError = DarkOnError,
        errorContainer = DarkErrorContainer,
        onErrorContainer = DarkOnErrorContainer,
        background = DarkBackground,
        onBackground = DarkOnSurface,
        surface = DarkSurface,
        onSurface = DarkOnSurface,
        surfaceVariant = DarkSurfaceContainer,
        onSurfaceVariant = DarkOnSurfaceVariant,
        surfaceTint = DarkPrimary,
        inverseSurface = DarkOnSurface,
        inverseOnSurface = DarkBackground,
        inversePrimary = LightPrimary,
        outline = DarkOutline,
        outlineVariant = DarkOutlineVariant,
        scrim = DarkScrim,
        surfaceContainerLowest = DarkSurfaceContainerLowest,
        surfaceContainerLow = DarkSurfaceContainerLow,
        surfaceContainer = DarkSurfaceContainer,
        surfaceContainerHigh = DarkSurfaceContainerHigh,
        surfaceContainerHighest = DarkSurfaceContainerHighest,
    ) to SafeDbColors(
        actionPrimary = DarkActionPrimary,
        onActionPrimary = DarkOnActionPrimary,
        actionPrimaryHover = DarkActionPrimaryHover,
        accentContainer = DarkAccentContainer,
        onAccentContainer = DarkOnAccentContainer,
        success = DarkSuccess,
        onSuccess = DarkOnSuccess,
        successContainer = DarkSuccessContainer,
        onSuccessContainer = DarkOnSuccessContainer,
        warning = DarkWarning,
        onWarning = DarkOnWarning,
        warningContainer = DarkWarningContainer,
        onWarningContainer = DarkOnWarningContainer,
        info = DarkInfo,
        onInfo = DarkOnInfo,
        infoContainer = DarkInfoContainer,
        onInfoContainer = DarkOnInfoContainer,
        uq = DarkUq,
        onUq = DarkOnUq,
        uqContainer = DarkUqContainer,
        onUqContainer = DarkOnUqContainer,
    )
