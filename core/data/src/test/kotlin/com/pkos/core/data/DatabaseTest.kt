package com.pkos.core.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pkos.core.data.db.PkosDatabase
import com.pkos.core.data.db.entity.BackupSnapshotEntity
import com.pkos.core.data.db.entity.KnowledgeItemEntity
import com.pkos.core.data.model.BackupStatus
import com.pkos.core.data.model.InboxFilter
import com.pkos.core.data.model.ItemStatus
import com.pkos.core.data.model.ProcessingState
import com.pkos.core.data.model.SyncState
import com.pkos.core.data.repository.CategoryRepository
import com.pkos.core.data.repository.KnowledgeRepository
import com.pkos.core.data.repository.StarterTaxonomy
import com.pkos.core.data.repository.SyncOverviewRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DatabaseTest {
    private lateinit var db: PkosDatabase
    private lateinit var knowledge: KnowledgeRepository

    @Before fun setUp() {
        db = PkosDatabase.inMemory(ApplicationProvider.getApplicationContext())
        knowledge = KnowledgeRepository(db) { 5_000 }
    }

    @After fun tearDown() = db.close()

    private fun item(
        title: String,
        processing: ProcessingState = ProcessingState.ORGANIZED,
        sync: SyncState = SyncState.LOCAL_ONLY,
        status: ItemStatus = ItemStatus.TO_LEARN,
        rawText: String? = null,
        createdAt: Long = 1_000,
        deletedAt: Long? = null,
    ) = KnowledgeItemEntity(
        id = UUID.randomUUID().toString(), title = title, rawText = rawText, processingState = processing, syncState = sync,
        status = status, createdAt = createdAt, updatedAt = createdAt, deletedAt = deletedAt,
    )

    @Test fun emptyDatabaseReportsZeroEverywhere() = runBlocking {
        val c = knowledge.counts().first()
        assertEquals(listOf(0, 0, 0, 0, 0, 0), listOf(c.saved, c.processing, c.needsReview, c.actionRequired, c.pendingSync, c.inbox))
        assertTrue(knowledge.recent().first().isEmpty())
        assertNull(SyncOverviewRepository(db).lastVerifiedBackup().first())
    }

    @Test fun countsAndInboxFiltersFollowLifecycleStates() = runBlocking {
        val dao = db.knowledgeDao()
        dao.insert(item("organized, done", status = ItemStatus.DONE))
        dao.insert(item("new capture", processing = ProcessingState.LOCAL_SAVED, status = ItemStatus.INBOX))
        dao.insert(item("needs review", processing = ProcessingState.NEEDS_REVIEW))
        dao.insert(item("failed", processing = ProcessingState.FAILED))
        dao.insert(item("conflict", sync = SyncState.CONFLICT))
        dao.insert(item("backup queued", sync = SyncState.BACKUP_QUEUED))
        dao.insert(item("deleted", processing = ProcessingState.FAILED, deletedAt = 9))

        val c = knowledge.counts().first()
        assertEquals(6, c.saved)
        assertEquals(1, c.processing)
        assertEquals(1, c.needsReview)
        assertEquals(2, c.actionRequired)
        assertEquals(1, c.pendingSync)
        assertEquals("inbox = new + review + failed + conflict", 4, c.inbox)

        assertEquals(setOf("failed", "conflict"), knowledge.inbox(InboxFilter.FAILED).first().map { it.title }.toSet())
        assertEquals(listOf("backup queued"), knowledge.inbox(InboxFilter.SYNC_PENDING).first().map { it.title })
        assertEquals(listOf("new capture"), knowledge.inbox(InboxFilter.PROCESSING).first().map { it.title })
        assertTrue("soft-deleted items never show", knowledge.inbox(InboxFilter.ALL).first().none { it.title == "deleted" })
    }

    @Test fun searchIsLiteralAndCoversBengaliText() = runBlocking {
        val dao = db.knowledgeDao()
        dao.insert(item("ফ্রি বইয়ের ওয়েবসাইট বাংলায়"))
        dao.insert(item("100% discount", rawText = "a_b"))
        dao.insert(item("other", rawText = "AI দিয়ে marketing শেখা"))
        assertEquals(listOf("ফ্রি বইয়ের ওয়েবসাইট বাংলায়"), knowledge.search("বইয়ের").first().map { it.title })
        assertEquals(listOf("other"), knowledge.search("marketing").first().map { it.title })
        assertEquals("% is literal", listOf("100% discount"), knowledge.search("0%").first().map { it.title })
        assertEquals("_ is literal", listOf("100% discount"), knowledge.search("a_b").first().map { it.title })
        assertTrue(knowledge.search("   ").first().isEmpty())
    }

    @Test fun starterTaxonomyIsIdempotentAndNeverOverwritesEdits() = runBlocking {
        val repo = CategoryRepository(db) { 1 }
        repo.ensureStarterTaxonomy()
        db.openHelper.writableDatabase.execSQL("UPDATE categories SET name = 'আমার শিক্ষা' WHERE id = 'sys-education'")
        repo.ensureStarterTaxonomy()
        val cats = repo.categories().first()
        assertEquals(StarterTaxonomy.categories.size, cats.size)
        assertEquals("আমার শিক্ষা", cats.first { it.category.id == "sys-education" }.category.name)
        assertEquals(StarterTaxonomy.categories.map { it.id }, cats.map { it.category.id })
    }

    @Test fun onlyVerifiedBackupCountsAsLastBackup() = runBlocking {
        val sync = db.syncDao()
        sync.insertBackup(BackupSnapshotEntity("b1", createdAt = 10, schemaVersion = 1, status = BackupStatus.FAILED))
        sync.insertBackup(BackupSnapshotEntity("b2", createdAt = 20, schemaVersion = 1, status = BackupStatus.UPLOADING))
        assertNull(SyncOverviewRepository(db).lastVerifiedBackup().first())
        sync.insertBackup(BackupSnapshotEntity("b3", createdAt = 5, schemaVersion = 1, status = BackupStatus.VERIFIED))
        assertEquals("b3", SyncOverviewRepository(db).lastVerifiedBackup().first()?.id)
    }

    @Test fun recentOrdersByLastOpenedThenUpdated() = runBlocking {
        val dao = db.knowledgeDao()
        val a = item("a", createdAt = 1); val b = item("b", createdAt = 2); val c = item("c", createdAt = 3)
        listOf(a, b, c).forEach { dao.insert(it) }
        knowledge.markOpened(a.id) // clock = 5000
        assertEquals(listOf("a", "c", "b"), knowledge.recent().first().map { it.title })
    }
}
