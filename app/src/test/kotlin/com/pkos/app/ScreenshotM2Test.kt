package com.pkos.app

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.pkos.app.capture.CaptureResultCard
import com.pkos.app.di.LocalAppContainer
import com.pkos.app.feature.capture.CaptureSheetContent
import com.pkos.app.ui.theme.PkosTheme
import com.pkos.app.ui.theme.Spacing
import com.pkos.core.data.capture.CaptureFailure
import com.pkos.core.data.capture.CaptureFile
import com.pkos.core.data.capture.CaptureRequest
import com.pkos.core.data.capture.CaptureResult
import com.pkos.core.data.model.CaptureMethod
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream

/** Screenshot evidence for M2 capture (docs/screenshots/m2). Every saved item here went through the real capture path. */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "bn-w400dp-h860dp-xxhdpi")
class ScreenshotM2Test {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val dir = (System.getProperty("pkos.screenshots.dir") ?: "build/screenshots/m1").replace("m1", "m2")
    private fun shot(name: String) { rule.waitForIdle(); captureScreenRoboImage("$dir/$name.png") }
    private fun waitFor(m: SemanticsMatcher) = rule.waitUntil(8_000) { rule.onAllNodes(m, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    private fun overlay(result: CaptureResult?) {
        val c = testContainer()
        rule.setContent {
            CompositionLocalProvider(LocalAppContainer provides c) {
                PkosTheme {
                    Box(Modifier.fillMaxSize().padding(Spacing.l), contentAlignment = Alignment.BottomCenter) {
                        CaptureResultCard(result, {}, {}, {}, {}, { _, _ -> })
                    }
                }
            }
        }
    }

    @Test fun m01_sheetEnabled() {
        rule.setContent { TestApp(testContainer()) }
        waitFor(hasTestTag("home_search"))
        rule.onNodeWithTag("tab_capture").performClick(); waitFor(hasTestTag("capture_sheet")); shot("m01_capture_sheet")
        rule.onNodeWithTag("capture_option_link").performClick(); waitFor(hasTestTag("capture_editor"))
        rule.onNodeWithTag("capture_text_input").performTextInput("https://www.youtube.com/watch?v=abc123")
        shot("m02_link_editor")
        rule.onNodeWithTag("capture_text_save").performClick()
        waitFor(hasTestTag("snackbar"))
        waitFor(androidx.compose.ui.test.hasContentDescription(rule.activity.getString(R.string.home_stat_saved) + ": 1")); shot("m03_saved_snackbar")
    }

    @Test fun m04_shareSaved() = overlay(CaptureResult.Saved("x", "youtube.com — দারুণ টিউটোরিয়াল", null, 0)).also { shot("m04_share_saved") }
    @Test fun m05_shareDuplicate() = overlay(CaptureResult.Saved("x", "screenshot.png", "y", 1)).also { shot("m05_share_duplicate") }
    @Test fun m06_shareFailed() = overlay(CaptureResult.Failed(CaptureFailure.UNREADABLE)).also { shot("m06_share_failed") }
    @Test fun m07_shareSaving() = overlay(null).also { shot("m07_share_saving") }

    @Test fun m08_itemDetailWithFile() {
        val c = testContainer()
        runBlocking {
            c.capture(
                CaptureRequest(
                    CaptureMethod.SHARE, text = "RSO মিটিং নোট https://example.com/report?utm_source=fb", sourceApp = "com.facebook.katana",
                    files = listOf(CaptureFile("meeting.png", "image/png", null) { ByteArrayInputStream(ByteArray(48_000) { 3 }) }),
                ),
            )
        }
        rule.setContent { TestApp(c) }
        waitFor(hasTestTag("home_search"))
        rule.onNodeWithTag("tab_inbox").performClick(); waitFor(hasText("RSO", substring = true))
        rule.onAllNodes(hasText("RSO", substring = true), useUnmergedTree = true)[0].performClick()
        waitFor(hasTestTag("item_detail")); rule.onNodeWithTag("item_pin").performClick(); shot("m08_item_detail")
        rule.onNodeWithTag("attachment").performScrollTo(); shot("m09_item_detail_files")
    }

    @Test fun m10_settingsFloating() {
        rule.setContent { TestApp(testContainer()) }
        waitFor(hasTestTag("home_search"))
        rule.onNodeWithTag("tab_settings").performClick(); waitFor(hasTestTag("settings_list"))
        rule.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("switch_floating")); shot("m10_settings_floating")
        rule.onNodeWithTag("switch_floating").performClick(); waitFor(hasText(rule.activity.getString(R.string.floating_permission_open))); shot("m11_overlay_permission_explained")
    }

    @Test fun m12_quickCaptureCard() {
        val c = testContainer()
        rule.setContent {
            CompositionLocalProvider(LocalAppContainer provides c) {
                PkosTheme {
                    Box(Modifier.fillMaxSize().padding(Spacing.l), contentAlignment = Alignment.BottomCenter) {
                        Surface(shape = MaterialTheme.shapes.extraLarge) { Box(Modifier.padding(top = Spacing.xl)) { CaptureSheetContent() } }
                    }
                }
            }
        }
        shot("m12_floating_quick_capture")
    }
}
