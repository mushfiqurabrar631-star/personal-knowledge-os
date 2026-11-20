package com.pkos.core.notion

import com.pkos.core.notion.model.KnowledgeBody
import com.pkos.core.notion.sync.ConflictKind
import com.pkos.core.notion.sync.ConflictResolution
import com.pkos.core.notion.sync.RetryReason
import com.pkos.core.notion.sync.SyncOutcome
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Requirements 2 + 3: lost local mapping, adoption review, and crash / lost-response recovery. */
class AdoptionAndRecoveryTest {

    private val completed = buildJsonObject { putJsonObject("select") { put("name", "Completed") } }

    // ---------- Requirement 2: adoption without local mapping ----------

    @Test
    fun adoptionOfUnchangedPageWritesNothing() = runBlocking {
        val env = TestEnv()
        val item = env.item()
        env.engine.sync(item, null) as SyncOutcome.Synced
        env.loseLocalJournal()
        val writes = env.fake.writes().size
        val out = env.engine.sync(item, null) as SyncOutcome.Synced
        assertTrue(out.adopted)
        assertFalse(out.propertiesWritten)
        assertEquals(writes, env.fake.writes().size)
    }

    @Test
    fun adoptionOfRemotelyEditedPageNeedsReviewAndWritesNothing() = runBlocking {
        val env = TestEnv()
        val item = env.item()
        val first = env.engine.sync(item, null) as SyncOutcome.Synced
        env.fake.userEdit(first.link.pageId, "Status", completed)
        env.loseLocalJournal()
        val writes = env.fake.writes().size

        val review = env.engine.sync(item, null) as SyncOutcome.Conflict
        assertEquals(ConflictKind.ADOPTION_REVIEW, review.kind)
        assertEquals(listOf("Status"), review.differingProperties)
        assertEquals("Completed", review.remoteValues["Status"])
        assertEquals("To Learn", review.localValues["Status"])
        assertEquals("no write without permission", writes, env.fake.writes().size)

        val keep = env.engine.sync(item, null, resolution = ConflictResolution.KEEP_REMOTE) as SyncOutcome.Synced
        assertFalse(keep.propertiesWritten)
        assertEquals(mapOf("Status" to "Completed"), keep.keptRemoteValues)
        assertEquals(writes, env.fake.writes().size)

        val overwrite = env.engine.sync(item, null, resolution = ConflictResolution.OVERWRITE_REMOTE) as SyncOutcome.Synced
        assertTrue(overwrite.propertiesWritten)
        assertTrue(env.fake.pages.getValue(first.link.pageId).toString().contains("To Learn"))
        env.assertLegacyUntouched()
    }

    @Test
    fun adoptionWithLocalChangesButNoJournalIsReviewedNotGuessed() = runBlocking {
        val env = TestEnv()
        val item = env.item()
        env.engine.sync(item, null) as SyncOutcome.Synced
        env.loseLocalJournal()
        val out = env.engine.sync(item.copy(title = "local edit after reinstall"), null)
        assertTrue(out is SyncOutcome.Conflict && out.kind == ConflictKind.ADOPTION_REVIEW)
        assertEquals(0, env.fake.propertyPatchCount())
    }

    @Test
    fun journalProvesOwnWriteSoLocalChangeIsNotAFalseConflict() = runBlocking {
        val env = TestEnv()
        val item = env.item()
        env.engine.sync(item, null) as SyncOutcome.Synced // mapping save "fails": link discarded
        val out = env.engine.sync(item.copy(title = "edited before re-sync"), null) as SyncOutcome.Synced
        assertTrue(out.adopted)
        assertTrue(out.propertiesWritten)
        assertEquals(1, env.fake.pagesWithCaptureId(item.captureId))
    }

    // ---------- Requirement 3: crash / failure after each step ----------

    @Test
    fun readBackFailureAfterCreateRecoversWithoutDuplicate() = runBlocking {
        val env = TestEnv()
        val item = env.item()
        env.fake.failNext("GET", "/pages/", 500)
        assertTrue(env.engine.sync(item, null) is SyncOutcome.Retry)
        val retry = env.engine.sync(item, null, attempt = 1) as SyncOutcome.Synced
        assertTrue(retry.adopted)
        assertFalse(retry.propertiesWritten)
        assertEquals(1, env.fake.createCount())
        assertEquals(1, env.fake.pagesWithCaptureId(item.captureId))
        assertEquals(1, env.fake.markerCount(retry.link.pageId, item.captureId))
    }

