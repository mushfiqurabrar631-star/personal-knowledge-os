package com.pkos.app

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.pkos.app.di.AppContainer
import com.pkos.core.data.capture.CaptureRequest
import com.pkos.core.data.capture.CaptureResult
import com.pkos.core.data.model.CaptureMethod
import com.pkos.core.data.model.ItemStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * M7 accessibility audit (spec 2.6: 48dp targets, screen-reader labels) over the main screens. Walks the semantics tree
 * TalkBack uses: every clickable node needs a ≥48×48dp touch area and something to announce (text, description or
 * editable text). Also runs at 2× font on a small 320dp phone, where nothing may become unreachable.
 */
abstract class A11yAuditBase(private val fontScale: Float) {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    protected val ctx: Context = ApplicationProvider.getApplicationContext()
    private val problems = linkedSetOf<String>()
    private fun s(id: Int) = ctx.getString(id)
    private fun waitFor(m: SemanticsMatcher) = rule.waitUntil(15_000) { rule.onAllNodes(m, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    @Before fun setUp() {
        RuntimeEnvironment.setFontScale(fontScale)
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
    }

    private fun label(n: SemanticsNode): String? {
        val c = n.config
        return c.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()?.takeIf { it.isNotBlank() }
            ?: c.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }?.takeIf { it.isNotBlank() }
            ?: c.getOrNull(SemanticsProperties.EditableText)?.text?.let { "edit:$it" }
            ?: c.getOrNull(SemanticsActions.SetText)?.let { "textfield" }
    }

    private fun audit(screen: String) {
        rule.waitForIdle()
        val density = ctx.resources.displayMetrics.density
        for (n in rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick)).fetchSemanticsNodes()) {
            if (n.config.getOrNull(SemanticsProperties.Disabled) != null) continue
            val b = n.touchBoundsInRoot
            if (b.width <= 0f || b.height <= 0f) continue // off-screen in a lazy list
            val tag = n.config.getOrNull(SemanticsProperties.TestTag) ?: "#${n.id}"
            val name = label(n)
            if (name == null) problems += "$screen: unlabeled clickable [$tag]"
            // touchBoundsInRoot is clipped by the viewport for half-visible list rows; the node's own size is not.
            val w = maxOf(b.width, n.size.width.toFloat()) / density; val h = maxOf(b.height, n.size.height.toFloat()) / density
            if (w < 47.5f || h < 47.5f) problems += "$screen: small target ${"%.0f".format(w)}x${"%.0f".format(h)}dp [$tag] '${name?.take(30)}'"
        }
    }

    private fun scrollAudit(screen: String, listTag: String? = null, steps: Int = 6) {
        audit(screen)
        val list = listTag?.let { rule.onNodeWithTag(it) } ?: rule.onAllNodes(hasScrollAction())[0]
        repeat(steps) { runCatching { list.performTouchInput { swipeUp() } }; audit(screen) }
    }

    private fun seeded(): AppContainer {
        val c = testContainer()
        fun save(t: String) = runBlocking { (c.capture(CaptureRequest(CaptureMethod.SHARE, text = t)) as CaptureResult.Saved).itemId }
        val a = save("TODO: ব্যাংকে নতুন সঞ্চয় হিসাব খুলতে হবে। মাসিক বাজেট পরিকল্পনা")
        val b = save("Kotlin Flow cold streams https://example.com/flow")
        val d = save("Kotlin coroutines guide")
        runBlocking {
            c.knowledge.editItem(b, "Kotlin Flow basics", "Cold streams", listOf("kotlin"), ItemStatus.TO_LEARN)
            c.knowledge.editItem(d, "Kotlin coroutines guide", null, listOf("kotlin"), ItemStatus.IN_PROGRESS)
            c.actions.propose(a); c.graph.link(b, d)
            c.smartCollections.createManual("পড়ার তালিকা")
        }
        return c
    }

    private fun back() { rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }; rule.waitForIdle() }

    @Test fun mainScreensAreLabeledWithLargeTouchTargets() {
        val c = seeded()
        rule.setContent { TestApp(c) }
        waitFor(hasTestTag("home_search"))
        scrollAudit("home", "home_list", 8)
        rule.onNodeWithTag("tab_inbox").performClick(); waitFor(hasText("Kotlin Flow basics")); scrollAudit("inbox", null, 3)
        rule.onNodeWithTag("tab_search").performClick(); waitFor(hasTestTag("search_field"))
        rule.onNodeWithTag("search_field").performTextInput("kotlin")
        waitFor(hasTestTag("search_results")); audit("search")
        rule.onNodeWithTag("tab_settings").performClick(); waitFor(hasTestTag("settings_list")); scrollAudit("settings", "settings_list", 10)

        rule.onNodeWithTag("tab_inbox").performClick(); waitFor(hasText("Kotlin Flow basics"))
        rule.onAllNodes(hasText("Kotlin Flow basics"), useUnmergedTree = true)[0].performClick(); waitFor(hasTestTag("item_detail"))
        waitFor(hasTestTag("item_related")); scrollAudit("item", null, 8)
        rule.onNodeWithTag("item_connections").performScrollTo().performClick()
        waitFor(hasTestTag("connections_screen")); scrollAudit("connections", "connections_screen", 3)
        back(); waitFor(hasTestTag("item_detail"))
        rule.onNodeWithTag("item_menu").performClick(); waitFor(hasTestTag("item_local_only")); audit("item-menu")
        back(); rule.waitForIdle()
        if (rule.onAllNodes(hasTestTag("tab_home")).fetchSemanticsNodes().isEmpty()) back()

        rule.onNodeWithTag("tab_home").performClick(); waitFor(hasTestTag("home_list"))
        rule.onNodeWithTag("home_list").performScrollToNode(hasTestTag("home_open_actions")); rule.onNodeWithTag("home_open_actions").performClick()
        waitFor(hasTestTag("actions_list")); scrollAudit("tasks", "actions_list", 3); back()
        rule.onNodeWithTag("home_list").performScrollToNode(hasTestTag("home_open_collections")); rule.onNodeWithTag("home_open_collections").performClick()
        waitFor(hasTestTag("collection_new_smart")); audit("collections")
        rule.onNodeWithTag("collection_new_smart").performClick(); waitFor(hasTestTag("collection_add_rule"))
        rule.onNodeWithTag("collection_add_rule").performClick(); waitFor(hasTestTag("rule_0")); scrollAudit("collection-editor", "collection_editor", 2); back(); back()
        rule.onNodeWithTag("home_list").performScrollToNode(hasTestTag("home_open_timeline")); rule.onNodeWithTag("home_open_timeline").performClick()
        waitFor(hasTestTag("timeline_bucket")); audit("timeline"); back()
        rule.onNodeWithTag("home_list").performScrollToNode(hasText(s(R.string.nav_ask_ai))); rule.onNodeWithText(s(R.string.nav_ask_ai)).performClick()
        waitFor(hasTestTag("ask_intro")); audit("ask"); back()
        rule.onNodeWithTag("home_list").performScrollToNode(hasText(s(R.string.nav_topics))); rule.onNodeWithText(s(R.string.nav_topics)).performClick()
        waitFor(hasTestTag("topics_list")); scrollAudit("topics", "topics_list", 3); back()

        rule.onNodeWithTag("tab_capture").performClick(); waitFor(hasTestTag("capture_sheet")); audit("capture-sheet")
        back()
        rule.onNodeWithTag("tab_settings").performClick(); waitFor(hasTestTag("settings_list"))
        for ((label, tag) in listOf(R.string.settings_backup_sync to "backup_key", R.string.notion_settings_title to "notion_settings", R.string.ai_settings_title to "ai_settings", R.string.settings_export to "export_screen", R.string.privacy_title to "privacy_screen")) {
            rule.onNodeWithTag("settings_list").performScrollToNode(hasText(s(label)))
            rule.onAllNodes(hasText(s(label)), useUnmergedTree = true)[0].performClick(); waitFor(hasTestTag(tag))
            scrollAudit(tag, null, 4); back(); waitFor(hasTestTag("settings_list"))
        }
        println("A11Y fontScale=$fontScale problems(${problems.size}):\n" + problems.joinToString("\n"))
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }
}

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class M7AccessibilityTest : A11yAuditBase(1f)

/** Smallest common phone width with the largest system font. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w320dp-h640dp-xhdpi")
class M7AccessibilityLargeFontTest : A11yAuditBase(2f)
