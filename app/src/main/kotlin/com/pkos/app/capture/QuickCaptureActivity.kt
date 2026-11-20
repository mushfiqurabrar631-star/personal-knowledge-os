package com.pkos.app.capture

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.pkos.app.MainActivity
import com.pkos.app.PkosApplication
import com.pkos.app.di.LocalAppContainer
import com.pkos.app.feature.capture.CaptureSheetContent
import com.pkos.app.ui.theme.PkosTheme
import com.pkos.app.ui.theme.Spacing
import com.pkos.app.ui.theme.isDarkTheme
import com.pkos.core.data.capture.CaptureRequest
import com.pkos.core.data.capture.CaptureResult
import com.pkos.core.data.settings.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Opened from the floating bubble (R-02): pick a source, save locally, confirm, return to the previous app. */
class QuickCaptureActivity : AppCompatActivity() {
    private val result = MutableStateFlow<CaptureResult?>(null)
    private val busy = MutableStateFlow(false)
    private var last: CaptureRequest? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as PkosApplication).container
        setContent {
            val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
            val state by result.collectAsStateWithLifecycle()
            val saving by busy.collectAsStateWithLifecycle()
            CompositionLocalProvider(LocalAppContainer provides container) {
                PkosTheme(darkTheme = isDarkTheme(settings.themeMode)) {
                    val launcher = rememberCaptureLauncher(floating = true, onRequest = ::save)
                    Box(
                        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f))
                            .clickable(remember { MutableInteractionSource() }, null) { if (!saving) finish() }
                            .safeDrawingPadding(),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        if (state == null && !saving) {
                            Surface(shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.padding(Spacing.l)) {
                                Box(Modifier.padding(top = Spacing.xl)) { CaptureSheetContent(onPick = launcher.pick, onText = launcher.text) }
                            }
                        } else {
                            CaptureResultCard(
                                result = state,
                                onRetry = { last?.let(::save) },
                                onDone = { finish() },
                                onOpen = { id -> startActivity(MainActivity.openItemIntent(this@QuickCaptureActivity, id)); finish() },
                                onPin = { id -> lifecycleScope.launch { container.knowledge.setPinned(id, true) } },
                                onNote = { id, note -> lifecycleScope.launch { container.knowledge.setNote(id, note) } },
                                modifier = Modifier.padding(Spacing.l),
                            )
                        }
                    }
                }
            }
        }
    }

    private fun save(request: CaptureRequest) {
        val container = (application as PkosApplication).container
        last = request
        result.value = null
        busy.value = true
        lifecycleScope.launch {
            result.value = container.capture(request)
            busy.value = false
        }
    }
}
