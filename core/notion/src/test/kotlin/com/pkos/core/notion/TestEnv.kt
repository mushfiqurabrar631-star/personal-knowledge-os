package com.pkos.core.notion

import com.pkos.core.notion.api.NotionClient
import com.pkos.core.notion.api.RateLimiter
import com.pkos.core.notion.model.ContentType
import com.pkos.core.notion.model.ItemPriority
import com.pkos.core.notion.model.ItemStatus
import com.pkos.core.notion.model.KnowledgeBody
import com.pkos.core.notion.model.SourcePlatform
import com.pkos.core.notion.model.SyncItem
import com.pkos.core.notion.sync.NotionSyncEngine
import com.pkos.core.notion.sync.RetryPolicy
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.time.Instant
import java.util.UUID
import kotlin.random.Random

/** Shared fixture: a fake Master Database with the owner's 15 legacy rows, a journal and an engine. */
class TestEnv(maxBodyScanPages: Int = 20) {
    val fake = FakeNotion()
    var journal = FakeJournal()
        private set
    var token: String? = "test-token"
    var now: Instant = Instant.parse("2026-11-20T05:00:00Z")
    val legacyIds: List<String> = (1..15).map { fake.seedLegacyPage("Old entry $it") }
    private val legacyPages: Map<String, JsonObject> = legacyIds.associateWith { fake.pages.getValue(it) }
    private val legacyChildren: Map<String, List<JsonObject>> = legacyIds.associateWith { fake.children.getValue(it).toList() }
    private val scanPages = maxBodyScanPages

    var engine: NotionSyncEngine = build()
        private set

    private fun build() = NotionSyncEngine(
        client = NotionClient(fake, { token }, RateLimiter.none()),
        dataSourceId = TEST_DS,
        journal = journal,
        retryPolicy = RetryPolicy(random = Random(1)),
        clock = { now },
        createLookupGraceMs = 120_000,
        maxBodyScanPages = scanPages,
    )

    /** Simulates losing the local database (journal and mappings), e.g. reinstall without restore. */
    fun loseLocalJournal() {
        journal = FakeJournal()
        engine = build()
    }

    fun advance(seconds: Long) { now = now.plusSeconds(seconds) }

    fun item(
        id: String = UUID.randomUUID().toString(),
        title: String = "Ai দিয়ে marketing",
        body: KnowledgeBody = KnowledgeBody(summary = "AI দিয়ে মার্কেটিং শেখার ভিডিও", keyPoints = listOf("Prompt", "Ads")),
    ) = SyncItem(
        captureId = id,
        title = title,
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

    /** The 15 old rows: identical properties and blocks, never written, no options created, no deletes. */
    fun assertLegacyUntouched() {
        legacyIds.forEach { id ->
            assertEquals("legacy page $id changed", legacyPages.getValue(id), fake.pages.getValue(id))
            assertEquals("legacy blocks $id changed", legacyChildren.getValue(id), fake.children.getValue(id))
        }
        val touched = fake.writes().filter { c -> legacyIds.any { c.path.contains(it) } }
        assertTrue("Legacy pages must never be written: $touched", touched.isEmpty())
        assertTrue("No new options may be created: ${fake.optionViolations}", fake.optionViolations.isEmpty())
        assertTrue("No delete calls", fake.calls.none { it.method == "DELETE" })
    }
}
