package com.pkos.core.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pkos.core.data.capture.AttachmentStore
import com.pkos.core.data.capture.CaptureFile
import com.pkos.core.data.capture.CaptureRepository
import com.pkos.core.data.capture.CaptureRequest
import com.pkos.core.data.capture.CaptureResult
import com.pkos.core.data.db.PkosDatabase
import com.pkos.core.data.db.entity.KnowledgeItemEntity
import com.pkos.core.data.db.entity.TagEntity
import com.pkos.core.data.features.ExportService
import com.pkos.core.data.features.Granularity
import com.pkos.core.data.features.GraphService
import com.pkos.core.data.features.KnowledgeChat
import com.pkos.core.data.features.SearchService
import com.pkos.core.data.features.TimelineService
import com.pkos.core.data.model.CaptureMethod
import com.pkos.core.data.repository.CategoryRepository
import androidx.room.withTransaction
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import java.io.OutputStream
import java.time.ZoneOffset
import kotlin.random.Random
import kotlin.system.measureTimeMillis

/**
 * M7 performance smoke tests on the JVM (Robolectric + real SQLite). Absolute numbers are NOT phone numbers; they
 * catch regressions (accidental O(n²), whole-file reads) and are printed for docs/m7-report.md. Budgets are generous.
 */
@RunWith(AndroidJUnit4::class)
class M7PerformanceTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var db: PkosDatabase
    private val words = ("kotlin android compose notion backup money budget travel recipe health python sql design marketing " +
        "টাকা ব্যবসা রান্না ভ্রমণ পড়াশোনা প্রোগ্রামিং স্বাস্থ্য পরিকল্পনা ভিডিও শেখা").split(" ")

    @Before fun setUp() = runBlocking {
        db = PkosDatabase.inMemory(ApplicationProvider.getApplicationContext())
        CategoryRepository(db).ensureStarterTaxonomy()
    }
    @After fun tearDown() = db.close()

    private fun text(r: Random, n: Int) = (1..n).joinToString(" ") { words[r.nextInt(words.size)] + if (r.nextInt(7) == 0) r.nextInt(999).toString() else "" }

    private suspend fun seed(n: Int) {
        val r = Random(7); val now = 1_800_000_000_000L
        words.forEach { db.tagDao().insertIgnore(TagEntity("t-$it", it, it)) }
        db.withTransaction {
            for (i in 0 until n) {
                val id = "p$i"
                db.knowledgeDao().insert(KnowledgeItemEntity(id = id, title = text(r, 6), rawText = text(r, 300), summary = text(r, 20), categoryId = "sys-programming",
                    createdAt = now - i * 3_600_000L, updatedAt = now - i * 3_600_000L))
                repeat(2) { db.tagDao().linkByName(id, words[r.nextInt(words.size)]) }
            }
        }
    }


    @Test fun largeLibrarySearchChatGraphTimeline() = runBlocking {
        val n = 5_000
        val seedMs = measureTimeMillis { seed(n) }
        val s = SearchService(db)
        val indexMs = measureTimeMillis { assertEquals(n, s.refreshIndex()) }
        var hits = 0
        val keywordMs = measureTimeMillis { hits = s.search("kotlin budget", semantic = false).size }
        assertTrue(hits > 0)
        val warm = measureTimeMillis { s.search("notion", semantic = false) }
        val semanticMs = measureTimeMillis { s.search("টাকা kotlinn", semantic = true) }
        val chatMs = measureTimeMillis { KnowledgeChat(s).ask("money plan", null) }
        val graphMs = measureTimeMillis { GraphService(db).neighbors("p10") }
        val timelineMs = measureTimeMillis { TimelineService(db, ZoneOffset.UTC).build(Granularity.MONTH, now = 1_800_000_000_000L) }
        val exportMs = measureTimeMillis { ExportService(db, tmp.newFolder(), "t").export(OutputStream.nullOutputStream()) }
        println("PERF items=$n seed=${seedMs}ms index=${indexMs}ms keyword=${keywordMs}ms keywordWarm=${warm}ms semantic=${semanticMs}ms chat=${chatMs}ms graph=${graphMs}ms timeline=${timelineMs}ms export=${exportMs}ms")
        assertTrue("keyword search ${keywordMs}ms", keywordMs < 3_000)
        assertTrue("semantic search ${semanticMs}ms", semanticMs < 6_000)
        assertTrue("graph ${graphMs}ms", graphMs < 6_000)
    }

    @Test fun captureThroughputAndLargeAttachmentStreaming() = runBlocking {
        val root = tmp.newFolder()
        val store = AttachmentStore(root)
        val capture = CaptureRepository(db, store, { Long.MAX_VALUE })
        val r = Random(3)
        val captureMs = measureTimeMillis {
            repeat(300) { (capture.capture(CaptureRequest(CaptureMethod.SHARE, text = "note $it " + text(r, 40))) as CaptureResult.Saved) }
        }
        // 150 MB file: captured, hashed and exported by streaming, with the JVM heap kept far below the file size.
        val big = File(tmp.root, "big.bin")
        big.outputStream().buffered().use { o -> val b = ByteArray(1 shl 20) { it.toByte() }; repeat(150) { o.write(b) } }
        val rt = Runtime.getRuntime(); System.gc()
        val before = rt.totalMemory() - rt.freeMemory()
        var bigMs = 0L
        val saved = run {
            var res: CaptureResult? = null
            bigMs = measureTimeMillis { res = capture.capture(CaptureRequest(CaptureMethod.SHARE, files = listOf(CaptureFile("big.bin", "application/octet-stream", big.toURI().toString()) { big.inputStream() }))) }
            res as CaptureResult.Saved
        }
        val exportMs = measureTimeMillis { ExportService(db, root, "t").export(OutputStream.nullOutputStream()) }
        val grew = (rt.totalMemory() - rt.freeMemory() - before) / (1 shl 20)
        println("PERF capture300=${captureMs}ms (${captureMs / 300.0}ms/item) capture150MB=${bigMs}ms export150MB=${exportMs}ms heapGrowthMB=$grew")
        assertEquals(150L shl 20, db.attachmentDao().forItem(saved.itemId).single().sizeBytes)
        assertTrue("heap grew ${grew}MB while exporting a 150MB file", grew < 100)
    }

    /** Index rows written by the first v6 indexer (any rowid) are replaced; deleted items leave the index. */
    @Test fun indexFromEarlierBuildIsRepairedWithoutDuplicates() = runBlocking {
        seed(3)
        db.openHelper.writableDatabase.execSQL("INSERT INTO knowledge_fts(rowid, item_id, indexed_at, title, content, tags, topics, url) VALUES (9999, 'p1', 0, 'old', 'zebra', '', '', '')")
        db.openHelper.writableDatabase.execSQL("INSERT INTO knowledge_fts(rowid, item_id, indexed_at, title, content, tags, topics, url) VALUES (9998, 'gone', 0, 'old', 'zebra', '', '', '')")
        val s = SearchService(db)
        s.refreshIndex()
        fun ftsRows() = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM knowledge_fts").use { it.moveToFirst(); it.getInt(0) }
        assertEquals(3, ftsRows())
        assertTrue(s.search("zebra", semantic = false).isEmpty())
        db.knowledgeDao().softDelete("p2", 1_900_000_000_000L)
        s.refreshIndex()
        assertEquals(2, ftsRows())
        assertEquals(0, s.refreshIndex()) // nothing stale on the next keystroke
    }
}
