package com.pkos.app

import android.content.Context
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.pkos.app.di.AppContainer
import com.pkos.app.features.ReminderScheduler
import com.pkos.core.data.capture.CaptureRequest
import com.pkos.core.data.capture.CaptureResult
import com.pkos.core.data.features.ActionService
import com.pkos.core.data.features.ExportOptions
import com.pkos.core.data.model.CaptureMethod
import com.pkos.core.data.model.ItemStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipFile

/** M6 UI flows: search by meaning offline, grounded Ask, task confirmation, smart collections, export, reminders opt-in. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class M6FlowTest {
    @get:Rule val rule = createComposeRule()
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private fun s(id: Int) = ctx.getString(id)
    private fun waitFor(m: SemanticsMatcher) = rule.waitUntil(10_000) { rule.onAllNodes(m, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    private fun pump(until: () -> Boolean) {
        repeat(100) { if (until()) return; rule.waitForIdle(); org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); Thread.sleep(50) }
    }
    private fun save(c: AppContainer, text: String) = runBlocking { (c.capture(CaptureRequest(CaptureMethod.SHARE, text = text)) as CaptureResult.Saved).itemId }
    private fun start(c: AppContainer) { rule.setContent { TestApp(c) }; waitFor(hasTestTag("home_search")) }
    private fun home(tag: String) { rule.onNodeWithTag("home_list").performScrollToNode(hasTestTag(tag)); rule.onNodeWithTag(tag).performClick() }

    @Before fun wm() = WorkManagerTestInitHelper.initializeTestWorkManager(ctx, Configuration.Builder().setExecutor(SynchronousExecutor()).build())

    @Test fun meaningSearchWorksOfflineAndShowsWhy() {
        val c = testContainer()
        val money = save(c, "Monthly budget plan for the family")
        save(c, "Kotlin coroutines guide")
        start(c)
        rule.onNodeWithTag("tab_search").performClick(); waitFor(hasTestTag("search_field"))
        rule.onNodeWithTag("search_meaning_note").assertExists() // meaning on by default, cloud off
        rule.onNodeWithTag("search_field").performTextInput("টাকা")
        waitFor(hasTestTag("search_reasons_$money"))
        rule.onAllNodes(hasText(s(R.string.search_reason_related)), useUnmergedTree = true)[0].assertExists()
        rule.onNodeWithTag("search_mode_keyword").performClick() // keyword only → no Bengali match
        pump { runBlocking { !c.settings.settings.first().semanticSearchEnabled } }
        waitFor(hasTestTag("search_no_results"))
        rule.onNodeWithTag("search_mode_semantic").performClick()
        waitFor(hasTestTag("search_reasons_$money"))
        rule.onNodeWithTag("search_filters").performScrollToNode(hasTestTag("filter_pinned")) // more filters since P1; the row scrolls
        rule.onNodeWithTag("filter_pinned").performClick() // nothing pinned
        waitFor(hasTestTag("search_no_results"))
    }

    @Test fun askAnswersOfflineFromSavedItemsWithOpenableSources() {
        val c = testContainer()
        save(c, "Flows are cold streams. They emit values only when collected.")
        start(c)
        rule.onNodeWithTag("home_list").performScrollToNode(hasText(s(R.string.nav_ask_ai)))
        rule.onNodeWithText(s(R.string.nav_ask_ai)).performClick(); waitFor(hasTestTag("ask_intro"))
        rule.onNodeWithTag("ask_input").performTextInput("cold streams")
        rule.onNodeWithTag("ask_send").performClick()
        waitFor(hasTestTag("ask_answer"))
        rule.onNodeWithText(s(R.string.ask_by_phone), useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("ask_source_1").performClick()
        waitFor(hasTestTag("item_detail"))
        rule.onNodeWithTag("ask_input").assertDoesNotExist()
    }

    @Test fun askSaysWhenNothingIsSaved() {
        val c = testContainer()
        save(c, "Gardening notes about tomatoes")
        start(c)
        rule.onNodeWithTag("home_list").performScrollToNode(hasText(s(R.string.nav_ask_ai)))
        rule.onNodeWithText(s(R.string.nav_ask_ai)).performClick(); waitFor(hasTestTag("ask_intro"))
        rule.onNodeWithTag("ask_input").performTextInput("quantum computing")
        rule.onNodeWithTag("ask_send").performClick()
        waitFor(hasTestTag("ask_no_sources"))
    }

    @Test fun suggestedTaskIsCreatedOnlyAfterConfirmationWithoutAGuessedDate() {
        val c = testContainer()
        val id = save(c, "TODO: renew the passport next month")
        runBlocking { c.actions.propose(id) }
        val a = runBlocking { c.database.featureDao().observeActionsFor(id).first().single() }
        assertEquals(ActionService.PROPOSED, a.status); assertNull(a.dueAt)
        start(c)
        home("home_open_actions"); waitFor(hasTestTag("action_${a.id}"))
        rule.onNodeWithTag("action_confirm_${a.id}").performScrollTo().performClick()
        pump { runBlocking { c.database.featureDao().action(a.id)!!.status == ActionService.CONFIRMED } }
        val done = runBlocking { c.database.featureDao().action(a.id)!! }
        assertEquals(ActionService.CONFIRMED, done.status); assertNull(done.dueAt)
        waitFor(hasTestTag("action_done_${a.id}"))
    }

    @Test fun smartCollectionFollowsItsRule() {
        val c = testContainer()
        val k = save(c, "kotlin flows")
        save(c, "unrelated note")
        runBlocking { c.knowledge.editItem(k, "Kotlin flows", null, listOf("kotlin"), ItemStatus.TO_LEARN) }
        start(c)
        home("home_open_collections"); waitFor(hasTestTag("collection_new_smart"))
        rule.onNodeWithTag("collection_new_smart").performClick(); waitFor(hasTestTag("collection_name"))
        rule.onNodeWithTag("collection_name").performTextInput("Kotlin")
        rule.onNodeWithTag("collection_add_rule").performClick(); waitFor(hasTestTag("rule_text_0"))
        rule.onNodeWithTag("rule_text_0").performTextInput("kotlin")
        rule.onNodeWithTag("collection_save").performScrollTo().performClick()
        waitFor(hasTestTag("collection_Kotlin"))
        rule.onNodeWithTag("collection_Kotlin").performClick()
        waitFor(hasText("Kotlin flows"))
        rule.onAllNodes(hasText("unrelated note"), useUnmergedTree = true).fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }
    }

    @Test fun exportBundleAndIntegrityCheck() {
        val c = testContainer()
        save(c, "export me")
        val vm = com.pkos.app.feature.knowledge.ExportViewModel(c)
        val f = File(ctx.cacheDir, "e.zip").apply { delete(); createNewFile() }
        vm.export(android.net.Uri.fromFile(f), ExportOptions())
        pump { vm.state.value is com.pkos.app.feature.knowledge.ExportState.Done || vm.state.value is com.pkos.app.feature.knowledge.ExportState.Failed }
        assertTrue(vm.state.value is com.pkos.app.feature.knowledge.ExportState.Done)
        ZipFile(f).use { z -> assertTrue(z.getEntry("pkos-export.json") != null && z.getEntry("items.csv") != null && z.getEntry("integrity-report.json") != null) }
        start(c)
        rule.onNodeWithTag("tab_settings").performClick(); waitFor(hasTestTag("settings_list"))
        rule.onNodeWithTag("settings_list").performScrollToNode(hasText(s(R.string.settings_export)))
        rule.onNodeWithText(s(R.string.settings_export)).performClick(); waitFor(hasTestTag("export_screen"))
        rule.onNodeWithTag("integrity_run").performClick()
        waitFor(hasTestTag("integrity_ok"))
    }

    @Test fun remindersAreOffUntilTheOwnerTurnsThemOn() {
        val c = testContainer()
        assertFalse(runBlocking { c.settings.settings.first().remindersEnabled })
        start(c)
        rule.onNodeWithTag("tab_settings").performClick(); waitFor(hasTestTag("settings_list"))
        rule.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("switch_reminders"))
        assertTrue(WorkManager.getInstance(ctx).getWorkInfosForUniqueWork(ReminderScheduler.NAME).get().none { !it.state.isFinished })
        rule.onNodeWithTag("switch_reminders").performClick()
        pump { runBlocking { c.settings.settings.first().remindersEnabled } }
        assertTrue(runBlocking { c.settings.settings.first().remindersEnabled })
        pump { WorkManager.getInstance(ctx).getWorkInfosForUniqueWork(ReminderScheduler.NAME).get().any { !it.state.isFinished } }
        assertTrue(WorkManager.getInstance(ctx).getWorkInfosForUniqueWork(ReminderScheduler.NAME).get().any { !it.state.isFinished })
    }

    @Test fun knowledgeMapShowsProjectsAndOpensItemsFromAConcept() {
        val c = testContainer()
        val b = save(c, "Kotlin Flow cold streams")
        val d = save(c, "Kotlin coroutines guide")
        runBlocking {
            c.knowledge.editItem(b, "Kotlin Flow basics", null, listOf("kotlin", "android"), com.pkos.core.data.model.ItemStatus.TO_LEARN)
            c.knowledge.editItem(d, "Kotlin coroutines guide", null, listOf("kotlin"), com.pkos.core.data.model.ItemStatus.TO_LEARN)
            for (id in listOf(b, d)) c.database.knowledgeDao().get(id)!!.let { c.database.knowledgeDao().update(it.copy(categoryId = "sys-programming")) }
        }
        start(c)
        home("home_open_map"); waitFor(hasTestTag("map_project"))
        rule.onNodeWithTag("map_concept_kotlin").performScrollTo().performClick()
        waitFor(hasText("• Kotlin Flow basics"))
        rule.onAllNodes(hasText("• Kotlin Flow basics"), useUnmergedTree = true)[0].performClick()
        waitFor(hasTestTag("item_detail"))
    }
}
