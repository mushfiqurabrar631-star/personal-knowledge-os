package com.pkos.app.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// One calm primary accent (deep teal) on neutral surfaces (spec 2.6). Semantic colors are used only for status.
internal val LightColors = lightColorScheme(
    primary = Color(0xFF0F6B63), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFB4EFE5), onPrimaryContainer = Color(0xFF00201D),
    secondary = Color(0xFF4A635F), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCCE8E2), onSecondaryContainer = Color(0xFF051F1C),
    tertiary = Color(0xFF3F6082), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD1E4FF), onTertiaryContainer = Color(0xFF001D35),
    error = Color(0xFFBA1A1A), onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF6F8F7), onBackground = Color(0xFF171D1C),
    surface = Color(0xFFF6F8F7), onSurface = Color(0xFF171D1C),
    surfaceVariant = Color(0xFFDAE5E2), onSurfaceVariant = Color(0xFF3F4947),
    outline = Color(0xFF6F7977), outlineVariant = Color(0xFFC6D0CD),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF0F3F2),
    surfaceContainer = Color(0xFFEAEFED), surfaceContainerHigh = Color(0xFFE4E9E8),
    surfaceContainerHighest = Color(0xFFDEE4E2),
    inverseSurface = Color(0xFF2C3130), inverseOnSurface = Color(0xFFEEF2F0), inversePrimary = Color(0xFF8AD5CA),
)

internal val DarkColors = darkColorScheme(
    primary = Color(0xFF8AD5CA), onPrimary = Color(0xFF003733),
    primaryContainer = Color(0xFF00504A), onPrimaryContainer = Color(0xFFB4EFE5),
    secondary = Color(0xFFB1CCC6), onSecondary = Color(0xFF1C3531),
    secondaryContainer = Color(0xFF334B47), onSecondaryContainer = Color(0xFFCCE8E2),
    tertiary = Color(0xFFA8C9F0), onTertiary = Color(0xFF07325A),
    tertiaryContainer = Color(0xFF264869), onTertiaryContainer = Color(0xFFD1E4FF),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0F1413), onBackground = Color(0xFFDEE4E2),
    surface = Color(0xFF0F1413), onSurface = Color(0xFFDEE4E2),
    surfaceVariant = Color(0xFF3F4947), onSurfaceVariant = Color(0xFFBEC9C6),
    outline = Color(0xFF899390), outlineVariant = Color(0xFF3F4947),
    surfaceContainerLowest = Color(0xFF0A0F0E), surfaceContainerLow = Color(0xFF171D1C),
    surfaceContainer = Color(0xFF1B2120), surfaceContainerHigh = Color(0xFF252B2A),
    surfaceContainerHighest = Color(0xFF303635),
    inverseSurface = Color(0xFFDEE4E2), inverseOnSurface = Color(0xFF2C3130), inversePrimary = Color(0xFF0F6B63),
)

/** A container/content pair for status chips and banners. */
@Immutable
data class Tone(val container: Color, val content: Color)

/** Semantic status colors — used only to communicate state (spec 2.6). */
@Immutable
data class StatusColors(
    val neutral: Tone,
    val info: Tone,
    val success: Tone,
    val warning: Tone,
    val danger: Tone,
)

internal val LightStatusColors = StatusColors(
    neutral = Tone(Color(0xFFE4E9E8), Color(0xFF3F4947)),
    info = Tone(Color(0xFFD1E4FF), Color(0xFF0B3A63)),
    success = Tone(Color(0xFFCDEED3), Color(0xFF0E4B22)),
    warning = Tone(Color(0xFFFFE6A8), Color(0xFF5A4300)),
    danger = Tone(Color(0xFFFFDAD6), Color(0xFF8C1D18)),
)

internal val DarkStatusColors = StatusColors(
    neutral = Tone(Color(0xFF303635), Color(0xFFC6D0CD)),
    info = Tone(Color(0xFF1F3F60), Color(0xFFD1E4FF)),
    success = Tone(Color(0xFF1C4A2A), Color(0xFFB9E8C4)),
    warning = Tone(Color(0xFF4F3D00), Color(0xFFFFE08C)),
    danger = Tone(Color(0xFF6E1A15), Color(0xFFFFDAD6)),
)

val LocalStatusColors = staticCompositionLocalOf { LightStatusColors }
