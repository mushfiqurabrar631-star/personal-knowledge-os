package com.pkos.core.notion

import com.pkos.core.notion.api.NotionClient
import com.pkos.core.notion.api.NotionErrorKind
import com.pkos.core.notion.api.RateLimiter
import com.pkos.core.notion.model.ContentType
import com.pkos.core.notion.model.ItemPriority
import com.pkos.core.notion.model.ItemStatus
import com.pkos.core.notion.model.KnowledgeBody
import com.pkos.core.notion.model.PrivacyScope
import com.pkos.core.notion.model.SourcePlatform
import com.pkos.core.notion.model.SyncItem
import com.pkos.core.notion.sync.ActionReason
import com.pkos.core.notion.sync.NotionSyncEngine
import com.pkos.core.notion.sync.RetryPolicy
import com.pkos.core.notion.sync.SyncOutcome
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.UUID
import kotlin.random.Random

class NotionSyncEngineTest {
    private lateinit var fake: FakeNotion
    private lateinit var engine: NotionSyncEngine
    private var token: String? = "secret_test"
    private lateinit var legacyIds: List<String>

    @Before
    fun setUp() {
        fake = FakeNotion()
        legacyIds = (1..15).map { fake.seedLegacyPage("Old entry $it") }
        val client = NotionClient(fake, { token }, RateLimiter.none())
        engine = NotionSyncEngine(client, TEST_DS, retryPolicy = RetryPolicy(random = Random(1)))
    }

    private fun item(
        id: String = UUID.randomUUID().toString(),
        body: KnowledgeBody = KnowledgeBody(summary = "AI দিয়ে মার্কেটিং শেখার ভিডিও", keyPoints = listOf("Prompt", "Ads")),
    ) = SyncItem(
        captureId = id,
        title = "Ai দিয়ে marketing",
        sourceUrl = "https://youtu.be/ynZp8-FjYUo?si=wFYeGdw0Jweb12Gd",
        capturedAt = Instant.parse("2026-11-20T04:30:00Z"),
        contentType = ContentType.VIDEO,
        status = ItemStatus.TO_LEARN,
        priority = ItemPriority.HIGH,
        mainCategory = "AI",
        tags = listOf("Business"),
        platform = SourcePlatform.YOUTUBE,
        body = body,
    )

    private fun assertLegacyUntouched() {
        val touched = fake.writes().filter { c -> legacyIds.any { c.path.contains(it) } }
        assertTrue("Legacy pages must never be written: $touched", touched.isEmpty())
        assertTrue("No new options may be created: ${fake.optionViolations}", fake.optionViolations.isEmpty())
    }

    @Test
    fun createsVerifiedPageWithBodyWhenNotLinked() = runBlocking {
        val it = item()
        val out = engine.sync(it, existing = null)
        assertTrue(out is SyncOutcome.Synced)
        out as SyncOutcome.Synced
        assertTrue(out.created)
        assertTrue(out.link.bodyWritten)
        assertEquals(16, fake.pages.size)
        val page = fake.pages.getValue(out.link.pageId)["properties"]!!.jsonObject
        assertTrue(page["Capture ID"].toString().contains(it.captureId))
        assertTrue(page["Link/URL"].toString().contains("https://youtu.be/ynZp8-FjYUo\""))
        assertTrue(page["platform "].toString().contains("Youtube"))
        assertTrue(page["Date Added"].toString().contains("2026-11-20"))
        assertFalse(page["Notes"].toString().contains("name"))
        assertTrue(fake.children.getValue(out.link.pageId).isNotEmpty())
        assertEquals("schema read before write", "GET", fake.calls.first().method)
        assertLegacyUntouched()
    }