    @Test
    fun readBackFailureWhileQueryIndexLagsUsesKnownPageId() = runBlocking {
        val env = TestEnv()
        val item = env.item()
        env.fake.hideNewPagesFromQuery = true
        env.fake.failNext("GET", "/pages/", 500)
        assertTrue(env.engine.sync(item, null) is SyncOutcome.Retry)
        val retry = env.engine.sync(item, null, attempt = 1) as SyncOutcome.Synced
        assertTrue(retry.adopted)
        assertEquals(1, env.fake.createCount())
    }

    @Test
    fun lostCreateResponseWithIndexLagWaitsInsteadOfDuplicating() = runBlocking {
        val env = TestEnv()
        val item = env.item()
        env.fake.hideNewPagesFromQuery = true
        env.fake.loseResponseOfNext("POST", "/pages")
        assertTrue(env.engine.sync(item, null) is SyncOutcome.Retry)
        env.advance(10)
        val wait = env.engine.sync(item, null, attempt = 1)
        assertTrue(wait is SyncOutcome.Retry && wait.reason == RetryReason.AWAITING_REMOTE_INDEX)
        assertEquals(1, env.fake.createCount())
        env.fake.hiddenFromQuery.clear()
        env.advance(30)
        val done = env.engine.sync(item, null, attempt = 2) as SyncOutcome.Synced
        assertTrue(done.adopted)
        assertEquals(1, env.fake.pagesWithCaptureId(item.captureId))
    }

    @Test
    fun createThatNeverReachedNotionIsRetriedAfterGracePeriod() = runBlocking {
        val env = TestEnv()
        val item = env.item()
        env.fake.failNext("POST", "/pages", 503)
        assertTrue(env.engine.sync(item, null) is SyncOutcome.Retry)
        val wait = env.engine.sync(item, null, attempt = 1)
        assertTrue(wait is SyncOutcome.Retry && wait.reason == RetryReason.AWAITING_REMOTE_INDEX)
        env.advance(121)
        val created = env.engine.sync(item, null, attempt = 2) as SyncOutcome.Synced
        assertTrue(created.created)
        assertEquals(1, env.fake.pagesWithCaptureId(item.captureId))
    }

    @Test
    fun lostPatchResponseIsNotAFalseConflict() = runBlocking {
        val env = TestEnv()
        val item = env.item()
        val first = env.engine.sync(item, null) as SyncOutcome.Synced
        val changed = item.copy(title = "changed in app")
        env.fake.loseResponseOfNext("PATCH", "/pages/")
        assertTrue(env.engine.sync(changed, first.link) is SyncOutcome.Retry)
        // local link still has the OLD hash; the journal proves the remote change is ours
        val retry = env.engine.sync(changed, first.link, attempt = 1) as SyncOutcome.Synced
        assertFalse("already applied, nothing to resend", retry.propertiesWritten)
        assertEquals(1, env.fake.propertyPatchCount())
    }

    @Test
    fun journalFailureStopsBeforeAnyWrite() = runBlocking {
        val env = TestEnv()
        env.journal.failWrites = true
        val out = env.engine.sync(env.item(), null)
        assertTrue(out is SyncOutcome.Retry && out.reason == RetryReason.LOCAL_JOURNAL)
        assertTrue(env.fake.writes().isEmpty())
        env.journal.failWrites = false
        env.journal.failReads = true
        val read = env.engine.sync(env.item(), null)
        assertTrue(read is SyncOutcome.Retry && read.reason == RetryReason.LOCAL_JOURNAL)
        assertTrue(env.fake.writes().isEmpty())
    }

    @Test
    fun journalRecordsIntentBeforeEachWrite() = runBlocking {
        val env = TestEnv()
        val item = env.item(body = KnowledgeBody())
        val first = env.engine.sync(item, null) as SyncOutcome.Synced
        env.engine.sync(item.copy(title = "t2", body = KnowledgeBody(summary = "AI")), first.link) as SyncOutcome.Synced
        val kinds = env.journal.history.map { it.kind.name }
        assertEquals(listOf("CREATE", "CREATE", "UPDATE", "APPEND_BODY"), kinds)
        assertEquals(null, env.journal.history.first().pageId)
        assertEquals(first.link.pageId, env.journal.history[1].pageId)
    }
}
