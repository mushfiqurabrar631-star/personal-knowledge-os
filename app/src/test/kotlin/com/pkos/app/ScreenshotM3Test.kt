package com.pkos.app

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.pkos.app.di.AppContainer
import com.pkos.core.data.capture.CaptureFile
import com.pkos.core.data.capture.CaptureRequest
import com.pkos.core.data.capture.CaptureResult
import com.pkos.core.data.model.CaptureMethod
import com.pkos.core.data.settings.AppSettings
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream

/** Screenshot evidence for M3 processing (docs/screenshots/m3). Items are captured and processed by the real engine. */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "bn-w400dp-h860dp-xxhdpi")
class ScreenshotM3Test {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val dir = (System.getProperty("pkos.screenshots.dir") ?: "build/screenshots/m1").replace("m1", "m3")
    private fun shot(name: String) { rule.waitForIdle(); captureScreenRoboImage("$dir/$name.png") }
    private fun waitFor(m: SemanticsMatcher) = rule.waitUntil(8_000) { rule.onAllNodes(m, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    private fun processed(c: AppContainer, text: String, files: List<CaptureFile> = emptyList()) = runBlocking {
        val id = (c.capture(CaptureRequest(CaptureMethod.SHARE, text = text, files = files)) as CaptureResult.Saved).itemId
        c.processing.process(id, null); id
    }

    private fun open(c: AppContainer, text: String) {
        rule.setContent { TestApp(c) }
        waitFor(hasTestTag("home_search"))
        rule.onNodeWithTag("tab_inbox").performClick(); waitFor(hasText(text, substring = true))
        rule.onAllNodes(hasText(text, substring = true), useUnmergedTree = true)[0].performClick()
        waitFor(hasTestTag("item_detail"))
    }

    @Test fun p01_inboxAfterProcessing() {
        val c = testContainer()
        processed(c, "Python SQL tutorial: data analysis with Excel and GitHub code https://example.com/sql")
        processed(c, "নতুন ব্যবসা শুরু করার মার্কেটিং আইডিয়া এবং বিক্রি বাড়ানোর উপায়।")
        processed(c, "hello there friend")
        rule.setContent { TestApp(c) }
        waitFor(hasTestTag("home_search")); shot("p01_home_after_processing")
        rule.onNodeWithTag("tab_inbox").performClick(); waitFor(hasText("hello there friend")); shot("p02_inbox_after_processing")
    }

    @Test fun p03_organizedItem() {
        val c = testContainer()
        processed(c, "নতুন ব্যবসা শুরু করার মার্কেটিং আইডিয়া এবং বিক্রি বাড়ানোর উপায়। দাম কম রাখার কৌশল।")
        open(c, "নতুন ব্যবসা"); shot("p03_item_ai_organized")
        rule.onNodeWithTag("item_detail").performScrollToNode(hasText(rule.activity.getString(R.string.item_section_history))); shot("p04_item_organized_details")
    }

    @Test fun p05_needsReview() {
        val c = testContainer()
        processed(c, "hello there friend")
        open(c, "hello there friend"); shot("p05_item_needs_review")
        rule.onNodeWithTag("ai_change_category").performScrollTo().performClick(); waitFor(hasTestTag("category_option_sys-ideas")); shot("p06_change_category")
    }

    @Test fun p07_fileText() {
        val c = testContainer()
        processed(c, "মিটিং নোট", listOf(CaptureFile("notes.txt", "text/plain", null) { ByteArrayInputStream("Q4 target report: RSO meeting plan and deadline for the project.".toByteArray()) }))
        open(c, "মিটিং নোট")
        rule.onNodeWithTag("item_detail").performScrollToNode(hasText(rule.activity.getString(R.string.item_section_file_text))); shot("p07_item_file_text")
    }

    @Test fun p08_aiSettings() {
        val c = testContainer(InMemorySettings(AppSettings(onboardingCompleted = true, cloudAiEnabled = true)))
        rule.setContent { TestApp(c) }
        waitFor(hasTestTag("home_search"))
        rule.onNodeWithTag("tab_settings").performClick(); waitFor(hasTestTag("settings_list"))
        rule.onNodeWithTag("settings_list").performScrollToNode(hasText(rule.activity.getString(R.string.ai_settings_title)))
        rule.onAllNodes(hasText(rule.activity.getString(R.string.ai_settings_title)), useUnmergedTree = true)[0].performClick()
        waitFor(hasTestTag("ai_settings")); shot("p08_ai_settings_no_key")
        c.secrets.put(com.pkos.app.processing.GEMINI_KEY, "x")
    }
}
