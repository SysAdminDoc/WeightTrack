package com.weighttrack.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp

val WeightTrackTypography = Typography()

/**
 * The one number a person opens the app to read. Tabular figures keep it from jittering as
 * the digits change, which matters on a screen that updates every time you step off the scale.
 */
val HeroNumberStyle = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.Light,
    fontSize = 68.sp,
    lineHeight = 72.sp,
    letterSpacing = (-2).sp,
    textAlign = TextAlign.Center,
)

val HeroUnitStyle = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.Medium,
    fontSize = 22.sp,
    lineHeight = 26.sp,
    letterSpacing = 0.5.sp,
)

val StatValueStyle = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.SemiBold,
    fontSize = 22.sp,
    lineHeight = 26.sp,
    letterSpacing = (-0.3).sp,
)

val StatLabelStyle = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.Medium,
    fontSize = 12.sp,
    lineHeight = 16.sp,
    letterSpacing = 0.6.sp,
)
