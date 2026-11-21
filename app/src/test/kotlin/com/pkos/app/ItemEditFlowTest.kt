package com.pkos.app

import android.content.Context
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pkos.app.di.AppContainer
import com.pkos.core.data.capture.CaptureRequest
import com.pkos.core.data.capture.CaptureResult
import com.pkos.core.data.model.CaptureMethod
import com.pkos.core.data.model.ItemStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ItemEditFlowTest {
    @get:Rule val rule = createComposeRule()
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private fun waitFor(m: SemanticsMatcher) = rule.waitUntil(10_000) { rule.onAllNodes(m, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    private fun pump(until: () -> Boolean) {
        repeat(100) { if (until()) return; rule.waitForIdle(); org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); Thread.sleep(50) }
    }

    private fun openItem(c: AppContainer, text: String): String {
        val id = runBlocking { (c.capture(CaptureRequest(CaptureMethod.SHARE, text = text)) as CaptureResult.Saved).itemId }
        rule.setContent { TestApp(c) }
        waitFor(hasTestTag("home_search"))
        rule.onNodeWithTag("tab_inbox").performClick(); waitFor(hasText(text))
        rule.onAllNodes(hasText(text), useUnmergedTree = true)[0].performClick()
        waitFor(hasTestTag("item_detail"))
        return id
    }

    @Test fun editTitleSummaryTagsStatusOffline() {
        val c = testContainer()
        val id = openItem(c, "original shared text")
        rule.onNodeWithTag("item_edit").performClick(); waitFor(hasTestTag("edit_item"))
        rule.onNodeWithTag("edit_title").performTextClearance(); rule.onNodeWithTag("edit_title").performTextInput("নতুন শিরোনাম")
        rule.onNodeWithTag("edit_summary").performTextInput("আমার সারাংশ")
        rule.onNodeWithTag("edit_tags").performTextInput("ব্যবসা, idea")
        rule.onNodeWithTag("edit_status_TO_LEARN").performScrollTo().performClick()
        rule.onNodeWithTag("edit_save").performScrollTo().performClick()
        pump { runBlocking { c.database.knowledgeDao().get(id)!!.title == "নতুন শিরোনাম" } }
        val item = runBlocking { c.database.knowledgeDao().get(id)!! }
        assertEquals("আমার সারাংশ", item.summary); assertEquals(ItemStatus.TO_LEARN, item.status)
        assertEquals("USER", JSONObject(item.fieldOrigins).getString("title"))
        assertEquals(setOf("ব্যবসা", "idea"), runBlocking { c.database.tagDao().forItem(id).map { it.name }.toSet() })
        waitFor(hasText("নতুন শিরোনাম"))
        waitFor(hasText(ctx.getString(R.string.item_status_to_learn)))
    }

    @Test fun archiveThenDeleteWithConfirmation() {
        val c = testContainer()
        val id = openItem(c, "archive and delete me")
        rule.onNodeWithTag("item_menu").performClick(); rule.onNodeWithTag("item_archive").performClick()
        pump { runBlocking { c.database.knowledgeDao().get(id)!!.status == ItemStatus.ARCHIVED } }
        assertEquals(0, runBlocking { c.knowledge.counts().first().inbox })
        rule.onNodeWithTag("item_menu").performClick(); rule.onNodeWithTag("item_delete").performClick()
        waitFor(hasTestTag("item_delete_confirm"))
        rule.onNodeWithTag("item_delete_confirm").performClick()
        pump { runBlocking { c.database.knowledgeDao().get(id)!!.deletedAt != null } }
        assertNotNull(runBlocking { c.database.knowledgeDao().get(id)!!.deletedAt })
        assertEquals(0, runBlocking { c.knowledge.counts().first().saved })
    }
}
