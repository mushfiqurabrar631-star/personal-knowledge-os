package com.pkos.core.notion

import com.pkos.core.notion.model.ItemPriority
import com.pkos.core.notion.model.ItemStatus
import com.pkos.core.notion.model.KnowledgeBody
import com.pkos.core.notion.model.PrivacyScope
import com.pkos.core.notion.model.SourcePlatform
import com.pkos.core.notion.sync.ActionReason
import com.pkos.core.notion.sync.BodyState
import com.pkos.core.notion.sync.ConflictKind
import com.pkos.core.notion.sync.ConflictResolution
import com.pkos.core.notion.sync.RetryReason
import com.pkos.core.notion.sync.SyncOutcome
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/** Core behaviour of the sync engine (create, update, options, errors, guards). */
class NotionSyncEngineTest {
    private val env = TestEnv()
    private val fake get() = env.fake
    private val engine get() = env.engine

    @Test
    fun createsVerifiedPageWithBodyWhenNotLinked() = runBlocking {
        val it = env.item()
        val out = engine.sync(it, existing = null) as SyncOutcome.Synced
        assertTrue(out.created)
        assertEquals(BodyState.WRITTEN, out.bodyState)
        assertEquals(16, fake.pages.size)
        val page = fake.pages.getValue(out.link.pageId)["properties"]!!.jsonObject
        assertTrue(page["Capture ID"].toString().contains(it.captureId))
        assertTrue(page["Link/URL"].toString().contains("https://youtu.be/ynZp8-FjYUo\""))
        assertTrue(page["platform "].toString().contains("Youtube"))
        assertTrue(page["Date Added"].toString().contains("2026-11-20"))
        assertFalse(page["Notes"].toString().contains("name"))
        assertEquals(1, fake.markerCount(out.link.pageId, it.captureId))
        assertEquals("schema read before write", "GET", fake.calls.first().method)
        env.assertLegacyUntouched()
    }

    @Test
    fun secondSyncUpdatesSamePageAndNeverDuplicates() = runBlocking {
        val it = env.item()
        val first = engine.sync(it, null) as SyncOutcome.Synced
        val second = engine.sync(it.copy(title = "Updated title"), first.link) as SyncOutcome.Synced
        assertFalse(second.created)
        assertTrue(second.propertiesWritten)
        assertEquals(first.link.pageId, second.link.pageId)
        assertEquals(16, fake.pages.size)
        assertEquals(1, fake.createCount())
        assertEquals("body written at create, never appended again", 0, fake.appendCount())
        env.assertLegacyUntouched()
    }

    @Test
    fun identicalResyncWritesNothing() = runBlocking {
        val it = env.item()
        val first = engine.sync(it, null) as SyncOutcome.Synced
        val writesBefore = fake.writes().size
        val again = engine.sync(it, first.link) as SyncOutcome.Synced
        assertFalse(again.propertiesWritten)
        assertEquals(writesBefore, fake.writes().size)
    }

    @Test
    fun unknownOptionsAreSkippedNotCreated() = runBlocking {
        val it = env.item().copy(
            mainCategory = "Agriculture & Farming",
            tags = listOf("Business", "Freelancing"),
            platform = SourcePlatform.INSTAGRAM,
            priority = ItemPriority.CRITICAL,
            status = ItemStatus.ARCHIVED,
        )
        val out = engine.sync(it, null) as SyncOutcome.Synced
        val props = fake.pages.getValue(out.link.pageId)["properties"]!!.jsonObject
        assertTrue(props["Priority"].toString().contains("High"))
        assertTrue(props["Status"].toString().contains("null"))
        assertTrue(props["platform "].toString().contains("null"))
        val skippedValues = out.skipped.map { s -> s.value }
        assertTrue(skippedValues.containsAll(listOf("Agriculture & Farming", "Freelancing", "INSTAGRAM", "ARCHIVED")))
        env.assertLegacyUntouched()
    }

    @Test
    fun missingCaptureIdPropertyBlocksAllWrites() = runBlocking {
        fake.schema = MasterSchemaFixture.json(captureIdType = null)
        val out = engine.sync(env.item(), null)
        assertTrue(out is SyncOutcome.ActionRequired && out.reason == ActionReason.SCHEMA_INCOMPATIBLE)
        assertTrue(fake.writes().isEmpty())
    }

    @Test
    fun wrongCaptureIdTypeBlocksAllWrites() = runBlocking {
        fake.schema = MasterSchemaFixture.json(captureIdType = "number")
        val out = engine.sync(env.item(), null)
        assertTrue(out is SyncOutcome.ActionRequired && out.reason == ActionReason.SCHEMA_INCOMPATIBLE)
        assertTrue(fake.writes().isEmpty())
    }

    @Test
    fun remoteEditIsReportedAsConflictAndNotOverwritten() = runBlocking {
        val it = env.item()
        val first = engine.sync(it, null) as SyncOutcome.Synced
        fake.userEdit(first.link.pageId, "Status", buildJsonObject { putJsonObject("select") { put("name", "Completed") } })
        val out = engine.sync(it.copy(title = "App change"), first.link)
        out as SyncOutcome.Conflict
        assertEquals(ConflictKind.REMOTE_EDITED, out.kind)
        assertEquals(listOf("Status", "Topic name"), out.differingProperties)
        assertEquals("Completed", out.remoteValues["Status"])
        assertEquals(0, fake.propertyPatchCount(first.link.pageId))
        val forced = engine.sync(it.copy(title = "App change"), first.link, resolution = ConflictResolution.OVERWRITE_REMOTE)
        assertTrue(forced is SyncOutcome.Synced)
        assertTrue(fake.pages.getValue(first.link.pageId).toString().contains("To Learn"))
    }

