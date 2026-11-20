package com.pkos.core.notion

import com.pkos.core.notion.mapping.BodyMarker
import com.pkos.core.notion.mapping.PageBodyBuilder
import com.pkos.core.notion.model.KnowledgeBody
import com.pkos.core.notion.sync.BodyState
import com.pkos.core.notion.sync.SyncOutcome
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/** Requirement 1 + 4: PKOS body is recognised only by its own marker; owner content is never touched. */
class BodyMarkerTest {

    @Test
    fun builtBodyEndsWithThisItemsMarker() {
        val env = TestEnv()
        val item = env.item(body = KnowledgeBody(summary = "s", ocrText = "x ".repeat(30_000)))
        val blocks = PageBodyBuilder.build(item)
        assertTrue(blocks.size <= PageBodyBuilder.MAX_BLOCKS)
        assertTrue(blocks.last().toString().contains(BodyMarker.token(item.captureId)))
        assertEquals(1, blocks.count { it.toString().contains(BodyMarker.PREFIX) })
    }

    @Test
    fun ownerBlocksWithoutMarkerDoNotCountAsPkosBody() = runBlocking {
        val env = TestEnv()
        val item = env.item(body = KnowledgeBody())
        val first = env.engine.sync(item, null) as SyncOutcome.Synced
        env.fake.userAppend(first.link.pageId, "আমার নিজের নোট ১", "আমার নিজের নোট ২")
        val ownerBlocks = env.fake.children.getValue(first.link.pageId).toList()

        val enriched = item.copy(body = KnowledgeBody(summary = "AI summary"))
        val out = env.engine.sync(enriched, first.link) as SyncOutcome.Synced

        assertEquals(BodyState.WRITTEN, out.bodyState)
        assertEquals(1, env.fake.appendCount())
        val after = env.fake.children.getValue(first.link.pageId)
        assertEquals("owner blocks stay first and unchanged", ownerBlocks, after.take(2))
        assertEquals(1, env.fake.markerCount(first.link.pageId, item.captureId))
        env.assertLegacyUntouched()
    }

    @Test
    fun markerOfAnotherItemIsIgnored() = runBlocking {
        val env = TestEnv()
        val item = env.item(body = KnowledgeBody())
        val first = env.engine.sync(item, null) as SyncOutcome.Synced
        env.fake.userAppend(first.link.pageId, "copied: PKOS · ${BodyMarker.token(UUID.randomUUID().toString())}")
        val out = env.engine.sync(item.copy(body = KnowledgeBody(summary = "AI")), first.link) as SyncOutcome.Synced
        assertEquals(BodyState.WRITTEN, out.bodyState)
        assertEquals(1, env.fake.appendCount())
    }

    @Test
    fun lostAppendResponseDoesNotDuplicateBody() = runBlocking {
        val env = TestEnv()
        val item = env.item(body = KnowledgeBody())
        val first = env.engine.sync(item, null) as SyncOutcome.Synced
        val enriched = item.copy(body = KnowledgeBody(summary = "AI"))
        env.fake.loseResponseOfNext("PATCH", "/blocks/")
        val failed = env.engine.sync(enriched, first.link)
        assertTrue(failed is SyncOutcome.Retry)
        // local link still says bodyWritten=false; the marker scan must find the applied append
        val retry = env.engine.sync(enriched, first.link, attempt = 1) as SyncOutcome.Synced
        assertEquals(BodyState.WRITTEN, retry.bodyState)
        assertEquals(1, env.fake.appendCount())
        assertEquals(1, env.fake.markerCount(first.link.pageId, item.captureId))
    }

    @Test
    fun markerOnLaterChildrenPageIsFound() = runBlocking {
        val env = TestEnv()
        env.fake.childrenPageSize = 2
        val item = env.item(body = KnowledgeBody())
        val first = env.engine.sync(item, null) as SyncOutcome.Synced
        env.fake.userAppend(first.link.pageId, "n1", "n2", "n3", "n4", "n5")
        env.engine.sync(item.copy(body = KnowledgeBody(summary = "AI")), first.link) as SyncOutcome.Synced
        env.loseLocalJournal()
        val listsBefore = env.fake.calls.count { it.method == "GET" && it.path.contains("/children") }
        val adopted = env.engine.sync(item.copy(body = KnowledgeBody(summary = "AI")), null) as SyncOutcome.Synced
        assertEquals(BodyState.WRITTEN, adopted.bodyState)
        assertTrue("scanned several pages", env.fake.calls.count { it.method == "GET" && it.path.contains("/children") } - listsBefore > 1)
        assertEquals(1, env.fake.appendCount())
    }

    @Test
    fun tooLongToScanIsUnknownAndNothingIsAppended() = runBlocking {
        val env = TestEnv(maxBodyScanPages = 2)
        env.fake.childrenPageSize = 2
        val item = env.item(body = KnowledgeBody())
        val first = env.engine.sync(item, null) as SyncOutcome.Synced
        env.fake.userAppend(first.link.pageId, *Array(10) { "owner $it" })
        val out = env.engine.sync(item.copy(body = KnowledgeBody(summary = "AI")), first.link) as SyncOutcome.Synced
        assertEquals(BodyState.UNKNOWN, out.bodyState)
        assertFalse(out.link.bodyWritten)
        assertEquals(0, env.fake.appendCount())
    }

    @Test
    fun ownerContentSurvivesAFullLifecycle() = runBlocking {
        val env = TestEnv()
        val item = env.item(body = KnowledgeBody())
        var link = (env.engine.sync(item, null) as SyncOutcome.Synced).link
        env.fake.userAppend(link.pageId, "আমার মন্তব্য", "দ্বিতীয় লাইন")
        val owner = env.fake.children.getValue(link.pageId).toList()
        val steps = listOf(
            item.copy(body = KnowledgeBody(summary = "AI")),
            item.copy(title = "নতুন শিরোনাম", body = KnowledgeBody(summary = "AI v2")),
            item.copy(title = "নতুন শিরোনাম", pinned = true, body = KnowledgeBody(summary = "AI v3")),
        )
        for (s in steps) link = (env.engine.sync(s, link) as SyncOutcome.Synced).link
        val blocks = env.fake.children.getValue(link.pageId)
        assertEquals(owner, blocks.take(2))
        assertEquals("one PKOS body appended once", 1, env.fake.appendCount())
        assertEquals(1, env.fake.markerCount(link.pageId, item.captureId))
        val summaryTexts = blocks.map { it.toString() }.filter { it.contains("AI v") }
        assertTrue("body is never rewritten (v2/v3 not added)", summaryTexts.isEmpty())
        assertTrue(env.fake.calls.none { it.method == "DELETE" })
        assertTrue("no block-level edits", env.fake.calls.none { it.method == "PATCH" && it.path.startsWith("/blocks/") && !it.path.endsWith("/children") })
        val title = env.fake.pages.getValue(link.pageId)["properties"]!!.jsonObject["Topic name"]!!.jsonObject
        assertTrue(title.toString().contains("নতুন শিরোনাম"))
        assertEquals("true", env.fake.pages.getValue(link.pageId)["properties"]!!.jsonObject["Pinned"]!!.jsonObject["checkbox"]!!.jsonPrimitive.content)
        env.assertLegacyUntouched()
    }
}
