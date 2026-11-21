package com.pkos.app

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.activity.ComponentActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.pkos.app.di.AppContainer
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshot evidence for M1 (docs/screenshots/m1). Rendered by Robolectric native graphics from the real app
 * composition over a real in-memory Room database. Screens "with items" use test rows from [Fixtures]
 * because capture arrives in M2.
 */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "bn-w400dp-h860dp-xxhdpi")
class ScreenshotTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val dir = System.getProperty("pkos.screenshots.dir") ?: "build/screenshots/m1"
    private fun shot(name: String) { rule.waitForIdle(); captureScreenRoboImage("$dir/$name.png") }
    private fun waitFor(m: SemanticsMatcher) = rule.waitUntil(5_000) { rule.onAllNodes(m, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    private fun s(id: Int, vararg a: Any) = rule.activity.getString(id, *a)

    private fun waitForCount() = waitFor(androidx.compose.ui.test.hasContentDescription(s(R.string.home_stat_saved) + ": 4"))
    private fun scrollHomeTo(text: String) = rule.onNodeWithTag("home_list").performScrollToNode(hasText(text))

    private fun withItems(): AppContainer = testContainer().also { c -> runBlocking { Fixtures.sample.forEach { c.database.knowledgeDao().insert(it) } } }

    private fun home(container: AppContainer = testContainer(), dark: Boolean = false) {
        rule.setContent { TestApp(container, dark = dark) }
        waitFor(hasTestTag("home_search"))
    }

    @Test fun s01_onboarding() {
        rule.setContent { TestApp(testContainer(InMemorySettings()), onboarding = true) }
        shot("01_onboarding_1")
        rule.onNodeWithText(s(R.string.onboarding_next)).performClick(); shot("02_onboarding_2")
        rule.onNodeWithText(s(R.string.onboarding_next)).performClick(); shot("03_onboarding_3")
    }

    @Test fun s04_homeEmptyLight() { home(); shot("04_home_empty_light") }

    @Test @Config(qualifiers = "+night") fun s05_homeEmptyDark() { home(dark = true); shot("05_home_empty_dark") }

    @Test fun s06_homeWithItems() {
        home(withItems()); waitForCount(); shot("06_home_with_items")
        scrollHomeTo("RSO meeting screenshot"); shot("06b_home_recent_items")
        rule.onNodeWithTag("home_list").performScrollToNode(hasText(s(R.string.nav_ask_ai))); shot("07_home_scrolled")
    }

    @Test @Config(qualifiers = "+night") fun s08_homeWithItemsDark() { home(withItems(), dark = true); waitForCount(); scrollHomeTo("Ai দিয়ে marketing"); shot("08_home_with_items_dark") }

    @Test fun s09_captureSheet() { home(); rule.onNodeWithTag("tab_capture").performClick(); waitFor(hasTestTag("capture_sheet")); shot("09_capture_sheet") }

    @Test fun s10_inbox() {
        home(withItems()); rule.onNodeWithTag("tab_inbox").performClick(); waitFor(hasText("RSO meeting screenshot")); shot("10_inbox")
        rule.onNodeWithTag("inbox_filters").performScrollToNode(hasText(s(R.string.inbox_filter_sync_pending)))
        rule.onNodeWithText(s(R.string.inbox_filter_sync_pending)).performClick(); waitFor(hasTestTag("inbox_empty")); shot("11_inbox_empty_filter")
    }

    @Test fun s12_search() {
        home(withItems()); rule.onNodeWithTag("tab_search").performClick(); waitFor(hasTestTag("search_field")); shot("12_search_start")
        rule.onNodeWithTag("search_field").performTextInput("marketing"); waitFor(hasText("Ai দিয়ে marketing")); shot("13_search_results")
    }

    @Test fun s14_topics() {
        home(); rule.onNodeWithTag("home_list").performScrollToNode(hasText(s(R.string.nav_topics)))
        rule.onNodeWithText(s(R.string.nav_topics)).performClick(); waitFor(hasTestTag("topics_list")); shot("14_topics")
    }

    @Test fun s15_askAi() {
        home(); rule.onNodeWithTag("home_list").performScrollToNode(hasText(s(R.string.nav_ask_ai)))
        rule.onNodeWithText(s(R.string.nav_ask_ai)).performClick(); waitFor(hasTestTag("ask_intro")); shot("15_ask_ai")
    }

    @Test fun s16_itemDetail() {
        home(withItems()); waitForCount(); scrollHomeTo("OpenStax free textbooks")
        rule.onNodeWithText("OpenStax free textbooks").performClick(); waitFor(hasTestTag("item_detail")); shot("16_item_detail")
    }

    @Test fun s17_settings() {
        home(); rule.onNodeWithTag("tab_settings").performClick(); waitFor(hasTestTag("settings_list")); shot("17_settings")
        rule.onNodeWithTag("settings_list").performScrollToNode(hasText(s(R.string.settings_replay_onboarding))); shot("18_settings_scrolled")
    }

    @Test fun s19_integrationsAndBackup() {
        home(); rule.onNodeWithTag("tab_settings").performClick(); waitFor(hasTestTag("settings_list"))
        rule.onNodeWithText(s(R.string.settings_integrations)).performClick(); waitFor(hasTestTag("integration_notion")); shot("19_integrations")
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        waitFor(hasTestTag("settings_list"))
        rule.onNodeWithText(s(R.string.settings_backup_sync)).performClick(); waitFor(hasTestTag("backup_status")); shot("20_backup_sync")
    }

    @Test fun s21_largeFont() { RuntimeEnvironment.setFontScale(1.6f); home(withItems()); waitForCount(); shot("21_home_large_font") }

    @Test @Config(qualifiers = "en-w400dp-h860dp-xxhdpi") fun s22_english() { home(withItems()); waitForCount(); shot("22_home_english") }

    @Test fun s23_smallPhone() {
        RuntimeEnvironment.setQualifiers("w320dp-h640dp-xhdpi"); home(withItems()); waitForCount(); shot("23_home_small_phone")
    }

}
