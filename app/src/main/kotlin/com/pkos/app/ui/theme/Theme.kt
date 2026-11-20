package com.pkos.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.dp
import com.pkos.core.data.settings.ThemeMode

/** 4dp base grid; common steps 8, 12, 16, 24 (spec 2.6). */
object Spacing {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    val screen = 16.dp
}

/** Cards 16–20dp, buttons 12–16dp, chips pill-shaped (spec 2.6). */
val PkosShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Motion durations (spec 2.6: 120–220ms). Compose scales these with the system animator setting. */
object Motion {
    const val SHORT = 120
    const val MEDIUM = 180
    const val LONG = 220
}

@Composable
fun isDarkTheme(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun PkosTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalStatusColors provides if (darkTheme) DarkStatusColors else LightStatusColors) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = PkosTypography,
            shapes = PkosShapes,
            content = content,
        )
    }
}

object PkosColors {
    val status: StatusColors
        @Composable get() = LocalStatusColors.current
}
