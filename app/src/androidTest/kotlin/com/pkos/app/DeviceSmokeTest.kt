package com.pkos.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pkos.app.processing.KeystoreSecretStore
import com.pkos.app.processing.TesseractBengaliOcr
import com.pkos.core.data.db.PkosDatabase
import com.pkos.core.data.db.entity.KnowledgeItemEntity
import com.pkos.core.data.features.SearchService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumentation checks that Robolectric cannot do (run on an emulator in CI, or a phone): the real Android Keystore,
 * real SQLite (FTS + triggers), the Tesseract native library with the Bengali model, and a real app launch.
 * These do not replace the owner's Final Testing on the phone.
 */
@RunWith(AndroidJUnit4::class)
class DeviceSmokeTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun appLaunchesToOnboardingOrHome() {
        val skip = ctx.getString(R.string.onboarding_skip)
        rule.waitUntil(20_000) { rule.onAllNodes(hasText(skip) or hasTestTag("home_search"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun keystoreSecretRoundTripsAndIsRemoved() {
        val store = KeystoreSecretStore(ctx)
        store.put("pkos_instrumentation_probe", "value-১২৩")
        assertEquals("value-১২৩", store.get("pkos_instrumentation_probe"))
        store.remove("pkos_instrumentation_probe")
        assertNull(store.get("pkos_instrumentation_probe"))
    }

    @Test fun realSqliteSearchFindsBengaliAndEnglish() = runBlocking {
        val db = PkosDatabase.inMemory(ctx)
        try {
            db.knowledgeDao().insert(KnowledgeItemEntity(id = "a", title = "মাসিক বাজেট পরিকল্পনা", rawText = "সঞ্চয় বাড়ানো", createdAt = 1, updatedAt = 1))
            db.knowledgeDao().insert(KnowledgeItemEntity(id = "b", title = "Kotlin coroutines guide", rawText = "structured concurrency", createdAt = 2, updatedAt = 2))
            val s = SearchService(db)
            assertTrue(s.search("বাজেট").any { it.item.id == "a" })
            assertTrue(s.search("coroutines").any { it.item.id == "b" })
        } finally { db.close() }
    }

    @Test fun tesseractBengaliModelLoadsAndReadsWithoutCrashing() = runBlocking {
        val bmp = Bitmap.createBitmap(1200, 300, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply {
            drawColor(Color.WHITE)
            drawText("আমার সোনার বাংলা আমি তোমায় ভালোবাসি", 20f, 180f, Paint().apply { color = Color.BLACK; textSize = 64f; isAntiAlias = true })
        }
        // The result may be null when confidence is low; the check is that the native library and model work.
        val text = TesseractBengaliOcr(ctx).read(bmp)
        if (text != null) assertTrue(text.any(TesseractBengaliOcr::isBengali))
    }
}
