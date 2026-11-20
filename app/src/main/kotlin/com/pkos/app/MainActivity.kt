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
    private val openItem = MutableStateFlow<String?>(null)

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_OPEN_ITEM)?.let { openItem.value = it }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        val container = (application as PkosApplication).container
        // Keep the splash only until the first settings read (decides onboarding vs home); no artificial delay.
        splash.setKeepOnScreenCondition { initialSettings.value == null }
        lifecycleScope.launch {
            container.categories.ensureStarterTaxonomy()
            // Re-queue anything left unfinished (app killed, update, reboot). Safe: the engine is idempotent.
            runCatching { container.scheduler.ensureScheduled(container.database.processingJobDao().itemsWithPendingJobs()) }
            val first = container.settings.settings.first()
            // Restore the owner's floating button after a reboot/app restart — only if the permission is still granted.
            if (first.floatingCaptureEnabled && com.pkos.app.capture.FloatingCaptureService.canRun(this@MainActivity)) {
                runCatching { com.pkos.app.capture.FloatingCaptureService.start(this@MainActivity) }
            }
            initialSettings.value = first
        }
        if (savedInstanceState == null) openItem.value = intent.getStringExtra(EXTRA_OPEN_ITEM)
        setContent { Root(container) }
    }

    @androidx.compose.runtime.Composable
    private fun Root(container: AppContainer) {
        val initial by initialSettings.collectAsStateWithLifecycle()
        val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
        val pendingItem by openItem.collectAsStateWithLifecycle()
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
                    openItemId = pendingItem,
                    onOpenItemConsumed = { openItem.value = null },
                )
            }
        }
    }

    companion object {
        const val EXTRA_OPEN_ITEM = "com.pkos.app.OPEN_ITEM"
        fun openItemIntent(context: android.content.Context, id: String) =
            android.content.Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_OPEN_ITEM, id)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)

        private val LIGHT_SCRIM = android.graphics.Color.argb(0xE6, 0xFF, 0xFF, 0xFF)
        private val DARK_SCRIM = android.graphics.Color.argb(0x80, 0x1B, 0x1B, 0x1B)
    }
}
