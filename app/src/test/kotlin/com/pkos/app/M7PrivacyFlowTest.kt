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
import androidx.compose.ui.test.performTextClearance
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.pkos.app.di.AppContainer
import com.pkos.app.di.BACKUP_KEK
import com.pkos.app.notion.NOTION_TOKEN
import com.pkos.app.processing.GEMINI_KEY
import com.pkos.core.data.capture.CaptureRequest
import com.pkos.core.data.capture.CaptureResult
import com.pkos.core.data.features.CloudAudit
import com.pkos.core.data.model.CaptureMethod
import com.pkos.core.data.settings.AppSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Spec 10.1 privacy controls in the UI: activity list, clear AI history, cache vs delete-all, disconnect/revoke, protected export. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class M7PrivacyFlowTest {
    @get:Rule val rule = createComposeRule()
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private fun s(id: Int) = ctx.getString(id)
    private fun waitFor(m: SemanticsMatcher) = rule.waitUntil(15_000) { rule.onAllNodes(m, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    private fun pump(until: () -> Boolean) { repeat(150) { if (until()) return; rule.waitForIdle(); org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); Thread.sleep(50) } }

    @Before fun wm() = WorkManagerTestInitHelper.initializeTestWorkManager(ctx, Configuration.Builder().setExecutor(SynchronousExecutor()).build())

    private fun openSettingsRow(c: AppContainer, label: Int, tag: String) {
        rule.setContent { TestApp(c) }
        waitFor(hasTestTag("home_search"))
        rule.onNodeWithTag("tab_settings").performClick(); waitFor(hasTestTag("settings_list"))
        rule.onNodeWithTag("settings_list").performScrollToNode(hasText(s(label)))
        rule.onAllNodes(hasText(s(label)), useUnmergedTree = true)[0].performClick(); waitFor(hasTestTag(tag))
    }

    @Test fun activityIsListedAndAiHistoryCanBeCleared() {
        val c = testContainer()
        val id = runBlocking { (c.capture(CaptureRequest(CaptureMethod.SHARE, text = "Kotlin flows")) as CaptureResult.Saved).itemId }
        runBlocking { CloudAudit.record(c.database, id, CloudAudit.ASK, "gemini", "OK", 120, 1_800_000_000_000L) }
        openSettingsRow(c, R.string.privacy_title, "privacy_screen")
        waitFor(hasText("Kotlin flows"))
        rule.onNodeWithTag("privacy_clear_ai").performScrollTo().performClick()
        rule.onNodeWithTag("privacy_clear_ai_confirm").performClick()
        waitFor(hasTestTag("activity_empty"))
        assertEquals(1, runBlocking { c.database.knowledgeDao().countAll() }) // items kept
    }

    @Test fun clearCacheKeepsDataAndDeleteAllNeedsTypedConfirmation() {
        val c = testContainer()
        runBlocking { c.capture(CaptureRequest(CaptureMethod.SHARE, text = "keep me")) }
        c.secrets.put(NOTION_TOKEN, "t"); c.secrets.put(GEMINI_KEY, "k"); c.secrets.put(BACKUP_KEK, "a2V5")
        openSettingsRow(c, R.string.privacy_title, "privacy_screen")
        rule.onNodeWithTag("privacy_clear_cache").performScrollTo().performClick()
        waitFor(hasTestTag("privacy_msg"))
        assertEquals(1, runBlocking { c.database.knowledgeDao().countAll() })

        rule.onNodeWithTag("privacy_wipe").performScrollTo().performClick()
        rule.onNodeWithTag("privacy_wipe_confirm").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag("privacy_wipe_field").performTextInput("wrong")
        rule.onNodeWithTag("privacy_wipe_confirm").assertIsNotEnabled()
        assertEquals(1, runBlocking { c.database.knowledgeDao().countAll() })
        rule.onNodeWithTag("privacy_wipe_field").performTextClearance()
        rule.onNodeWithTag("privacy_wipe_field").performTextInput(s(R.string.privacy_wipe_word))
        rule.onNodeWithTag("privacy_wipe_confirm").assertIsEnabled().performClick()
        pump { runBlocking { c.database.knowledgeDao().countAll() == 0 && !c.settings.settings.first().onboardingCompleted } }
        assertEquals(0, runBlocking { c.database.knowledgeDao().countAll() })
        assertNull(c.secrets.get(NOTION_TOKEN)); assertNull(c.secrets.get(GEMINI_KEY)); assertNull(c.secrets.get(BACKUP_KEK))
        assertFalse(runBlocking { c.settings.settings.first().onboardingCompleted })
        waitFor(hasText(s(R.string.onboarding_1_title)))
    }

    @Test fun driveDisconnectRevokesTheGoogleGrant() {
        val settings = InMemorySettings(AppSettings(onboardingCompleted = true, driveConnected = true, backupAutoEnabled = true))
        val c = testContainer(settings)
        var revokes = 0
        c.driveRevoker = { revokes++; true }
        openSettingsRow(c, R.string.settings_backup_sync, "backup_key")
        rule.onNodeWithTag("drive_disconnect").performScrollTo().performClick()
        waitFor(hasTestTag("drive_revoked"))
        assertEquals(1, revokes)
        assertFalse(settings.state.value.driveConnected); assertFalse(settings.state.value.backupAutoEnabled)
    }

    @Test fun driveDisconnectSaysSoWhenRevokeCannotBeConfirmed() {
        val c = testContainer(InMemorySettings(AppSettings(onboardingCompleted = true, driveConnected = true)))
        c.driveRevoker = { false }
        openSettingsRow(c, R.string.settings_backup_sync, "backup_key")
        rule.onNodeWithTag("drive_disconnect").performScrollTo().performClick()
        waitFor(hasTestTag("drive_revoke_manual"))
    }

    @Test fun exportWithPrivateItemsRequiresMatchingPassword() {
        val c = testContainer()
        openSettingsRow(c, R.string.settings_export, "export_screen")
        rule.onNodeWithTag("export_run").assertIsEnabled()
        rule.onNodeWithTag("export_local").performClick()
        waitFor(hasTestTag("export_password"))
        rule.onNodeWithTag("export_run").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag("export_password").performTextInput("long password")
        rule.onNodeWithTag("export_password2").performTextInput("long passwor")
        rule.onNodeWithTag("export_run").assertIsNotEnabled()
        rule.onNodeWithTag("export_password2").performTextInput("d")
        rule.onNodeWithTag("export_run").assertIsEnabled()
    }
}
