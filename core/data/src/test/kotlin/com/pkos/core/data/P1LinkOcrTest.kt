package com.pkos.core.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pkos.core.data.capture.AttachmentStore
import com.pkos.core.data.capture.CaptureFile
import com.pkos.core.data.capture.CaptureRepository
import com.pkos.core.data.capture.CaptureRequest
import com.pkos.core.data.capture.CaptureResult
import com.pkos.core.data.db.PkosDatabase
import com.pkos.core.data.features.LinkMetadataService
import com.pkos.core.data.features.SearchService
import com.pkos.core.data.model.CaptureMethod
import com.pkos.core.data.model.ExtractionStatus
import com.pkos.core.data.model.PrivacyScope
import com.pkos.core.data.processing.DefaultTextExtractor
import com.pkos.core.data.processing.HttpGet
import com.pkos.core.data.processing.HttpReply
import com.pkos.core.data.processing.OcrEngine
import com.pkos.core.data.processing.ProcessingEngine
import com.pkos.core.data.repository.CategoryRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException

/** Spec 3: opt-in link metadata (YouTube oEmbed / page tags) and OCR of scanned PDFs. */
@RunWith(AndroidJUnit4::class)
class P1LinkOcrTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var db: PkosDatabase
    private lateinit var store: AttachmentStore
    private lateinit var capture: CaptureRepository
    private var now = 1_800_000_000_000L
    private val requested = mutableListOf<String>()

    @Before fun setUp() = runBlocking {
        db = PkosDatabase.inMemory(ApplicationProvider.getApplicationContext()); CategoryRepository(db).ensureStarterTaxonomy()
        store = AttachmentStore(tmp.newFolder()); capture = CaptureRepository(db, store, { Long.MAX_VALUE }, clock = { now++ })
    }
    @After fun tearDown() = db.close()
    private fun save(t: String, p: PrivacyScope = PrivacyScope.NORMAL) = runBlocking { (capture.capture(CaptureRequest(CaptureMethod.SHARE, text = t), p) as CaptureResult.Saved).itemId }

    private val web = HttpGet { url, _, max ->
        requested += url
        assertTrue(max <= LinkMetadataService.MAX_BYTES)
        when {
            url.startsWith("https://www.youtube.com/oembed") -> HttpReply(200, """{"title":"Kotlin Flow in 10 minutes","author_name":"Android Devs"}""")
            url.startsWith("https://blog.example.com") -> HttpReply(200, """<html><head><title>Ignored</title><meta property="og:title" content="Budget planning &amp; saving"><meta name="description" content="Five ways to save money"><meta property="og:site_name" content="Example Blog"></head></html>""")
            url.startsWith("https://offline.example.com") -> throw IOException("no network")
            else -> HttpReply(404, "")
        }
    }

    @Test fun youtubeAndPageMetadataAreFetchedOnceAndSearchable() = runBlocking {
        val yt = save("https://youtu.be/abc123")
        val blog = save("https://blog.example.com/post")
        val svc = LinkMetadataService(db, web, { now++ })
        assertTrue(svc.fetchFor(yt)); assertTrue(svc.fetchFor(blog))
        val y = db.knowledgeDao().get(yt)!!
        assertEquals("Kotlin Flow in 10 minutes", y.linkTitle); assertEquals("Kotlin Flow in 10 minutes", y.title) // system title improved
        assertEquals("YouTube · Android Devs", y.linkDescription)
        val b = db.knowledgeDao().get(blog)!!
        assertEquals("Budget planning & saving", b.linkTitle); assertEquals("Five ways to save money", b.linkDescription); assertEquals("Example Blog", b.linkSite)
        assertFalse(svc.fetchFor(blog)) // once per item
        assertEquals(2, requested.size)
        val s = SearchService(db, { now }); s.refreshIndex()
        assertEquals(listOf(blog), s.search("ways save", semantic = false).map { it.item.id })
    }

    @Test fun localOnlyHttpAndOfflineAreHandledSafely() = runBlocking {
        val svc = LinkMetadataService(db, web, { now++ })
        assertFalse(svc.fetchFor(save("https://blog.example.com/secret", PrivacyScope.LOCAL_ONLY)))
        assertFalse(svc.fetchFor(save("http://blog.example.com/plain")))
        assertTrue(requested.isEmpty()) // nothing requested for local-only or non-HTTPS
        val off = save("https://offline.example.com/a")
        assertFalse(svc.fetchFor(off)); assertFalse(svc.fetchFor(off))
        assertEquals(2, requested.size) // offline is retried later, not marked done
        assertNull(db.knowledgeDao().get(off)!!.linkTitle)
    }

    @Test fun scannedPdfIsReadByOcrOnThePhone() = runBlocking {
        val pdf = File(tmp.root, "scan.pdf").apply { writeBytes("%PDF-1.4\n%%EOF".toByteArray()) }
        val id = (capture.capture(CaptureRequest(CaptureMethod.SHARE, files = listOf(CaptureFile("scan.pdf", "application/pdf", "x") { pdf.inputStream() }))) as CaptureResult.Saved).itemId
        val ocr = object : OcrEngine {
            override suspend fun recognize(file: File) = ""
            override suspend fun recognizePdf(file: File) = "স্ক্যান করা চুক্তিপত্র"
        }
        ProcessingEngine(db, store::resolve, { _, _ -> "" }, ocr, clock = { now++ }).process(id, null)
        val a = db.attachmentDao().forItem(id).single()
        assertEquals(ExtractionStatus.DONE, a.extractionStatus); assertEquals("স্ক্যান করা চুক্তিপত্র", a.extractedText)
    }
}