    @Test
    fun secondSyncUpdatesSamePageAndNeverDuplicates() = runBlocking {
        val it = item()
        val first = engine.sync(it, null) as SyncOutcome.Synced
        val second = engine.sync(it.copy(title = "Updated title"), first.link) as SyncOutcome.Synced
        assertFalse(second.created)
        assertEquals(first.link.pageId, second.link.pageId)
        assertEquals(16, fake.pages.size)
        assertEquals(1, fake.calls.count { c -> c.method == "POST" && c.path == "/pages" })
        assertEquals("body written at create, never appended again", 0,
            fake.calls.count { c -> c.path.endsWith("/children") && c.method == "PATCH" })
        assertLegacyUntouched()
    }

    @Test
    fun lostLocalLinkIsRecoveredByCaptureIdLookup() = runBlocking {
        val it = item()
        engine.sync(it, null) as SyncOutcome.Synced
        // e.g. reinstall: local mapping gone, same Capture ID restored from backup
        val again = engine.sync(it, null) as SyncOutcome.Synced
        assertFalse(again.created)
        assertEquals(16, fake.pages.size)
        assertLegacyUntouched()
    }

    @Test
    fun responseLostAfterCreateDoesNotDuplicateOnRetry() = runBlocking {
        val it = item()
        fake.loseNextCreateResponse = true
        val first = engine.sync(it, null)
        assertTrue(first is SyncOutcome.Retry && first.reason == NotionErrorKind.NETWORK)
        val retry = engine.sync(it, null, attempt = 1) as SyncOutcome.Synced
        assertFalse(retry.created)
        assertEquals(16, fake.pages.size)
        assertEquals("body not appended twice", 0, fake.calls.count { c -> c.method == "PATCH" && c.path.endsWith("/children") })
        assertLegacyUntouched()
    }

    @Test
    fun unknownOptionsAreSkippedNotCreated() = runBlocking {
        val it = item().copy(
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
        assertLegacyUntouched()
    }

    @Test
    fun missingCaptureIdPropertyBlocksAllWrites() = runBlocking {
        fake.schema = MasterSchemaFixture.json(captureIdType = null)
        val out = engine.sync(item(), null)
        assertTrue(out is SyncOutcome.ActionRequired && out.reason == ActionReason.SCHEMA_INCOMPATIBLE)
        assertTrue(fake.writes().isEmpty())
    }

    @Test
    fun wrongCaptureIdTypeBlocksAllWrites() = runBlocking {
        fake.schema = MasterSchemaFixture.json(captureIdType = "number")
        val out = engine.sync(item(), null)
        assertTrue(out is SyncOutcome.ActionRequired && out.reason == ActionReason.SCHEMA_INCOMPATIBLE)
        assertTrue(fake.writes().isEmpty())
    }

    @Test
    fun remoteEditIsReportedAsConflictAndNotOverwritten() = runBlocking {
        val it = item()
        val first = engine.sync(it, null) as SyncOutcome.Synced
        fake.userEdit(first.link.pageId, "Status", buildJsonObject { putJsonObject("select") { put("name", "Completed") } })
        val out = engine.sync(it.copy(title = "App change"), first.link)
        assertTrue(out is SyncOutcome.Conflict)
        assertEquals(0, fake.calls.count { c -> c.method == "PATCH" && c.path == "/pages/${first.link.pageId}" })
        val forced = engine.sync(it.copy(title = "App change"), first.link, overwriteRemoteEdits = true)
        assertTrue(forced is SyncOutcome.Synced)
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
            val out = engine.sync(item(), null)
            assertTrue("$status → $out", out is SyncOutcome.ActionRequired && out.reason == reason)
        }
        assertTrue(fake.writes().isEmpty())
    }

