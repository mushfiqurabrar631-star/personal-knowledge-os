package com.pkos.app

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.pkos.core.data.capture.CaptureRequest
import com.pkos.core.data.capture.CaptureResult
import com.pkos.core.data.model.CaptureMethod
import com.pkos.core.data.model.ItemStatus
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** M7 evidence (docs/screenshots/m7): Bengali UI at 2× font on a small 320dp phone. */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "bn-w320dp-h640dp-xhdpi")
class ScreenshotM7Test {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val dir = (System.getProperty("pkos.screenshots.dir") ?: "build/screenshots/m1").replace("m1", "m7")
    private fun shot(name: String) { rule.waitForIdle(); captureScreenRoboImage("$dir/$name.png") }
    private fun waitFor(m: SemanticsMatcher) = rule.waitUntil(20_000) { rule.onAllNodes(m, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    @Before fun setUp() {
        RuntimeEnvironment.setFontScale(2f)
        WorkManagerTestInitHelper.initializeTestWorkManager(rule.activity, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        val c = testContainer()
        val a = runBlocking { (c.capture(CaptureRequest(CaptureMethod.SHARE, text = "TODO: ব্যাংকে নতুন সঞ্চয় হিসাব খুলতে হবে। মাসিক বাজেট পরিকল্পনা")) as CaptureResult.Saved).itemId }
        runBlocking { c.knowledge.editItem(a, "মাসিক বাজেট পরিকল্পনা ও সঞ্চয়", "সঞ্চয় বাড়ানোর উপায়", listOf("টাকা"), ItemStatus.TO_LEARN); c.actions.propose(a) }
        rule.setContent { TestApp(c) }
        waitFor(hasTestTag("home_search"))
    }

    @Test fun x01_homeLargeFont() { shot("x01_home_2x_font_320dp") }
    @Test fun x02_searchLargeFont() {
        rule.onNodeWithTag("tab_search").performClick(); waitFor(hasTestTag("search_field"))
        rule.onNodeWithTag("search_field").performTextInput("বাজেট"); waitFor(hasTestTag("search_results")); shot("x02_search_2x_font_320dp")
    }
    @Test fun x03_itemLargeFont() {
        rule.onNodeWithTag("tab_inbox").performClick(); waitFor(hasText("মাসিক বাজেট পরিকল্পনা ও সঞ্চয়"))
        rule.onAllNodes(hasText("মাসিক বাজেট পরিকল্পনা ও সঞ্চয়"), useUnmergedTree = true)[0].performClick(); waitFor(hasTestTag("item_detail")); shot("x03_item_2x_font_320dp")
    }
    @Test fun x04_settingsLargeFont() {
        rule.onNodeWithTag("tab_settings").performClick(); waitFor(hasTestTag("settings_list"))
        rule.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("switch_reminders")); shot("x04_settings_2x_font_320dp")
    }
}
