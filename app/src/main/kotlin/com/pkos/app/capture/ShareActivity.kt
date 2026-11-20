package com.pkos.app.capture

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.pkos.app.MainActivity
import com.pkos.app.PkosApplication
import com.pkos.app.di.LocalAppContainer
import com.pkos.app.ui.theme.PkosTheme
import com.pkos.app.ui.theme.Spacing
import com.pkos.app.ui.theme.isDarkTheme
import com.pkos.core.data.capture.CaptureFailure
import com.pkos.core.data.capture.CaptureRequest
import com.pkos.core.data.capture.CaptureResult
import com.pkos.core.data.settings.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Receives ACTION_SEND / ACTION_SEND_MULTIPLE (R-01). Saves immediately — the user never has to press Save —
 * then shows a short confirmation with optional note / pin (spec 2.4). Works without network.
 */
class ShareActivity : AppCompatActivity() {
    private val result = MutableStateFlow<CaptureResult?>(null)
    private var request: CaptureRequest? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as PkosApplication).container
        if (savedInstanceState == null) {
            request = ShareIntentParser.parse(intent, contentResolver, sourcePackage())
            save()
        } else {
            finish(); return // a re-created activity has lost its URI grants; never save twice
        }
        setContent {
            val state by result.collectAsStateWithLifecycle()
            val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
            CompositionLocalProvider(LocalAppContainer provides container) {
                PkosTheme(darkTheme = isDarkTheme(settings.themeMode)) {
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f)).safeDrawingPadding(), contentAlignment = Alignment.BottomCenter) {
                        CaptureResultCard(
                            result = state,
                            onRetry = { save() },
                            onDone = { finish() },
                            onOpen = { id -> startActivity(MainActivity.openItemIntent(this@ShareActivity, id)); finish() },
                            onPin = { id -> lifecycleScope.launch { container.knowledge.setPinned(id, true) } },
                            onNote = { id, note -> lifecycleScope.launch { container.knowledge.setNote(id, note) } },
                            modifier = Modifier.padding(Spacing.l),
                        )
                    }
                }
            }
        }
    }

    private fun save() {
        val container = (application as PkosApplication).container
        val req = request
        result.value = null
        lifecycleScope.launch {
            result.value = if (req == null) CaptureResult.Failed(CaptureFailure.EMPTY) else container.capture(req)
        }
    }

    /** The sharing app's package, when Android provides it (used only to label the source). */
    private fun sourcePackage(): String? {
        if (Build.VERSION.SDK_INT >= 34) launchedFromPackage?.let { return it }
        val ref: Uri? = referrer
        return if (ref?.scheme == "android-app") ref.host else null
    }

    companion object {
        fun isShare(intent: Intent) = intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SEND_MULTIPLE
    }
}
