package com.pkos.core.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pkos.core.data.capture.AttachmentStore
import com.pkos.core.data.capture.CaptureFailure
import com.pkos.core.data.capture.CaptureFile
import com.pkos.core.data.capture.CaptureRepository
import com.pkos.core.data.capture.CaptureRequest
import com.pkos.core.data.capture.CaptureResult
import com.pkos.core.data.db.PkosDatabase
import com.pkos.core.data.model.CaptureMethod
import com.pkos.core.data.model.ContentType
import com.pkos.core.data.model.ProcessingJobType
import com.pkos.core.data.model.ProcessingState
import com.pkos.core.data.model.PrivacyScope
import com.pkos.core.data.model.SourcePlatform
import com.pkos.core.data.model.SyncState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

@RunWith(AndroidJUnit4::class)
class CaptureRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var db: PkosDatabase
    private lateinit var root: File
    private var free = Long.MAX_VALUE
    private lateinit var repo: CaptureRepository

    @Before fun setUp() {
        db = PkosDatabase.inMemory(context)
        root = tmp.newFolder("attachments")
        repo = CaptureRepository(db, AttachmentStore(root), { free }) { 1_000 }
    }

    @After fun tearDown() = db.close()

    private fun file(name: String, mime: String, bytes: ByteArray) = CaptureFile(name, mime, "content://test/$name") { ByteArrayInputStream(bytes) }
    private fun stored(): List<File> = root.walkTopDown().filter { it.isFile }.toList()

    @Test fun sharedUrlIsSavedLocallyWithProvenanceAndQueuedJobs() = runBlocking {
        val r = repo.capture(CaptureRequest(CaptureMethod.SHARE, text = "দেখুন https://www.youtube.com/watch?v=abc&utm_source=x", sourceApp = "com.google.android.youtube")) as CaptureResult.Saved
        val item = db.knowledgeDao().get(r.itemId)!!
        assertEquals(ProcessingState.LOCAL_SAVED, item.processingState)
        assertEquals(SyncState.LOCAL_ONLY, item.syncState)
        assertEquals(SourcePlatform.YOUTUBE, item.sourcePlatform)
        assertEquals(ContentType.VIDEO, item.contentType)
        assertFalse(item.sourceUrl!!.contains("utm_source"))
        assertEquals(listOf(ProcessingJobType.AI_CLASSIFY), db.processingJobDao().forItem(r.itemId).map { it.jobType })
        assertEquals("com.google.android.youtube", db.captureSourceDao().observeForItem(r.itemId).first().single().sourceApp)
    }

    @Test fun imageIsCopiedHashedAndDedupedOnDisk() = runBlocking {
        val png = ByteArray(4096) { (it % 251).toByte() }
        val a = repo.capture(CaptureRequest(CaptureMethod.SHARE, files = listOf(file("shot.png", "image/png", png)))) as CaptureResult.Saved
        val b = repo.capture(CaptureRequest(CaptureMethod.SHARE, files = listOf(file("shot-copy.png", "image/png", png)))) as CaptureResult.Saved
        assertEquals(1, stored().size) // content-addressed: same bytes stored once
        val att = db.attachmentDao().forItem(a.itemId).single()
        assertEquals(4096L, att.sizeBytes)
        assertTrue(File(root, att.localPath).exists())
        assertEquals(a.itemId, b.duplicateOfId) // duplicate is kept and only flagged (spec 5.3)
        assertEquals(2, db.knowledgeDao().countAll())
        assertTrue(db.processingJobDao().forItem(a.itemId).any { it.jobType == ProcessingJobType.OCR })
    }

    @Test fun largeFileIsStreamedNotBuffered() = runBlocking {
        val size = 30L * 1024 * 1024
        val stream = object : InputStream() {
            var left = size
            override fun read(): Int = if (left-- > 0) 7 else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (left <= 0) return -1
                val n = minOf(len.toLong(), left).toInt(); java.util.Arrays.fill(b, off, off + n, 7); left -= n; return n
            }
        }
        val r = repo.capture(CaptureRequest(CaptureMethod.FILE_PICKER, files = listOf(CaptureFile("big.pdf", "application/pdf", null) { stream }))) as CaptureResult.Saved
        assertEquals(size, db.attachmentDao().forItem(r.itemId).single().sizeBytes)
        assertTrue(db.processingJobDao().forItem(r.itemId).any { it.jobType == ProcessingJobType.EXTRACT })
    }

    @Test fun unreadableFileLeavesNoRowsAndNoFiles() = runBlocking {
        val ok = file("a.png", "image/png", ByteArray(100) { 1 })
        val broken = CaptureFile("b.png", "image/png", null) { throw IOException("revoked") }
        val r = repo.capture(CaptureRequest(CaptureMethod.SHARE_MULTIPLE, files = listOf(ok, broken)))
        assertEquals(CaptureFailure.UNREADABLE, (r as CaptureResult.Failed).reason)
        assertEquals(0, db.knowledgeDao().countAll())
        assertTrue(stored().isEmpty())
    }

    @Test fun emptyAndLowStorageAreReportedHonestly() = runBlocking {
        assertEquals(CaptureFailure.EMPTY, (repo.capture(CaptureRequest(CaptureMethod.PASTE, text = "   ")) as CaptureResult.Failed).reason)
        free = 1024
        assertEquals(CaptureFailure.LOW_STORAGE, (repo.capture(CaptureRequest(CaptureMethod.QUICK_NOTE, text = "note")) as CaptureResult.Failed).reason)
        assertEquals(0, db.knowledgeDao().countAll())
    }

    @Test fun localOnlyPrivacyAndNoteArePersisted() = runBlocking {
        val r = repo.capture(CaptureRequest(CaptureMethod.QUICK_NOTE, text = "গোপন আইডিয়া", note = " পরে দেখব "), PrivacyScope.LOCAL_ONLY) as CaptureResult.Saved
        val item = db.knowledgeDao().get(r.itemId)!!
        assertEquals(PrivacyScope.LOCAL_ONLY, item.privacyScope)
        assertEquals("পরে দেখব", item.userNote)
        assertNull(item.duplicateOfId)
    }

    @Test fun captureSurvivesDatabaseReopen() = runBlocking {
        val name = "reopen-test.db"
        context.deleteDatabase(name)
        val fileDb = PkosDatabase.create(context, name)
        val r = CaptureRepository(fileDb, AttachmentStore(root), { free }).capture(CaptureRequest(CaptureMethod.SHARE, text = "https://example.com/a")) as CaptureResult.Saved
        fileDb.close()
        val reopened = PkosDatabase.create(context, name)
        assertNotNull(reopened.knowledgeDao().get(r.itemId))
        reopened.close()
        context.deleteDatabase(name)
        Unit
    }
}

@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), PkosDatabase::class.java)

    @Test fun v1ToV2KeepsExistingItems() {
        val name = "migration-test.db"
        helper.createDatabase(name, 1).apply {
            execSQL(
                "INSERT INTO knowledge_items (id, title, priority, status, processing_state, sync_state, privacy_scope, pinned, field_origins, created_at, updated_at, user_edited) " +
                    "VALUES ('old-1', 'পুরোনো আইটেম', 'NORMAL', 'INBOX', 'LOCAL_SAVED', 'LOCAL_ONLY', 'NORMAL', 0, '{}', 1, 1, 0)",
            )
            close()
        }
        val db = helper.runMigrationsAndValidate(name, 2, true)
        db.query("SELECT title, content_hash, duplicate_of_id FROM knowledge_items WHERE id = 'old-1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("পুরোনো আইটেম", c.getString(0))
            assertTrue(c.isNull(1)); assertTrue(c.isNull(2))
        }
        db.close()
        // Room itself opens the migrated file without a destructive fallback.
        val room = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), PkosDatabase::class.java, name).build()
        runBlocking { assertEquals("পুরোনো আইটেম", room.knowledgeDao().get("old-1")?.title) }
        room.close()
    }
}
