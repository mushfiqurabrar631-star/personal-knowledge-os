package com.pkos.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

/** M7 accessibility: WCAG 2.1 AA text contrast (≥ 4.5:1) for every text/background pair the app uses, light + dark. */
class ContrastTest {
    private fun ratio(a: Color, b: Color): Double {
        val (l1, l2) = listOf(a.luminance() + 0.05, b.luminance() + 0.05).sortedDescending()
        return (l1 / l2).toDouble()
    }

    @Test fun textPairsMeetAa() {
        val problems = mutableListOf<String>()
        for ((mode, c, st) in listOf(Triple("light", LightColors, LightStatusColors), Triple("dark", DarkColors, DarkStatusColors))) {
            val surfaces = mapOf("surface" to c.surface, "background" to c.background, "containerLow" to c.surfaceContainerLow,
                "container" to c.surfaceContainer, "containerHigh" to c.surfaceContainerHigh, "containerHighest" to c.surfaceContainerHighest)
            val pairs = buildMap {
                surfaces.forEach { (n, bg) ->
                    put("onSurface/$n", c.onSurface to bg); put("onSurfaceVariant/$n", c.onSurfaceVariant to bg)
                    put("primary/$n", c.primary to bg); put("error/$n", c.error to bg)
                }
                put("onPrimary/primary", c.onPrimary to c.primary); put("onPrimaryContainer", c.onPrimaryContainer to c.primaryContainer)
                put("onSecondaryContainer", c.onSecondaryContainer to c.secondaryContainer); put("onTertiaryContainer", c.onTertiaryContainer to c.tertiaryContainer)
                put("onErrorContainer", c.onErrorContainer to c.errorContainer); put("onError/error", c.onError to c.error)
                put("inverse", c.inverseOnSurface to c.inverseSurface)
                listOf("neutral" to st.neutral, "info" to st.info, "success" to st.success, "warning" to st.warning, "danger" to st.danger)
                    .forEach { (n, t) -> put("status-$n", t.content to t.container) }
            }
            pairs.forEach { (name, p) -> val r = ratio(p.first, p.second); if (r < 4.5) problems += "$mode $name ${"%.2f".format(r)}" }
        }
        println("CONTRAST problems=$problems")
        assertTrue(problems.joinToString(), problems.isEmpty())
    }
}
