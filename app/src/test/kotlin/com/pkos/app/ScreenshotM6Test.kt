package com.pkos.app

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
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
import com.pkos.core.data.capture.CaptureRequest
import com.pkos.core.data.capture.CaptureResult
import com.pkos.core.data.model.CaptureMethod
import com.pkos.core.data.model.ItemStatus
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** M6 evidence (docs/screenshots/m6): all on-phone, cloud AI off. */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "bn-w400dp-h860dp-xxhdpi")
class ScreenshotM6Test {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val dir = (System.getProperty("pkos.screenshots.dir") ?: "build/screenshots/m1").replace("m1", "m6")
    private fun shot(name: String) { rule.waitForIdle(); captureScreenRoboImage("$dir/$name.png") }
    private fun waitFor(m: SemanticsMatcher) = rule.waitUntil(8_000) { rule.onAllNodes(m, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    private fun s(id: Int) = rule.activity.getString(id)

    @Before fun wm() = WorkManagerTestInitHelper.initializeTestWorkManager(rule.activity, Configuration.Builder().setExecutor(SynchronousExecutor()).build())

    private fun seeded(): Pair<AppContainer, String> {
        val c = testContainer()
        fun save(t: String) = runBlocking { (c.capture(CaptureRequest(CaptureMethod.SHARE, text = t)) as CaptureResult.Saved).itemId }
        val a = save("মাসিক বাজেট পরিকল্পনা: সঞ্চয় বাড়ানোর ৫টি উপায়। TODO: ব্যাংকে নতুন সঞ্চয় হিসাব খুলতে হবে")
        val b = save("Kotlin Flow cold streams. They emit values only when collected.")
        val d = save("Kotlin coroutines structured concurrency guide")
        runBlocking {
            c.knowledge.editItem(a, "মাসিক বাজেট পরিকল্পনা", "সঞ্চয় বাড়ানোর উপায়", listOf("টাকা", "পরিকল্পনা"), ItemStatus.TO_LEARN)
            c.knowledge.editItem(b, "Kotlin Flow basics", "Cold streams", listOf("kotlin", "android"), ItemStatus.TO_LEARN)
            c.knowledge.editItem(d, "Kotlin coroutines guide", null, listOf("kotlin"), ItemStatus.IN_PROGRESS)
            c.actions.propose(a)
            c.graph.link(b, d)
            c.smartCollections.createSmart("Kotlin শেখা", com.pkos.core.data.features.CollectionRule(true, listOf(com.pkos.core.data.features.RuleCondition(com.pkos.core.data.features.RuleField.TAG, "kotlin"))))
            c.smartCollections.createManual("পড়ার তালিকা")
        }
        rule.setContent { TestApp(c) }
        waitFor(hasTestTag("home_search"))
        return c to b
    }
    private fun home(tag: String) { rule.onNodeWithTag("home_list").performScrollToNode(hasTestTag(tag)); rule.onNodeWithTag(tag).performClick() }

    @Test fun m01_searchByMeaning() {
        seeded()
        rule.onNodeWithTag("tab_search").performClick(); waitFor(hasTestTag("search_field"))
        rule.onNodeWithTag("search_field").performTextInput("money"); waitFor(hasText(s(R.string.search_reason_related)))
        shot("m01_search_meaning_offline")
    }

    @Test fun m02_askWithSources() {
        seeded()
        rule.onNodeWithTag("home_list").performScrollToNode(hasText(s(R.string.nav_ask_ai)))
        rule.onNodeWithText(s(R.string.nav_ask_ai)).performClick(); waitFor(hasTestTag("ask_intro"))
        rule.onNodeWithTag("ask_input").performTextInput("cold streams"); rule.onNodeWithTag("ask_send").performClick()
        waitFor(hasTestTag("ask_answer")); shot("m02_ask_offline_cited")
    }

    @Test fun m03_tasks() { seeded(); home("home_open_actions"); waitFor(hasTestTag("actions_list")); shot("m03_suggested_task_confirm") }

    @Test fun m04_collections() {
        seeded(); home("home_open_collections"); waitFor(hasTestTag("collection_Kotlin শেখা")); shot("m04_collections")
        rule.onNodeWithTag("collection_new_smart").performClick(); waitFor(hasTestTag("collection_name"))
        rule.onNodeWithTag("collection_name").performTextInput("জরুরি")
        rule.onNodeWithTag("collection_add_rule").performClick(); waitFor(hasTestTag("rule_text_0"))
        rule.onNodeWithTag("rule_text_0").performTextInput("kotlin"); shot("m05_smart_rule_builder")
    }

    @Test fun m06_timeline() { seeded(); home("home_open_timeline"); waitFor(hasTestTag("timeline_bucket")); shot("m06_timeline") }

    @Test fun m07_connections() {
        seeded()
        rule.onNodeWithTag("tab_inbox").performClick(); waitFor(hasText("Kotlin Flow basics"))
        rule.onAllNodes(hasText("Kotlin Flow basics"), useUnmergedTree = true)[0].performClick(); waitFor(hasTestTag("item_detail"))
        rule.onNodeWithTag("item_connections").performScrollTo(); shot("m07_item_related_connections")
        rule.onNodeWithTag("item_connections").performClick(); waitFor(hasTestTag("graph_canvas")); shot("m08_knowledge_graph")
    }

    @Test fun m09_exportAndSettings() {
        seeded()
        rule.onNodeWithTag("tab_settings").performClick(); waitFor(hasTestTag("settings_list"))
        rule.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("switch_reminders")); shot("m09_settings_knowledge_tools")
        rule.onNodeWithTag("settings_list").performScrollToNode(hasText(s(R.string.settings_export)))
        rule.onNodeWithText(s(R.string.settings_export)).performClick(); waitFor(hasTestTag("export_screen"))
        rule.onNodeWithTag("integrity_run").performClick(); waitFor(hasTestTag("integrity_ok")); shot("m10_export_integrity")
    }
}