    @Test
    fun errorsMapToSafeOutcomes() = runBlocking {
        val cases = listOf(
            401 to ActionReason.UNAUTHORIZED,
            403 to ActionReason.NO_ACCESS,
            404 to ActionReason.DATA_SOURCE_NOT_FOUND,
            400 to ActionReason.VALIDATION_REJECTED,
        )
        for ((status, reason) in cases) {
            fake.failNext("GET", "/data_sources/", status)
            val out = engine.sync(env.item(), null)
            assertTrue("$status → $out", out is SyncOutcome.ActionRequired && out.reason == reason)
        }
        assertTrue(fake.writes().isEmpty())
    }

    @Test
    fun retryableErrorsScheduleBackoff() = runBlocking {
        fake.failNext("POST", "/pages", 429, "rate_limited", retryAfter = "7")
        assertEquals(SyncOutcome.Retry(RetryReason.RATE_LIMITED, 7_000), engine.sync(env.item(), null))

        fake.failNext("POST", "/pages", 500)
        val server = engine.sync(env.item(), null)
        assertTrue(server is SyncOutcome.Retry && server.reason == RetryReason.SERVER && server.delayMs >= 1_000)

        fake.failNext("POST", "/pages", 409, "conflict_error")
        val conflict = engine.sync(env.item(), null)
        assertTrue(conflict is SyncOutcome.Retry && conflict.reason == RetryReason.CONFLICT)

        fake.throwNetworkOnNext = true
        val offline = engine.sync(env.item(), null)
        assertTrue(offline is SyncOutcome.Retry && offline.reason == RetryReason.NETWORK)

        fake.failNext("POST", "/pages", 503)
        val exhausted = engine.sync(env.item(), null, attempt = 7)
        assertTrue(exhausted is SyncOutcome.ActionRequired && exhausted.reason == ActionReason.RETRIES_EXHAUSTED)
        assertEquals("no page created by failed attempts", 15, fake.pages.size)
    }

    @Test
    fun missingTokenNeedsAction() = runBlocking {
        env.token = null
        val out = engine.sync(env.item(), null)
        assertTrue(out is SyncOutcome.ActionRequired && out.reason == ActionReason.NO_TOKEN)
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun localOnlyItemsAreNeverSent() = runBlocking {
        val out = engine.sync(env.item().copy(privacyScope = PrivacyScope.LOCAL_ONLY), null)
        assertTrue(out is SyncOutcome.NotEligible)
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun invalidCaptureIdIsRejected() = runBlocking {
        val out = engine.sync(env.item(id = "not-a-uuid"), null)
        assertTrue(out is SyncOutcome.ActionRequired && out.reason == ActionReason.INVALID_CAPTURE_ID)
        val upper = engine.sync(env.item(id = UUID.randomUUID().toString().uppercase()), null)
        assertTrue(upper is SyncOutcome.ActionRequired)
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun linkPointingAtLegacyPageIsRefused() = runBlocking {
        val it = env.item()
        val good = engine.sync(it, null) as SyncOutcome.Synced
        val out = engine.sync(it, good.link.copy(pageId = env.legacyIds.first()))
        assertTrue(out is SyncOutcome.ActionRequired && out.reason == ActionReason.PAGE_NOT_OWNED)
        env.assertLegacyUntouched()
    }

    @Test
    fun deletedRemotePageIsNotSilentlyRecreated() = runBlocking {
        val it = env.item()
        val first = engine.sync(it, null) as SyncOutcome.Synced
        fake.pages.remove(first.link.pageId)
        val out = engine.sync(it, first.link)
        assertTrue(out is SyncOutcome.ActionRequired && out.reason == ActionReason.PAGE_MISSING)
        assertEquals(15, fake.pages.size)
    }

    @Test
    fun duplicateRemotePagesNeedUserDecision() = runBlocking {
        val it = env.item()
        engine.sync(it, null) as SyncOutcome.Synced
        env.loseLocalJournal()
        fake.pages.keys.last().let { id -> fake.pages[id + "-copy"] = fake.pages.getValue(id) }
        val out = engine.sync(it, null)
        assertTrue(out is SyncOutcome.ActionRequired && out.reason == ActionReason.DUPLICATE_REMOTE_PAGES)
    }

    @Test
    fun itemWithoutAiYetGetsBodyLaterExactlyOnce() = runBlocking {
        val it = env.item(body = KnowledgeBody())
        val first = engine.sync(it, null) as SyncOutcome.Synced
        assertEquals(BodyState.ABSENT, first.bodyState)
        val enriched = it.copy(body = KnowledgeBody(summary = "পরে AI সারসংক্ষেপ"))
        val second = engine.sync(enriched, first.link) as SyncOutcome.Synced
        assertEquals(BodyState.WRITTEN, second.bodyState)
        engine.sync(enriched, second.link) as SyncOutcome.Synced
        assertEquals(1, fake.appendCount())
        assertEquals(1, fake.markerCount(first.link.pageId, it.captureId))
    }
}
