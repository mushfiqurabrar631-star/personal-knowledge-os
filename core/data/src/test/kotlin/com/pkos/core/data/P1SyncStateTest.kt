package com.pkos.core.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pkos.core.data.db.PkosDatabase
import com.pkos.core.data.db.entity.BackupSnapshotEntity
import com.pkos.core.data.db.entity.KnowledgeItemEntity
import com.pkos.core.data.features.SearchFilters
import com.pkos.core.data.features.SearchService
import com.pkos.core.data.model.BackupStatus
import com.pkos.core.data.model.SyncState
import com.pkos.core.data.repository.CategoryRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Spec 8.5 per-item states: BACKED_UP / SYNCED after a verified backup, back to LOCAL_ONLY / NOTION_SYNCED after an edit. */
@RunWith(AndroidJUnit4::class)
class P1SyncStateTest {
    private lateinit var db: PkosDatabase
    @Before fun setUp() = runBlocking { db = PkosDatabase.inMemory(ApplicationProvider.getApplicationContext()); CategoryRepository(db).ensureStarterTaxonomy() }
    @After fun tearDown() = db.close()

    private suspend fun item(id: String, at: Long, state: SyncState) = db.knowledgeDao().insert(KnowledgeItemEntity(id = id, title = id, createdAt = at, updatedAt = at, syncState = state))
    private suspend fun state(id: String) = db.knowledgeDao().get(id)!!.syncState

    @Test fun verifiedBackupMarksItemsAndEditsUndoIt() = runBlocking {
        item("a", 100, SyncState.LOCAL_ONLY); item("n", 100, SyncState.NOTION_SYNCED)
        item("q", 100, SyncState.NOTION_QUEUED); item("late", 500, SyncState.LOCAL_ONLY)
        db.backupDao().upsert(BackupSnapshotEntity("b1", createdAt = 200, schemaVersion = 6, status = BackupStatus.VERIFIED))
        assertEquals(2, db.backupDao().markBackedUp(200))
        assertEquals(SyncState.BACKED_UP, state("a")); assertEquals(SyncState.SYNCED, state("n"))
        assertEquals(SyncState.NOTION_QUEUED, state("q")); assertEquals(SyncState.LOCAL_ONLY, state("late"))
        assertEquals(200L, db.backupDao().latestVerifiedAt())

        val s = SearchService(db); s.refreshIndex()
        assertEquals(setOf("a", "n"), s.search("", SearchFilters(backedUp = true)).map { it.item.id }.toSet())

        // edits after the backup: the trigger takes the "backed up" part away
        db.knowledgeDao().update(db.knowledgeDao().get("a")!!.copy(title = "a2", updatedAt = 300))
        db.knowledgeDao().update(db.knowledgeDao().get("n")!!.copy(title = "n2", updatedAt = 300))
        assertEquals(SyncState.LOCAL_ONLY, state("a")); assertEquals(SyncState.NOTION_SYNCED, state("n"))
        // a non-edit update (same updated_at) keeps the state
        db.backupDao().markBackedUp(400)
        db.knowledgeDao().update(db.knowledgeDao().get("a")!!.copy(pinned = true))
        assertEquals(SyncState.BACKED_UP, state("a"))
    }
}
