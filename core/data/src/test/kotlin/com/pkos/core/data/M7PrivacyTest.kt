package com.pkos.core.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pkos.core.data.capture.AttachmentStore
import com.pkos.core.data.capture.CaptureFile
import com.pkos.core.data.capture.CaptureRepository
import com.pkos.core.data.capture.CaptureRequest
import com.pkos.core.data.capture.CaptureResult
import com.pkos.core.data.db.PkosDatabase
import com.pkos.core.data.features.CloudAudit
import com.pkos.core.data.features.EmbeddingProvider
import com.pkos.core.data.features.ExportOptions
import com.pkos.core.data.features.ExportService
import com.pkos.core.data.features.KnowledgeChat
import com.pkos.core.data.features.PrivacyService
import com.pkos.core.data.features.SearchService
import com.pkos.core.data.features.TextGenerator
import com.pkos.core.data.model.CaptureMethod
import com.pkos.core.data.model.PrivacyScope
import com.pkos.core.data.processing.AiInput
import com.pkos.core.data.processing.AiProvider
import com.pkos.core.data.processing.AiResponse
import com.pkos.core.data.processing.DefaultTextExtractor
import com.pkos.core.data.processing.ProcessingEngine
import com.pkos.core.data.repository.CategoryRepository
import kotlinx.coroutines.runBlocking
import net.lingala.zip4j.ZipFile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File

/** Spec 10.1 privacy controls: cloud activity log, clear AI history, clear cache vs delete all data, protected export. */
@RunWith(AndroidJUnit4::class)
class M7PrivacyTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var db: PkosDatabase
    private var now = 1_800_000_000_000L
    private lateinit var root: File
    private lateinit var capture: CaptureRepository

    private val cloud = object : AiProvider {
        override val name = "gemini"; override val model = "gemini-x"
        override suspend fun classify(input: AiInput, repairHint: String?) = AiResponse.Failed("offline")
    }
    private val embed = object : EmbeddingProvider { override val model = "emb"; override suspend fun embed(text: String) = floatArrayOf(1f, 0f) }

    @Before fun setUp() = runBlocking {
        db = PkosDatabase.inMemory(ApplicationProvider.getApplicationContext()); CategoryRepository(db).ensureStarterTaxonomy()
        root = tmp.newFolder(); capture = CaptureRepository(db, AttachmentStore(root), { Long.MAX_VALUE }, clock = { now++ })
    }
    @After fun tearDown() = db.close()

    private fun save(t: String, scope: PrivacyScope = PrivacyScope.NORMAL) = runBlocking { (capture.capture(CaptureRequest(CaptureMethod.SHARE, text = t), scope) as CaptureResult.Saved).itemId }

    @Test fun cloudActivityListsEverySendAndClearAiHistoryKeepsItems() = runBlocking {
        val a = save("kotlin flows article")
        save("kotlin private diary", PrivacyScope.LOCAL_ONLY)
        ProcessingEngine(db, AttachmentStore(root)::resolve, DefaultTextExtractor(), null, clock = { now++ }).process(a, cloud)
        val s = SearchService(db, { now }); s.refreshIndex(); s.indexEmbeddings(embed)
        KnowledgeChat(s).ask("kotlin", TextGenerator { "x [1]" })
        val p = PrivacyService(db, root)
        val act = p.recentCloudActivity()
        assertTrue(act.any { it.kind == CloudAudit.ASK && it.itemId == a })
        assertTrue(act.any { it.kind == CloudAudit.EMBED && it.itemId == a })
        assertTrue(act.any { it.provider == "gemini" && it.model == "gemini-x" && it.itemId == a })
        assertTrue(act.all { it.itemId == a }) // the local-only item never appears: it was never sent
        assertTrue(act.all { it.chars > 0 && it.title != null })

        assertTrue(p.clearAiHistory() > 0)
        assertTrue(p.recentCloudActivity().isEmpty())
        assertEquals(0, db.featureDao().embeddings("emb").size)
        assertEquals(2, db.knowledgeDao().countAll()) // items untouched
    }

    @Test fun clearCacheKeepsDataButDeleteAllDataRemovesEverythingOnThePhone() = runBlocking {
        val png = File(tmp.root, "a.png").apply { writeBytes(ByteArray(300) { it.toByte() }) }
        val id = (capture.capture(CaptureRequest(CaptureMethod.SHARE, text = "with file", files = listOf(CaptureFile("a.png", "image/png", "x") { png.inputStream() }))) as CaptureResult.Saved).itemId
        val cache = tmp.newFolder("cache").also { File(it, "tmp.bin").writeBytes(ByteArray(1000)) }
        val s = SearchService(db, { now }); s.refreshIndex()
        val p = PrivacyService(db, root)
        assertEquals(1000L, p.clearCache(listOf(cache)))
        assertTrue(cache.listFiles()!!.isEmpty())
        assertEquals(1, db.knowledgeDao().countAll()); assertEquals(1, db.attachmentDao().forItem(id).size)
        assertEquals(listOf(id), s.search("with", semantic = false).map { it.item.id }) // index rebuilt on demand

        val r = p.deleteAllData()
        assertEquals(1, r.items); assertEquals(1, r.files)
        assertEquals(0, db.knowledgeDao().countAll())
        assertTrue(root.walkBottomUp().none { it.isFile })
        assertTrue(db.categoryDao().all().isNotEmpty()) // fresh starter categories, app still usable
    }

    @Test fun exportWithPrivateItemsNeedsAPasswordAndIsAesEncrypted() = runBlocking {
        save("public note"); save("secret local note", PrivacyScope.LOCAL_ONLY)
        try { ExportOptions(includeLocalOnly = true); fail() } catch (_: IllegalArgumentException) {}
        try { ExportOptions(includeLocalOnly = true, password = "short".toCharArray()); fail() } catch (_: IllegalArgumentException) {}
        val f = File(tmp.root, "e.zip")
        f.outputStream().use { ExportService(db, root, "t").export(it, ExportOptions(includeLocalOnly = true, password = "correct horse".toCharArray())) }
        assertFalse(String(f.readBytes(), Charsets.ISO_8859_1).contains("secret local note")) // not readable without the password
        val z = ZipFile(f)
        assertTrue(z.isEncrypted)
        assertTrue(z.fileHeaders.all { it.isEncrypted && it.encryptionMethod == net.lingala.zip4j.model.enums.EncryptionMethod.AES })
        try { ZipFile(f, "wrong password".toCharArray()).extractAll(tmp.newFolder().path); fail() } catch (_: Exception) {}
        val out = tmp.newFolder()
        ZipFile(f, "correct horse".toCharArray()).extractAll(out.path)
        assertTrue(File(out, "pkos-export.json").readText().contains("secret local note"))
    }
}
