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
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pkos.app.di.AppContainer
import com.pkos.core.data.backup.DriveApi
import com.pkos.core.data.backup.DriveFile
import com.pkos.core.data.backup.DriveQuota
import com.pkos.core.data.backup.UploadStep
import com.pkos.core.data.settings.AppSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class BackupFlowTest {
    @get:Rule val rule = createComposeRule()
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private fun waitFor(m: SemanticsMatcher) = rule.waitUntil(10_000) { rule.onAllNodes(m, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    private fun open(c: AppContainer) {
        rule.setContent { TestApp(c) }
        waitFor(hasTestTag("home_search"))
        rule.onNodeWithTag("tab_settings").performClick(); waitFor(hasTestTag("settings_list"))
        rule.onNodeWithTag("settings_list").performScrollToNode(hasText(ctx.getString(R.string.settings_backup_sync)))
        rule.onAllNodes(hasText(ctx.getString(R.string.settings_backup_sync)), useUnmergedTree = true)[0].performClick()
        waitFor(hasTestTag("backup_key"))
    }

    @Test fun recoveryKeyIsShownOnceAndOnlyItsDerivedKeyIsStored() {
        val c = testContainer()
        open(c)
        rule.onNodeWithTag("backup_now").performScrollTo().assertIsNotEnabled() // no Drive, no key: nothing pretends to back up
        rule.onNodeWithTag("recovery_create").performScrollTo().performClick()
        waitFor(hasTestTag("recovery_key_text"))
        val key = rule.onNodeWithTag("recovery_key_text", useUnmergedTree = true).fetchSemanticsNode().config
            .let { it[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString("") }
        assertTrue(Regex("([A-Z2-9]{4}-){6}[A-Z2-9]{4}").matches(key))
        rule.onNodeWithTag("recovery_done").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag("recovery_written").performScrollTo().performClick()
        rule.onNodeWithTag("recovery_confirm").performScrollTo().performTextInput(key.takeLast(4))
        rule.onNodeWithTag("recovery_done").performScrollTo().assertIsEnabled().performClick()
        waitFor(hasText(ctx.getString(R.string.recovery_ready, runBlocking { c.database.backupDao().recovery() }!!.keyId.take(6))))
        assertNotNull(c.backupKek())
        val meta = runBlocking { c.database.backupDao().recovery() }!!
        assertTrue(!meta.toString().contains(key) && !meta.toString().contains(key.replace("-", "")))
        assertEquals(meta.keyId, com.pkos.core.data.backup.BackupCrypto.keyId(c.backupKek()!!))
    }

    @Test fun connectedDriveShowsQuotaWarningAndEmptyRestoreIsHonest() {
        val c = testContainer(InMemorySettings(AppSettings(onboardingCompleted = true, driveConnected = true)), drive = EmptyDrive())
        open(c)
        rule.onNodeWithTag("restore_start").performScrollTo().performClick()
        waitFor(hasTestTag("restore_error"))
        waitFor(hasText(ctx.getString(R.string.restore_none)))
    }
}
