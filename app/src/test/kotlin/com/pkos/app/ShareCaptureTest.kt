package com.pkos.app

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pkos.app.capture.ShareActivity
import com.pkos.app.capture.ShareIntentParser
import com.pkos.core.data.model.CaptureMethod
import com.pkos.core.data.model.SourcePlatform
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowStatFs
import java.io.File

/** Share target (R-01): parsing every supported intent shape, and saving straight from the share sheet. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ShareCaptureTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val resolver get() = ctx.contentResolver

    @Before fun storage() {
        TestShareProvider.install()
        ShadowStatFs.registerStats(ctx.filesDir, 1_000_000, 1_000_000, 1_000_000)
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(
            ctx, androidx.work.Configuration.Builder().setExecutor(androidx.work.testing.SynchronousExecutor()).build(),
        )
    }

    private fun tempFile(name: String, bytes: ByteArray) = File(ctx.cacheDir, name).apply { writeBytes(bytes) }

    @Test fun parsesSharedText() {
        val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "https://fb.com/post/1").putExtra(Intent.EXTRA_SUBJECT, "পোস্ট")
        val r = ShareIntentParser.parse(i, resolver, "com.facebook.katana")!!
        assertEquals(CaptureMethod.SHARE, r.method)
        assertEquals("পোস্ট", r.subject)
        assertEquals("com.facebook.katana", r.sourceApp)
        assertTrue(r.files.isEmpty())
    }

    @Test fun parsesMultipleImagesWithoutDuplicatesFromClipData() {
        val a = TestShareProvider.uri(tempFile("a.png", byteArrayOf(1, 2)))
        val b = TestShareProvider.uri(tempFile("b.png", byteArrayOf(3, 4)))
        val i = Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/png")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(a, b))
        i.clipData = ClipData.newRawUri("", a).apply { addItem(ClipData.Item(b)) }
        val r = ShareIntentParser.parse(i, resolver, null)!!
        assertEquals(CaptureMethod.SHARE_MULTIPLE, r.method)
        assertEquals(2, r.files.size)
        assertEquals(listOf("a.png", "b.png"), r.files.map { it.displayName })
    }

    /** M7 security: an exported share target must not read PKOS's own private files through file:// URIs. */
    @Test fun refusesFileUrisPointingAtPrivateAppFiles() {
        val c = (ctx as PkosApplication).container
        val db = ctx.getDatabasePath("pkos.db").apply { parentFile?.mkdirs(); if (!exists()) writeBytes(ByteArray(64)) }
        val secrets = File(ctx.dataDir, "shared_prefs/pkos_secrets.xml").apply { parentFile?.mkdirs(); writeText("<map/>") }
        listOf(Uri.fromFile(db), Uri.fromFile(secrets), Uri.parse("file:///data/data/com.pkos.app/files/attachments/x")).forEach { u ->
            val only = Intent(Intent.ACTION_SEND).setType("application/octet-stream").putExtra(Intent.EXTRA_STREAM, u)
            assertNull(ShareIntentParser.parse(only, resolver, null)) // nothing left to capture
            val withText = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "note").putExtra(Intent.EXTRA_STREAM, u)
            assertTrue(ShareIntentParser.parse(withText, resolver, null)!!.files.isEmpty()) // text kept, file refused
        }
        val i = Intent(ctx, ShareActivity::class.java).setAction(Intent.ACTION_SEND).setType("application/octet-stream").putExtra(Intent.EXTRA_STREAM, Uri.fromFile(db))
        ActivityScenario.launch<ShareActivity>(i).use { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(200); shadowOf(Looper.getMainLooper()).idle() }
        assertEquals(0, runBlocking { c.database.attachmentDao().all().size })
    }

    @Test fun ignoresUnsupportedOrEmptyIntents() {
        assertNull(ShareIntentParser.parse(Intent(Intent.ACTION_VIEW), resolver, null))
        assertNull(ShareIntentParser.parse(Intent(Intent.ACTION_SEND).setType("text/plain"), resolver, null))
    }

    private fun waitForItems(expected: Int): Int {
        val db = (ctx as PkosApplication).container.database
        repeat(100) {
            shadowOf(Looper.getMainLooper()).idle()
            val n = runBlocking { db.knowledgeDao().countAll() }
            if (n >= expected) return n
            Thread.sleep(50)
        }
        return runBlocking { db.knowledgeDao().countAll() }
    }

    @Test fun shareActivitySavesTextImmediatelyWithoutPressingSave() {
        val i = Intent(ctx, ShareActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "দারুণ ভিডিও https://youtu.be/xyz")
        ActivityScenario.launch<ShareActivity>(i).use {
            assertEquals(1, waitForItems(1))
            val item = runBlocking { (ctx as PkosApplication).container.database.knowledgeDao().countAll() }
            assertEquals(1, item)
        }
    }

    @Test fun shareActivitySavesSharedImageFile() {
        val png = tempFile("screen.png", ByteArray(2048) { 9 })
        val i = Intent(ctx, ShareActivity::class.java).setAction(Intent.ACTION_SEND).setType("image/png")
            .putExtra(Intent.EXTRA_STREAM, TestShareProvider.uri(png))
        ActivityScenario.launch<ShareActivity>(i).use {
            assertEquals(1, waitForItems(1))
            val c = (ctx as PkosApplication).container
            val att = runBlocking { c.database.attachmentDao().forItem(c.database.knowledgeDao().observeRecent(1).first().first().id) }
            assertEquals(2048L, att.single().sizeBytes)
            assertTrue(c.attachments.resolve(att.single().localPath).exists())
        }
    }

    @Test fun platformIsDetectedFromSharingApp() {
        val c = (ctx as PkosApplication).container
        val r = runBlocking { c.capture(ShareIntentParser.parse(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "hello"), resolver, "com.facebook.katana")!!) }
        val id = (r as com.pkos.core.data.capture.CaptureResult.Saved).itemId
        assertEquals(SourcePlatform.FACEBOOK, runBlocking { c.database.knowledgeDao().get(id)!!.sourcePlatform })
    }
}
