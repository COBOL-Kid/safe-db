package com.safedb.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val SystemFonts = FontFamily.Default

private val HeadlineLarge = TextStyle(
    fontFamily = SystemFonts,
    fontWeight = FontWeight.Bold,
    fontSize = 28.sp,
    lineHeight = 34.sp,
    letterSpacing = (-0.3).sp,
)
private val HeadlineMedium = TextStyle(
    fontFamily = SystemFonts,
    fontWeight = FontWeight.Bold,
    fontSize = 24.sp,
    lineHeight = 30.sp,
    letterSpacing = (-0.25).sp,
)
private val TitleLarge = TextStyle(
    fontFamily = SystemFonts,
    fontWeight = FontWeight.SemiBold,
    fontSize = 20.sp,
    lineHeight = 26.sp,
    letterSpacing = (-0.2).sp,
)
private val TitleMedium = TextStyle(
    fontFamily = SystemFonts,
    fontWeight = FontWeight.SemiBold,
    fontSize = 16.sp,
    lineHeight = 22.sp,
    letterSpacing = (-0.1).sp,
)
private val TitleSmall = TextStyle(
    fontFamily = SystemFonts,
    fontWeight = FontWeight.SemiBold,
    fontSize = 14.sp,
    lineHeight = 20.sp,
    letterSpacing = 0.sp,
)
private val BodyLarge = TextStyle(
    fontFamily = SystemFonts,
    fontWeight = FontWeight.Normal,
    fontSize = 16.sp,
    lineHeight = 24.sp,
    letterSpacing = 0.sp,
)
private val BodyMedium = TextStyle(
    fontFamily = SystemFonts,
    fontWeight = FontWeight.Normal,
    fontSize = 14.sp,
    lineHeight = 20.sp,
    letterSpacing = 0.sp,
)
private val BodySmall = TextStyle(
    fontFamily = SystemFonts,
    fontWeight = FontWeight.Normal,
    fontSize = 13.sp,
    lineHeight = 18.sp,
    letterSpacing = 0.sp,
)
private val LabelLarge = TextStyle(
    fontFamily = SystemFonts,
    fontWeight = FontWeight.Medium,
    fontSize = 14.sp,
    lineHeight = 20.sp,
    letterSpacing = 0.1.sp,
)
private val LabelMedium = TextStyle(
    fontFamily = SystemFonts,
    fontWeight = FontWeight.Medium,
    fontSize = 12.sp,
    lineHeight = 16.sp,
    letterSpacing = 0.1.sp,
)
private val LabelSmall = TextStyle(
    fontFamily = SystemFonts,
    fontWeight = FontWeight.Medium,
    fontSize = 11.sp,
    lineHeight = 14.sp,
    letterSpacing = 0.1.sp,
)

/**
 * Uppercase technical micro-label for "FILTER WHERE", "INDEXES", AND/OR
 * connectors, kbd hints, and other quiet structural labels.
 */
val LabelMicro = TextStyle(
    fontFamily = SystemFonts,
    fontWeight = FontWeight.SemiBold,
    fontSize = 10.sp,
    lineHeight = 14.sp,
    letterSpacing = 0.6.sp,
)

val SafeDbTypography = Typography(
    headlineLarge = HeadlineLarge,
    headlineMedium = HeadlineMedium,
    titleLarge = TitleLarge,
    titleMedium = TitleMedium,
    titleSmall = TitleSmall,
    bodyLarge = BodyLarge,
    bodyMedium = BodyMedium,
    bodySmall = BodySmall,
    labelLarge = LabelLarge,
    labelMedium = LabelMedium,
    labelSmall = LabelSmall,
)
