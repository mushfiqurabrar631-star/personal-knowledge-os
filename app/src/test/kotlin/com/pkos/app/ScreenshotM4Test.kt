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
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.pkos.app.di.AppContainer
import com.pkos.app.notion.NOTION_TOKEN
import com.pkos.core.data.capture.CaptureRequest
import com.pkos.core.data.capture.CaptureResult
import com.pkos.core.data.model.CaptureMethod
import com.pkos.core.notion.FakeNotion
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** M4 evidence (docs/screenshots/m4). Notion here is the in-memory FakeNotion; live checks run on the owner's phone. */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "bn-w400dp-h860dp-xxhdpi")
class ScreenshotM4Test {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val dir = (System.getProperty("pkos.screenshots.dir") ?: "build/screenshots/m1").replace("m1", "m4")
    private fun shot(name: String) { rule.waitForIdle(); captureScreenRoboImage("$dir/$name.png") }
    private fun waitFor(m: SemanticsMatcher) = rule.waitUntil(8_000) { rule.onAllNodes(m, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    @Before fun wm() = WorkManagerTestInitHelper.initializeTestWorkManager(rule.activity, Configuration.Builder().setExecutor(SynchronousExecutor()).build())

    private fun notionSettings(c: AppContainer) {
        rule.setContent { TestApp(c) }
        waitFor(hasTestTag("home_search"))
        rule.onNodeWithTag("tab_settings").performClick(); waitFor(hasTestTag("settings_list"))
        rule.onNodeWithTag("settings_list").performScrollToNode(hasText(rule.activity.getString(R.string.notion_settings_title)))
        rule.onAllNodes(hasText(rule.activity.getString(R.string.notion_settings_title)), useUnmergedTree = true)[0].performClick()
        waitFor(hasTestTag("notion_settings"))
    }

    @Test fun n01_tokenSetup() {
        val c = testContainer()
        notionSettings(c); shot("n01_notion_token_setup")
        rule.onNodeWithTag("notion_token_input").performTextInput("ntn_example")
        rule.onNodeWithTag("notion_token_save").performClick(); waitFor(hasTestTag("notion_check_ok"))
        rule.onNodeWithTag("switch_notion_sync").performScrollTo().performClick(); rule.waitForIdle()
        shot("n02_notion_connected")
    }

    @Test fun n03_failure() {
        val c = testContainer(notion = FakeNotion().apply { failNext("GET", "/data_sources", 403, "restricted_resource") })
        notionSettings(c)
        rule.onNodeWithTag("notion_token_input").performTextInput("ntn_example")
        rule.onNodeWithTag("notion_token_save").performClick(); waitFor(hasTestTag("notion_check_failed"))
        shot("n03_notion_no_access")
    }

    private fun synced(c: AppContainer, fake: FakeNotion): String = runBlocking {
        c.secrets.put(NOTION_TOKEN, "t"); c.categories.ensureStarterTaxonomy()
        val id = (c.capture(CaptureRequest(CaptureMethod.SHARE, text = "নতুন ব্যবসা শুরু করার মার্কেটিং আইডিয়া https://youtu.be/abc")) as CaptureResult.Saved).itemId
        c.processing.process(id, null); c.notionSync.sync(id); id
    }

    private fun openItem(c: AppContainer) {
        rule.setContent { TestApp(c) }
        waitFor(hasTestTag("home_search"))
        rule.onNodeWithTag("tab_inbox").performClick(); waitFor(hasText("নতুন ব্যবসা", substring = true))
        rule.onAllNodes(hasText("নতুন ব্যবসা", substring = true), useUnmergedTree = true)[0].performClick()
        waitFor(hasTestTag("item_detail"))
    }

    @Test fun n04_syncedItem() {
        val fake = FakeNotion(); val c = testContainer(notion = fake)
        synced(c, fake); openItem(c)
        rule.onNodeWithTag("item_detail").performScrollToNode(hasTestTag("notion_open")); shot("n04_item_synced_to_notion")
    }

    @Test fun n05_conflict() {
        val fake = FakeNotion(); val c = testContainer(notion = fake)
        val id = synced(c, fake)
        runBlocking {
            fake.userEdit(c.database.notionDao().mapping(id)!!.notionPageId, "Status", buildJsonObject { put("select", buildJsonObject { put("name", "Completed") }) })
            c.knowledge.setPinned(id, true); c.notionSync.sync(id)
        }
        openItem(c); waitFor(hasTestTag("notion_conflict")); shot("n05_item_notion_conflict")
    }
}
