package com.pkos.app

import android.content.Context
import kotlinx.coroutines.flow.first
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.SemanticsMatcher
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
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.pkos.app.processing.GEMINI_KEY
import com.pkos.core.data.capture.CaptureRequest
import com.pkos.core.data.capture.CaptureResult
import com.pkos.core.data.model.CaptureMethod
import com.pkos.core.data.model.ProcessingState
import com.pkos.core.data.settings.AppSettings
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowStatFs

/** M3 end to end: capture → WorkManager → engine → organized; plus review actions and AI settings UI. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ProcessingFlowTest {
    @get:Rule val rule = createComposeRule()
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private fun waitFor(m: SemanticsMatcher) = rule.waitUntil(10_000) { rule.onAllNodes(m, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    @Before fun init() {
        ShadowStatFs.registerStats(ctx.filesDir, 1_000_000, 1_000_000, 1_000_000)
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
    }

    @Test fun captureIsProcessedInTheBackgroundByWorkManager() = runBlocking {
        val c = (ctx as PkosApplication).container
        c.categories.ensureStarterTaxonomy()
        val id = (c.capture(CaptureRequest(CaptureMethod.SHARE, text = "Python SQL tutorial for data analysis with excel and github code")) as CaptureResult.Saved).itemId
        var infos = WorkManager.getInstance(ctx).getWorkInfosForUniqueWork("process-$id").get()
        repeat(200) {
            if (infos.single().state.isFinished) return@repeat
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); Thread.sleep(50)
            infos = WorkManager.getInstance(ctx).getWorkInfosForUniqueWork("process-$id").get()
        }
        assertEquals(WorkInfo.State.SUCCEEDED, infos.single().state)
        val item = c.database.knowledgeDao().get(id)!!
        assertEquals(ProcessingState.ORGANIZED, item.processingState)
        assertEquals("sys-programming", item.categoryId)
        assertTrue(item.confidence!! < 0.85) // local rules never claim high confidence
    }

    @Test fun reviewCardLetsOwnerPickCategoryAndItSticks() {
        val c = testContainer()
        val id = runBlocking {
            val id = (c.capture(CaptureRequest(CaptureMethod.QUICK_NOTE, text = "hello there friend")) as CaptureResult.Saved).itemId
            c.processing.process(id, null); id
        }
        assertEquals(ProcessingState.NEEDS_REVIEW, runBlocking { c.database.knowledgeDao().get(id)!!.processingState })
        rule.setContent { TestApp(c) }
        waitFor(hasTestTag("home_search"))
        rule.onNodeWithTag("tab_inbox").performClick(); waitFor(hasText("hello there friend"))
        rule.onAllNodes(hasText("hello there friend"), useUnmergedTree = true)[0].performClick()
        waitFor(hasTestTag("ai_review"))
        rule.onNodeWithTag("ai_change_category").performScrollTo().performClick()
        waitFor(hasTestTag("category_option_sys-ideas"))
        rule.onNodeWithTag("category_option_sys-ideas").performClick()
        // The ViewModel writes on its own coroutine; pump the main looper while Room finishes on its IO thread.
        repeat(100) {
            if (runBlocking { c.database.knowledgeDao().get(id)!!.categoryId } == "sys-ideas") return@repeat
            rule.waitForIdle(); org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); Thread.sleep(50)
        }
        assertEquals("sys-ideas", runBlocking { c.database.knowledgeDao().get(id)!!.categoryId })
        val item = runBlocking { c.database.knowledgeDao().get(id)!! }
        assertEquals(ProcessingState.ORGANIZED, item.processingState)
        assertEquals("USER", JSONObject(item.fieldOrigins).getString("category"))
        assertNull(item.reviewReason)
    }

    @Test fun aiSettingsStoreKeyOnlyInSecretStore() {
        val settings = InMemorySettings(AppSettings(onboardingCompleted = true))
        val c = testContainer(settings)
        rule.setContent { TestApp(c) }
        waitFor(hasTestTag("home_search"))
        rule.onNodeWithTag("tab_settings").performClick(); waitFor(hasTestTag("settings_list"))
        rule.onNodeWithTag("settings_list").performScrollToNode(hasText(ctx.getString(R.string.ai_settings_title)))
        rule.onAllNodes(hasText(ctx.getString(R.string.ai_settings_title)), useUnmergedTree = true)[0].performClick()
        waitFor(hasTestTag("ai_settings"))
        rule.onNodeWithTag("switch_ai_cloud").performClick()
        waitFor(hasTestTag("ai_no_key_warning"))
        assertNull(runBlocking { c.cloudProvider() }) // on, but no key → still local only
        rule.onNodeWithTag("ai_key_input").performTextInput("AIza-test-key")
        rule.onNodeWithTag("ai_key_save").performClick()
        waitFor(hasTestTag("ai_key_remove"))
        assertEquals("AIza-test-key", c.secrets.get(GEMINI_KEY))
        assertNotNull(runBlocking { c.cloudProvider() })
        rule.onNodeWithTag("ai_key_remove").performClick()
        waitFor(hasTestTag("ai_key_input"))
        assertNull(c.secrets.get(GEMINI_KEY))
    }

    @Test fun compatibleProviderNeedsHttpsAndModelAndStaysOffUntilCloudIsOn() {
        val settings = InMemorySettings(AppSettings(onboardingCompleted = true))
        val c = testContainer(settings)
        rule.setContent { TestApp(c) }
        waitFor(hasTestTag("home_search"))
        rule.onNodeWithTag("tab_settings").performClick(); waitFor(hasTestTag("settings_list"))
        rule.onNodeWithTag("settings_list").performScrollToNode(hasText(ctx.getString(R.string.ai_settings_title)))
        rule.onAllNodes(hasText(ctx.getString(R.string.ai_settings_title)), useUnmergedTree = true)[0].performClick()
        waitFor(hasTestTag("ai_settings"))
        rule.onNodeWithTag("ai_provider_compat").performClick()
        waitFor(hasTestTag("ai_compat_warning"))
        rule.onNodeWithTag("ai_compat_base").performScrollTo().performTextInput("http://plain.example.com/v1")
        rule.onNodeWithTag("ai_compat_model").performScrollTo().performTextInput("small-model")
        rule.waitUntil(5_000) { runBlocking { settings.settings.first().compatModel == "small-model" } }
        assertEquals(false, c.cloudConfigured(runBlocking { settings.settings.first() })) // http refused
        rule.onNodeWithTag("ai_compat_base").performTextClearance()
        rule.onNodeWithTag("ai_compat_base").performTextInput("https://llm.example.com/v1")
        rule.waitUntil(5_000) { runBlocking { settings.settings.first().compatBaseUrl == "https://llm.example.com/v1" } }
        assertNull(runBlocking { c.cloudProvider() }) // configured, but cloud AI is still off by default
        runBlocking { settings.setCloudAiEnabled(true) }
        assertEquals("openai_compat", runBlocking { c.cloudProvider() }!!.name)
        assertNull(runBlocking { c.cloudEmbedder() })
        rule.onNodeWithTag("ai_compat_key_input").performScrollTo().performTextInput("sk-own")
        rule.onNodeWithTag("ai_compat_key_save").performClick()
        waitFor(hasTestTag("ai_compat_key_remove"))
        assertEquals("sk-own", c.secrets.get(com.pkos.app.processing.COMPAT_KEY))
        assertNull(c.secrets.get(GEMINI_KEY))
        // Switching back to Gemini without its key means no cloud at all.
        rule.onNodeWithTag("ai_provider_gemini").performClick()
        rule.waitUntil(5_000) { runBlocking { settings.settings.first().aiProvider == "gemini" } }
        assertNull(runBlocking { c.cloudProvider() })
    }
}
