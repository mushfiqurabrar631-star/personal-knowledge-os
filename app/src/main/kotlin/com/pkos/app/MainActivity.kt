package com.pkos.app

import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.pkos.app.di.AppContainer
import com.pkos.app.di.LocalAppContainer
import com.pkos.app.navigation.PkosApp
import com.pkos.app.ui.theme.PkosTheme
import com.pkos.app.ui.theme.isDarkTheme
import com.pkos.core.data.settings.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** AppCompatActivity so per-app language (AppCompatDelegate) works on Android 8–12 too. */
class MainActivity : AppCompatActivity() {
    private val initialSettings = MutableStateFlow<AppSettings?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        val container = (application as PkosApplication).container
        // Keep the splash only until the first settings read (decides onboarding vs home); no artificial delay.
        splash.setKeepOnScreenCondition { initialSettings.value == null }
        lifecycleScope.launch {
            container.categories.ensureStarterTaxonomy()
            initialSettings.value = container.settings.settings.first()
        }
        setContent { Root(container) }
    }

    @androidx.compose.runtime.Composable
    private fun Root(container: AppContainer) {
        val initial by initialSettings.collectAsStateWithLifecycle()
        val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
        val first = initial ?: return
        val current = settings ?: first
        val dark = isDarkTheme(current.themeMode)
        DisposableEffect(dark) {
            enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark },
                navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { dark },
            )
            onDispose { }
        }
        CompositionLocalProvider(LocalAppContainer provides container) {
            PkosTheme(darkTheme = dark) {
                PkosApp(
                    startWithOnboarding = !first.onboardingCompleted,
                    onOnboardingFinished = { lifecycleScope.launch { container.settings.setOnboardingCompleted(true) } },
                )
            }
        }
    }

    private companion object {
        val LIGHT_SCRIM = android.graphics.Color.argb(0xE6, 0xFF, 0xFF, 0xFF)
        val DARK_SCRIM = android.graphics.Color.argb(0x80, 0x1B, 0x1B, 0x1B)
    }
}
