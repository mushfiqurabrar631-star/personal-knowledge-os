package com.pkos.core.notion

import com.pkos.core.notion.model.KnowledgeBody
import com.pkos.core.notion.model.NotionPageLink
import com.pkos.core.notion.model.SyncItem
import com.pkos.core.notion.sync.SyncOutcome
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Requirement 3: inject a failure at EVERY request of a sync (both "failed before Notion applied it" and
 * "Notion applied it but the response was lost"), then keep retrying like WorkManager would.
 * Each run must end Synced with exactly one page, exactly one PKOS body, no conflict, no new options,
 * and the legacy rows untouched. After success, losing the local mapping must not cause any write.
 */
class CrashChaosTest {

    private suspend fun retryUntilSynced(env: TestEnv, item: SyncItem, link: NotionPageLink?, label: String): SyncOutcome.Synced {
        repeat(8) { attempt ->
            when (val out = env.engine.sync(item, link, attempt)) {
                is SyncOutcome.Synced -> return out
                is SyncOutcome.Retry -> env.advance(300)
                else -> fail("$label: unexpected $out")
            }
        }
        fail("$label: never synced")
        error("unreachable")
    }

    private fun cleanCallCount(setup: suspend (TestEnv) -> NotionPageLink?, item: (TestEnv) -> SyncItem): Pair<Int, Int> = runBlocking {
        val env = TestEnv()
        val link = setup(env)
        val before = env.fake.calls.size
        env.engine.sync(item(env), link) as SyncOutcome.Synced
        before to env.fake.calls.size - before
    }

    @Test
    fun everyStepOfCreateIsSafeToRetry() {
        val captureId = java.util.UUID.randomUUID().toString()
        val itemOf = { env: TestEnv -> env.item(id = captureId) }
        val (_, steps) = cleanCallCount({ null }, itemOf)
        assertTrue(steps >= 4)
        for (k in 0 until steps) for (mode in FakeNotion.Mode.values()) runBlocking {
            val env = TestEnv()
            val label = "create step $k/$steps $mode"
            env.fake.failAtCall(k, mode)
            val item = itemOf(env)
            val done = retryUntilSynced(env, item, null, label)
            assertEquals(label, 1, env.fake.pagesWithCaptureId(captureId))
            assertEquals(label, 1, env.fake.markerCount(done.link.pageId, captureId))
            // mapping save "failed" after success: re-sync without link must write nothing
            val writes = env.fake.writes().size
            val again = env.engine.sync(item, null) as SyncOutcome.Synced
            assertFalse(label, again.propertiesWritten)
            assertEquals(label, writes, env.fake.writes().size)
            env.assertLegacyUntouched()
        }
    }

    @Test
    fun everyStepOfUpdateAndBodyAppendIsSafeToRetry() {
        val captureId = java.util.UUID.randomUUID().toString()
        val setup: suspend (TestEnv) -> NotionPageLink? = { env ->
            (env.engine.sync(env.item(id = captureId, body = KnowledgeBody()), null) as SyncOutcome.Synced).link
        }
        val enrichedOf = { env: TestEnv -> env.item(id = captureId, title = "AI দিয়ে marketing — updated", body = KnowledgeBody(summary = "AI সারাংশ")) }
        val (base, steps) = cleanCallCount(setup, enrichedOf)
        assertTrue(steps >= 5)
        for (k in 0 until steps) for (mode in FakeNotion.Mode.values()) runBlocking {
            val env = TestEnv()
            val label = "update step $k/$steps $mode"
            val link = setup(env)
            assertEquals(base, env.fake.calls.size)
            env.fake.failAtCall(base + k, mode)
            val item = enrichedOf(env)
            val done = retryUntilSynced(env, item, link, label)
            assertEquals(label, 1, env.fake.pagesWithCaptureId(captureId))
            assertEquals(label, 1, env.fake.markerCount(done.link.pageId, captureId))
            // count what Notion actually holds (a request that failed before applying is not a write)
            val summaries = env.fake.children.getValue(done.link.pageId).count { it.toString().contains("AI সারাংশ") }
            assertEquals("$label: PKOS body present exactly once", 1, summaries)
            val title = env.fake.pages.getValue(done.link.pageId)["properties"]!!.jsonObject["Topic name"].toString()
            assertTrue(label, title.contains("updated"))
            env.assertLegacyUntouched()
        }
    }
}
