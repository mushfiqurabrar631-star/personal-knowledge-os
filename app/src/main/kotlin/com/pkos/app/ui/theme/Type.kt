package com.pkos.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.pkos.app.R

/**
 * Hind Siliguri (SIL OFL 1.1) covers Bengali and Latin, so mixed বাংলা/English text shares one rhythm.
 * Line heights are slightly taller than Material defaults because Bengali conjuncts need the room.
 */
val PkosFontFamily = FontFamily(
    Font(R.font.hind_siliguri_regular, FontWeight.Normal),
    Font(R.font.hind_siliguri_medium, FontWeight.Medium),
    Font(R.font.hind_siliguri_semibold, FontWeight.SemiBold),
    Font(R.font.hind_siliguri_bold, FontWeight.Bold),
)

private fun style(size: Int, line: Int, weight: FontWeight, tracking: Double = 0.0) = TextStyle(
    fontFamily = PkosFontFamily,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.sp,
)

val PkosTypography = Typography(
    displaySmall = style(34, 44, FontWeight.SemiBold),
    headlineLarge = style(30, 40, FontWeight.SemiBold),
    headlineMedium = style(26, 36, FontWeight.SemiBold),
    headlineSmall = style(22, 32, FontWeight.SemiBold),
    titleLarge = style(20, 30, FontWeight.SemiBold),
    titleMedium = style(16, 26, FontWeight.SemiBold),
    titleSmall = style(14, 22, FontWeight.Medium),
    bodyLarge = style(16, 26, FontWeight.Normal),
    bodyMedium = style(15, 24, FontWeight.Normal),
    bodySmall = style(13, 20, FontWeight.Normal),
    labelLarge = style(14, 22, FontWeight.SemiBold),
    labelMedium = style(12, 18, FontWeight.Medium),
    labelSmall = style(11, 16, FontWeight.Medium, 0.2),
)
