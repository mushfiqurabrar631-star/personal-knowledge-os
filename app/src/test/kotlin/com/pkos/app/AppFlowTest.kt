package com.pkos.app

import android.content.Context
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pkos.core.data.settings.AppSettings
import com.pkos.core.data.settings.ThemeMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** UI tests for navigation, capture sheet, empty/loading states, live data and large font (spec 13.1). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class AppFlowTest {
    @get:Rule val rule = createComposeRule()
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private fun s(id: Int, vararg args: Any) = ctx.getString(id, *args)

    private fun waitFor(matcher: SemanticsMatcher, timeout: Long = 10_000) =
        rule.waitUntil(timeout) { rule.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    private fun startHome(container: com.pkos.app.di.AppContainer = testContainer()) = container.also {
        rule.setContent { TestApp(it) }
        waitFor(hasTestTag("home_search"))
    }

    @Test fun onboardingGoesToHomeAndIsRemembered() {
        val settings = InMemorySettings(AppSettings(onboardingCompleted = false))
        val container = testContainer(settings)
        rule.setContent { TestApp(container, onboarding = true, onFinished = { runBlocking { settings.setOnboardingCompleted(true) } }) }
        rule.onNodeWithText(s(R.string.onboarding_1_title)).assertIsDisplayed()
        rule.onNodeWithText(s(R.string.onboarding_next)).performClick()
        rule.onNodeWithText(s(R.string.onboarding_2_title)).assertIsDisplayed()
        rule.onNodeWithText(s(R.string.onboarding_2_point_2)).assertIsDisplayed()
        rule.onNodeWithText(s(R.string.onboarding_next)).performClick()
        rule.onNodeWithText(s(R.string.onboarding_3_title)).assertIsDisplayed()
        rule.onNodeWithText(s(R.string.onboarding_start)).performClick()
        waitFor(hasTestTag("home_search"))
        assertTrue(runBlocking { settings.settings.first().onboardingCompleted })
    }

    @Test fun skipOnboardingGoesStraightHome() {
        val container = testContainer(InMemorySettings())
        rule.setContent { TestApp(container, onboarding = true) }
        rule.onNodeWithText(s(R.string.onboarding_skip)).performClick()
        waitFor(hasTestTag("home_search"))
    }

    @Test fun bottomNavigationReachesEveryTab() {
        startHome()
        rule.onNodeWithTag("tab_inbox").performClick(); waitFor(hasTestTag("inbox_filters"))
        rule.onNodeWithTag("tab_search").performClick(); waitFor(hasTestTag("search_field"))
        rule.onNodeWithTag("tab_settings").performClick(); waitFor(hasTestTag("settings_list"))
        rule.onNodeWithTag("tab_home").performClick(); waitFor(hasTestTag("home_search"))
    }

    @Test fun homeSearchFieldOpensSearch() {
        startHome()
        rule.onNodeWithTag("home_search").performClick()
        waitFor(hasTestTag("search_field"))
    }

    @Test fun captureSheetListsEverySourceAndSavesNothingYet() {
        val c = startHome()
        rule.onNodeWithTag("tab_capture").performClick()
        waitFor(hasTestTag("capture_sheet"))
        listOf("link", "text", "image", "file", "note", "paste").forEach {
            rule.onNodeWithTag("capture_option_$it", useUnmergedTree = true).assertIsNotEnabled()
        }
        rule.onNodeWithText(s(R.string.capture_staged_note), useUnmergedTree = true).assertExists()
        assertEquals(0, runBlocking { c.knowledge.counts().first().saved })
    }

    @Test fun emptyHomeShowsHonestEmptyAndLocalOnlyStates() {
        startHome()
        rule.onNodeWithText(s(R.string.home_empty_title)).assertIsDisplayed()
        rule.onNode(hasContentDescription(s(R.string.home_stat_saved) + ": 0")).assertIsDisplayed()
        rule.onNode(hasContentDescription(s(R.string.home_stat_last_backup) + ": " + s(R.string.home_stat_backup_never))).assertIsDisplayed()
        rule.onNode(hasContentDescription(s(R.string.home_sync_status_cd, s(R.string.health_local_only)))).assertExists()
    }

    @Test fun homeUpdatesLiveFromTheDatabase() {
        val c = startHome()
        runBlocking { Fixtures.sample.forEach { c.database.knowledgeDao().insert(it) } }
        waitFor(hasContentDescription(s(R.string.home_stat_saved) + ": 4"))
        rule.onNode(hasContentDescription(s(R.string.home_stat_needs_review) + ": 1")).assertExists()
        rule.onNodeWithText(ctx.resources.getQuantityString(R.plurals.home_action_banner, 1, 1)).assertIsDisplayed()
        rule.onNodeWithText("Ai দিয়ে marketing").assertIsDisplayed()
        rule.onNode(hasContentDescription(s(R.string.home_sync_status_cd, s(R.string.health_action_required)))).assertExists()
    }

    @Test fun inboxFiltersShowOnlyMatchingItems() {
        val c = testContainer()
        runBlocking { Fixtures.sample.forEach { c.database.knowledgeDao().insert(it) } }
        startHome(c)
        rule.onNodeWithTag("tab_inbox").performClick()
        waitFor(hasText("RSO meeting screenshot"))
        // Chip labels include live counts; wait until the count query has delivered before clicking.
        waitFor(hasText(s(R.string.inbox_filter_failed) + " · 1"))
        rule.onNodeWithText(s(R.string.inbox_filter_failed) + " · 1").performClick()
        waitFor(hasText("RSO meeting screenshot"))
        rule.onAllNodesWithText("OpenStax free textbooks").assertCountEquals(0)
        rule.onNodeWithTag("inbox_filters").performScrollToNode(hasText(s(R.string.inbox_filter_sync_pending)))
        rule.onNodeWithText(s(R.string.inbox_filter_sync_pending)).performClick()
        waitFor(hasTestTag("inbox_empty"))
    }

    @Test fun searchFindsLocalItemsAndExplainsNoResults() {
        val c = testContainer()
        runBlocking { Fixtures.sample.forEach { c.database.knowledgeDao().insert(it) } }
        startHome(c)
        rule.onNodeWithTag("tab_search").performClick()
        waitFor(hasTestTag("search_field"))
        rule.onNodeWithTag("search_field").performTextInput("বইয়ের")
        waitFor(hasText("ফ্রি বইয়ের ওয়েবসাইট বাংলায়"))
        rule.onNodeWithText(ctx.resources.getQuantityString(R.plurals.search_results_count, 1, 1)).assertExists()
        rule.onNodeWithTag("search_field").performTextReplacement("zzz-not-there")
        waitFor(hasTestTag("search_no_results"))
    }

    @Test fun itemDetailOpensAndRecordsLastOpened() {
        val c = testContainer()
        val item = Fixtures.sample.first()
        runBlocking { c.database.knowledgeDao().insert(item) }
        startHome(c)
        rule.onNodeWithText(item.title).performClick()
        waitFor(hasTestTag("item_detail"))
        rule.onNodeWithText(item.summary!!).assertIsDisplayed()
        rule.waitUntil(5_000) { runBlocking { c.database.knowledgeDao().observe(item.id).first()?.lastOpenedAt } != null }
        assertNotNull(runBlocking { c.database.knowledgeDao().observe(item.id).first()?.lastOpenedAt })
    }

    @Test fun topicsAndAskAiAreReachableFromHome() {
        startHome()
        rule.onNodeWithTag("home_list").performScrollToNode(hasText(s(R.string.nav_topics)))
        rule.onNodeWithText(s(R.string.nav_topics)).performClick()
        waitFor(hasTestTag("topics_list"))
        rule.onNodeWithText(s(R.string.cat_education)).assertExists()
        rule.onNodeWithTag("topics_list").performScrollToNode(hasText(s(R.string.cat_unsorted)))
        rule.onNodeWithContentDescriptionBack()
        waitFor(hasTestTag("home_list"))
        rule.onNodeWithTag("home_list").performScrollToNode(hasText(s(R.string.nav_ask_ai)))
        rule.onNodeWithText(s(R.string.nav_ask_ai)).performClick()
        waitFor(hasTestTag("ask_not_ready"))
        rule.onNodeWithTag("ask_input").assertIsNotEnabled()
    }

    @Test fun settingsPersistThemeAndPrivacyChoices() {
        val settings = InMemorySettings(AppSettings(onboardingCompleted = true))
        startHome(testContainer(settings))
        rule.onNodeWithTag("tab_settings").performClick()
        waitFor(hasTestTag("settings_list"))
        rule.onNodeWithTag("theme_2").performClick()
        rule.waitUntil(3_000) { settings.state.value.themeMode == ThemeMode.DARK }
        rule.onNodeWithTag("switch_cloud_ai").assertIsOff().performClick()
        rule.waitUntil(3_000) { settings.state.value.cloudAiEnabled }
        rule.onNodeWithTag("switch_cloud_ai").assertIsOn()
    }

    @Test fun integrationsAndBackupNeverClaimAConnection() {
        startHome()
        rule.onNodeWithTag("health").performClick()
        waitFor(hasTestTag("backup_status"))
        rule.onNodeWithText(s(R.string.backup_none)).assertIsDisplayed()
        rule.onNodeWithText(s(R.string.backup_not_configured)).assertIsDisplayed()
        rule.onNodeWithText(" " + s(R.string.backup_now), useUnmergedTree = true).assertExists()
    }

    @Test fun largeFontKeepsKeyActionsUsable() {
        RuntimeEnvironment.setFontScale(2.0f)
        startHome()
        rule.onNodeWithTag("home_capture").assertHasClickAction().assertHeightIsAtLeast(48.dp)
        rule.onNodeWithTag("home_search").assertHasClickAction().assertHeightIsAtLeast(48.dp)
        rule.onNodeWithTag("tab_capture").assertHeightIsAtLeast(48.dp)
        rule.onNodeWithTag("tab_inbox").assertHeightIsAtLeast(48.dp)
        rule.onNodeWithTag("tab_inbox").performClick(); waitFor(hasTestTag("inbox_filters"))
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onNodeWithContentDescriptionBack() =
        onNode(hasContentDescription(s(R.string.action_back))).performClick()
}