    @Test
    fun retryableErrorsScheduleBackoff() = runBlocking {
        fake.failNext("POST", "/pages", 429, "rate_limited", retryAfter = "7")
        val limited = engine.sync(item(), null)
        assertEquals(SyncOutcome.Retry(NotionErrorKind.RATE_LIMITED, 7_000), limited)

        fake.failNext("POST", "/pages", 500)
        val server = engine.sync(item(), null)
        assertTrue(server is SyncOutcome.Retry && server.reason == NotionErrorKind.SERVER && server.delayMs >= 1_000)

        fake.failNext("POST", "/pages", 409, "conflict_error")
        val conflict = engine.sync(item(), null)
        assertTrue(conflict is SyncOutcome.Retry && conflict.reason == NotionErrorKind.CONFLICT)

        fake.throwNetworkOnNext = true
        val offline = engine.sync(item(), null)
        assertTrue(offline is SyncOutcome.Retry && offline.reason == NotionErrorKind.NETWORK)

        fake.failNext("POST", "/pages", 503)
        val exhausted = engine.sync(item(), null, attempt = 7)
        assertTrue(exhausted is SyncOutcome.ActionRequired && exhausted.reason == ActionReason.RETRIES_EXHAUSTED)
        assertEquals("no page created by failed attempts", 15, fake.pages.size)
    }

    @Test
    fun missingTokenNeedsAction() = runBlocking {
        token = null
        val out = engine.sync(item(), null)
        assertTrue(out is SyncOutcome.ActionRequired && out.reason == ActionReason.NO_TOKEN)
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun localOnlyItemsAreNeverSent() = runBlocking {
        val out = engine.sync(item().copy(privacyScope = PrivacyScope.LOCAL_ONLY), null)
        assertTrue(out is SyncOutcome.NotEligible)
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun invalidCaptureIdIsRejected() = runBlocking {
        val out = engine.sync(item(id = "not-a-uuid"), null)
        assertTrue(out is SyncOutcome.ActionRequired && out.reason == ActionReason.INVALID_CAPTURE_ID)
        val upper = engine.sync(item(id = UUID.randomUUID().toString().uppercase()), null)
        assertTrue(upper is SyncOutcome.ActionRequired)
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun linkPointingAtLegacyPageIsRefused() = runBlocking {
        val it = item()
        val good = engine.sync(it, null) as SyncOutcome.Synced
        val hijacked = good.link.copy(pageId = legacyIds.first())
        val out = engine.sync(it, hijacked)
        assertTrue(out is SyncOutcome.ActionRequired && out.reason == ActionReason.PAGE_NOT_OWNED)
        assertLegacyUntouched()
    }

    @Test
    fun deletedRemotePageIsNotSilentlyRecreated() = runBlocking {
        val it = item()
        val first = engine.sync(it, null) as SyncOutcome.Synced
        fake.pages.remove(first.link.pageId)
        val out = engine.sync(it, first.link)
        assertTrue(out is SyncOutcome.ActionRequired && out.reason == ActionReason.PAGE_MISSING)
        assertEquals(15, fake.pages.size)
    }

    @Test
    fun duplicateRemotePagesNeedUserDecision() = runBlocking {
        val it = item()
        engine.sync(it, null) as SyncOutcome.Synced
        fake.pages.keys.last().let { id -> fake.pages[id + "-copy"] = fake.pages.getValue(id) }
        val out = engine.sync(it, null)
        assertTrue(out is SyncOutcome.ActionRequired && out.reason == ActionReason.DUPLICATE_REMOTE_PAGES)
    }

    @Test
    fun itemWithoutAiYetGetsBodyLaterExactlyOnce() = runBlocking {
        val it = item(body = KnowledgeBody())
        val first = engine.sync(it, null) as SyncOutcome.Synced
        assertFalse(first.link.bodyWritten)
        val enriched = it.copy(body = KnowledgeBody(summary = "পরে AI সারসংক্ষেপ"))
        val second = engine.sync(enriched, first.link) as SyncOutcome.Synced
        assertTrue(second.link.bodyWritten)
        engine.sync(enriched, second.link) as SyncOutcome.Synced
        assertEquals(1, fake.calls.count { c -> c.method == "PATCH" && c.path.endsWith("/children") })
    }
}
