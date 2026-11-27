package com.pkos.app

import android.content.Context
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
import com.pkos.app.notion.NOTION_TOKEN
import com.pkos.core.data.capture.CaptureRequest
import com.pkos.core.data.capture.CaptureResult
import com.pkos.core.data.model.CaptureMethod
import com.pkos.core.data.model.SyncState
import com.pkos.core.data.notion.NotionSyncResult
import com.pkos.core.notion.FakeNotion
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class NotionFlowTest {
    @get:Rule val rule = createComposeRule()
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private fun waitFor(m: SemanticsMatcher) = rule.waitUntil(10_000) { rule.onAllNodes(m, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    @Before fun wm() = WorkManagerTestInitHelper.initializeTestWorkManager(ctx, Configuration.Builder().setExecutor(SynchronousExecutor()).build())

    private fun openNotionSettings(c: AppContainer) {
        rule.setContent { TestApp(c) }
        waitFor(hasTestTag("home_search"))
        rule.onNodeWithTag("tab_settings").performClick(); waitFor(hasTestTag("settings_list"))
        rule.onNodeWithTag("settings_list").performScrollToNode(hasText(ctx.getString(R.string.notion_settings_title)))
        rule.onAllNodes(hasText(ctx.getString(R.string.notion_settings_title)), useUnmergedTree = true)[0].performClick()
        waitFor(hasTestTag("notion_settings"))
    }

    @Test fun tokenIsStoredOnlyInSecretStoreAndSyncNeedsAVerifiedConnection() {
        val c = testContainer()
        openNotionSettings(c)
        rule.onNodeWithTag("switch_notion_sync").assertIsNotEnabled()
        rule.onNodeWithTag("notion_token_input").performTextInput("ntn_live_check_token")
        rule.onNodeWithTag("notion_token_save").performClick()
        waitFor(hasTestTag("notion_check_ok"))
        assertEquals("ntn_live_check_token", c.secrets.get(NOTION_TOKEN))
        assertFalse(runBlocking { c.settings.settings.first() }.toString().contains("ntn_live_check_token"))
        rule.onNodeWithTag("switch_notion_sync").performScrollTo().assertIsEnabled().performClick()
        rule.waitUntil(5_000) { runBlocking { c.settings.settings.first().notionSyncEnabled } }
        // Removing the token turns sync off.
        rule.onNodeWithTag("notion_token_remove").performScrollTo().performClick()
        rule.waitUntil(5_000) { runBlocking { !c.settings.settings.first().notionSyncEnabled } }
        assertEquals(null, c.secrets.get(NOTION_TOKEN))
    }

    @Test fun badTokenShowsHonestFailure() {
        val fake = FakeNotion().apply { failNext("GET", "/data_sources", 401, "unauthorized") }
        val c = testContainer(notion = fake)
        openNotionSettings(c)
        rule.onNodeWithTag("notion_token_input").performTextInput("wrong")
        rule.onNodeWithTag("notion_token_save").performClick()
        waitFor(hasTestTag("notion_check_failed"))
        waitFor(hasText(ctx.getString(R.string.notion_reason_token)))
        rule.onNodeWithTag("switch_notion_sync").assertIsNotEnabled()
    }

    private fun syncedItem(c: AppContainer, fake: FakeNotion): String = runBlocking {
        c.secrets.put(NOTION_TOKEN, "t")
        c.categories.ensureStarterTaxonomy()
        val id = (c.capture(CaptureRequest(CaptureMethod.SHARE, text = "Python SQL tutorial https://example.com/sql")) as CaptureResult.Saved).itemId
        c.processing.process(id, null)
        assertTrue(c.notionSync.sync(id) is NotionSyncResult.Synced)
        id
    }

    @Test fun syncedItemShowsVerifiedStatusAndNotionLink() {
        val fake = FakeNotion()
        val c = testContainer(notion = fake)
        syncedItem(c, fake)
        rule.setContent { TestApp(c) }
        waitFor(hasTestTag("home_search"))
        rule.onNodeWithTag("tab_inbox").performClick(); waitFor(hasText("Python SQL", substring = true))
        rule.onAllNodes(hasText("Python SQL", substring = true), useUnmergedTree = true)[0].performClick()
        waitFor(hasTestTag("item_detail"))
        rule.onNodeWithTag("item_detail").performScrollToNode(hasTestTag("notion_open"))
        waitFor(hasText(ctx.getString(R.string.notion_item_synced, "").trim(), substring = true))
    }

    @Test fun conflictIsShownAndKeepNotionIsQueuedNotWritten() {
        val fake = FakeNotion()
        val c = testContainer(notion = fake)
        val id = syncedItem(c, fake)
        val pageId = c.database.let { runBlocking { it.notionDao().mapping(id)!!.notionPageId } }
        fake.userEdit(pageId, "Status", buildJsonObject { put("select", buildJsonObject { put("name", "Completed") }) })
        runBlocking {
            c.knowledge.setPinned(id, true)
            assertEquals(NotionSyncResult.Conflict, c.notionSync.sync(id))
            assertEquals(SyncState.CONFLICT, c.database.knowledgeDao().get(id)!!.syncState)
        }
        val writesBefore = fake.calls.count { it.method == "PATCH" }
        rule.setContent { TestApp(c) }
        waitFor(hasTestTag("home_search"))
        rule.onNodeWithTag("tab_inbox").performClick(); waitFor(hasText("Python SQL", substring = true))
        rule.onAllNodes(hasText("Python SQL", substring = true), useUnmergedTree = true)[0].performClick()
        waitFor(hasTestTag("notion_conflict"))
        waitFor(hasText(ctx.getString(R.string.notion_conflict_in_notion, "Completed")))
        rule.onNodeWithTag("notion_keep").performScrollTo().performClick()
        val work = WorkManager.getInstance(ctx).getWorkInfosForUniqueWork("notion-$id").get()
        assertTrue(work.isNotEmpty())
        assertEquals(writesBefore, fake.calls.count { it.method == "PATCH" }) // nothing written by the UI itself
    }

    @Test fun largeFilesViaDriveIsOffByDefaultAndNeedsDrive() {
        val c = testContainer()
        openNotionSettings(c)
        assertFalse(runBlocking { c.settings.settings.first().largeFilesViaDrive })
        rule.onNodeWithTag("switch_large_files_drive").performScrollTo().assertIsNotEnabled()
        runBlocking { c.settings.updateBackup { it.copy(driveConnected = true) } }
        rule.onNodeWithTag("switch_large_files_drive").performScrollTo().assertIsEnabled().performClick()
        rule.waitUntil(5_000) { runBlocking { c.settings.settings.first().largeFilesViaDrive } }
    }
}
