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
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.pkos.app.di.AppContainer
import com.pkos.core.data.backup.DriveApi
import com.pkos.core.data.backup.DriveFile
import com.pkos.core.data.backup.DriveQuota
import com.pkos.core.data.backup.UploadStep
import com.pkos.core.data.settings.AppSettings
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File



/** M5 evidence (docs/screenshots/m5). Drive here is a local stub; real Drive runs on the owner's phone. */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "bn-w400dp-h860dp-xxhdpi")
class ScreenshotM5Test {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val dir = (System.getProperty("pkos.screenshots.dir") ?: "build/screenshots/m1").replace("m1", "m5")
    private fun shot(name: String) { rule.waitForIdle(); captureScreenRoboImage("$dir/$name.png") }
    private fun waitFor(m: SemanticsMatcher) = rule.waitUntil(8_000) { rule.onAllNodes(m, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    private fun open(c: AppContainer) {
        rule.setContent { TestApp(c) }
        waitFor(hasTestTag("home_search"))
        rule.onNodeWithTag("tab_settings").performClick(); waitFor(hasTestTag("settings_list"))
        val label = rule.activity.getString(R.string.settings_backup_sync)
        rule.onNodeWithTag("settings_list").performScrollToNode(hasText(label))
        rule.onAllNodes(hasText(label), useUnmergedTree = true)[0].performClick()
        waitFor(hasTestTag("backup_key"))
    }

    @Test fun b01_notConnected() { open(testContainer()); shot("b01_backup_not_connected") }

    @Test fun b02_recoveryKey() {
        open(testContainer(InMemorySettings(AppSettings(onboardingCompleted = true, driveConnected = true)), drive = EmptyDrive()))
        rule.onNodeWithTag("recovery_create").performScrollTo().performClick(); waitFor(hasTestTag("recovery_key_text"))
        rule.onNodeWithTag("recovery_new").performScrollTo(); shot("b02_recovery_key_once")
    }

    @Test fun b03_readyWithQuotaWarning() {
        val c = testContainer(InMemorySettings(AppSettings(onboardingCompleted = true, driveConnected = true, backupAutoEnabled = true)), drive = EmptyDrive())
        kotlinx.coroutines.runBlocking { val k = c.backupService.createRecoveryKey(); c.storeBackupKek(k.kek) }
        open(c)
        rule.onNodeWithTag("backup_controls").performScrollTo(); shot("b03_backup_ready_controls")
        rule.onNodeWithTag("backup_retention").performScrollTo(); shot("b04_retention_restore")
    }
}
