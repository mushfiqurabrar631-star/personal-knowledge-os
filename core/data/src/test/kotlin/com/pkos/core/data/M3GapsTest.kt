package com.pkos.core.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pkos.core.data.capture.AttachmentStore
import com.pkos.core.data.capture.CaptureRepository
import com.pkos.core.data.capture.CaptureRequest
import com.pkos.core.data.capture.CaptureResult
import com.pkos.core.data.capture.TextSimilarity
import com.pkos.core.data.db.PkosDatabase
import com.pkos.core.data.model.CaptureMethod
import com.pkos.core.data.model.ItemStatus
import com.pkos.core.data.model.ProcessingState
import com.pkos.core.data.processing.AiInput
import com.pkos.core.data.processing.AiProvider
import com.pkos.core.data.processing.AiResponse
import com.pkos.core.data.processing.DefaultTextExtractor
import com.pkos.core.data.processing.ProcessingEngine
import com.pkos.core.data.repository.CategoryRepository
import com.pkos.core.data.repository.KnowledgeRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
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

private class OneAnswer(private val json: String) : AiProvider {
    override val name = "gemini"; override val model = "t"
    override suspend fun classify(input: AiInput, repairHint: String?) = AiResponse.Raw(json)
}

private fun aiJson(title: String = "AI title", topic: String? = "Kotlin", subtopic: String? = "Coroutines", confidence: Double = 0.92) = """
{"title":"$title","summary":"AI summary.","main_category":"Programming & Data","topic":${topic?.let { "\"$it\"" } ?: "null"},
 "subtopic":${subtopic?.let { "\"$it\"" } ?: "null"},"content_type":"tutorial","purpose":["learn"],"tags":["ai-tag"],"keywords":[],
 "priority":"normal","status":"done","related_item_ids":[],"action_items":[],"confidence":$confidence,"needs_review":false,"review_reason":null}
"""

/** M3 gaps: topic/subtopic persistence, fuzzy duplicates, owner edits, archive and soft delete. */
@RunWith(AndroidJUnit4::class)
class M3GapsTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var db: PkosDatabase
    private lateinit var capture: CaptureRepository
    private lateinit var engine: ProcessingEngine
    private lateinit var repo: KnowledgeRepository
    private var now = 1_800_000_000_000L

    @Before fun setUp() = runBlocking {
        db = PkosDatabase.inMemory(ApplicationProvider.getApplicationContext())
        CategoryRepository(db).ensureStarterTaxonomy()
        val store = AttachmentStore(tmp.newFolder())
        capture = CaptureRepository(db, store, { Long.MAX_VALUE }, clock = { now++ })
        engine = ProcessingEngine(db, store::resolve, DefaultTextExtractor(), null, clock = { now++ })
        repo = KnowledgeRepository(db, clock = { now++ })
    }

    @After fun tearDown() = db.close()

    private suspend fun save(text: String) = (capture.capture(CaptureRequest(CaptureMethod.SHARE, text = text)) as CaptureResult.Saved)

    @Test fun topicAndSubtopicAreSavedAndReused() = runBlocking {
        val a = save("kotlin coroutines one").itemId
        engine.process(a, OneAnswer(aiJson()))
        val item = db.knowledgeDao().get(a)!!
        val d = repo.detail(a).first()!!
        assertEquals("Kotlin", d.topic!!.name); assertEquals("Coroutines", d.subtopic!!.name)
        assertEquals(d.topic!!.id, d.subtopic!!.parentTopicId)
        assertEquals("sys-programming", d.topic!!.categoryId)
        assertEquals("AI", JSONObject(item.fieldOrigins).getString("topic"))
        val b = save("kotlin coroutines two").itemId
        engine.process(b, OneAnswer(aiJson(topic = "kotlin")))
        assertEquals(item.topicId, db.knowledgeDao().get(b)!!.topicId) // same name (any case) → same topic
        assertEquals(item.subtopicId, db.knowledgeDao().get(b)!!.subtopicId)
    }

    @Test fun lowConfidenceOrLocalRulesInventNoTopic() = runBlocking {
        val a = save("kotlin coroutines").itemId
        engine.process(a, OneAnswer(aiJson(confidence = 0.4)))
        assertNull(db.knowledgeDao().get(a)!!.topicId)
        val b = save("python sql database code").itemId
        engine.process(b, null)
        assertNull(db.knowledgeDao().get(b)!!.topicId)
    }

    @Test fun similarityHandlesBengaliAndEnglish() {
        val bn = "নতুন ব্যবসা শুরু করার জন্য মার্কেটিং পরিকল্পনা এবং বিক্রি বাড়ানোর দশটি কার্যকর উপায়"
        val en = "Ten practical ways to plan marketing and grow sales when starting a new small business"
        assertTrue(TextSimilarity.jaccard(TextSimilarity.shingles(bn), TextSimilarity.shingles("$bn!")) > 0.95)
        assertTrue(TextSimilarity.jaccard(TextSimilarity.shingles(bn), TextSimilarity.shingles(en)) < 0.2)
        assertEquals(null, TextSimilarity.bestMatch("short", listOf("x" to "short")))
    }

    @Test fun nearDuplicateIsFlaggedButKept() = runBlocking {
        val text = "Ten practical ways to plan marketing and grow sales when starting a new small business in Dhaka"
        val first = save(text)
        val near = save("$text!!  (shared again) https://fb.com/post/2")
        val other = save("A completely different note about Kotlin coroutines, flows and structured concurrency in Android apps")
        assertNull(first.duplicateOfId)
        assertEquals(first.itemId, near.duplicateOfId)
        assertNull(other.duplicateOfId)
        assertEquals(3, db.knowledgeDao().countAll())
    }

    @Test fun ownerEditsAreKeptWhenAiRunsLater() = runBlocking {
        val a = save("kotlin coroutines guide").itemId
        assertTrue(repo.editItem(a, "আমার শিরোনাম", "নিজের সারাংশ", listOf("নিজের", " #kotlin ", "নিজের"), ItemStatus.TO_LEARN))
        val origins = JSONObject(db.knowledgeDao().get(a)!!.fieldOrigins)
        listOf("title", "summary", "tags", "status").forEach { assertEquals(it, "USER", origins.getString(it)) }
        assertEquals(1, db.itemVersionDao().latestVersion(a))
        engine.process(a, OneAnswer(aiJson()))
        val item = db.knowledgeDao().get(a)!!
        assertEquals("আমার শিরোনাম", item.title); assertEquals("নিজের সারাংশ", item.summary); assertEquals(ItemStatus.TO_LEARN, item.status)
        assertEquals(setOf("নিজের", "kotlin"), db.tagDao().forItem(a).map { it.name }.toSet())
        assertEquals("kotlin coroutines guide", item.rawText) // original capture untouched
        assertEquals(ProcessingState.ORGANIZED, item.processingState)
    }

    @Test fun unchangedFieldsStayAiManaged() = runBlocking {
        val a = save("kotlin coroutines guide").itemId
        val title = db.knowledgeDao().get(a)!!.title
        repo.editItem(a, title, null, emptyList(), ItemStatus.DONE)
        val origins = JSONObject(db.knowledgeDao().get(a)!!.fieldOrigins)
        assertFalse(origins.optString("title") == "USER"); assertEquals("USER", origins.getString("status"))
        engine.process(a, OneAnswer(aiJson()))
        assertEquals("AI title", db.knowledgeDao().get(a)!!.title)
    }

    @Test fun archiveLeavesInboxButStaysSearchable() = runBlocking {
        val a = save("archive me please").itemId
        assertEquals(1, repo.counts().first().inbox)
        repo.setArchived(a, true)
        assertEquals(0, repo.counts().first().inbox)
        assertEquals(1, repo.counts().first().saved)
        assertEquals(listOf(a), repo.search("archive").first().map { it.id })
        repo.setArchived(a, false)
        assertEquals(1, repo.counts().first().inbox)
    }

    @Test fun deleteHidesEverywhereButKeepsTheRow() = runBlocking {
        val a = save("delete me please").itemId
        repo.delete(a)
        val c = repo.counts().first()
        assertEquals(0, c.saved); assertEquals(0, c.inbox)
        assertTrue(repo.search("delete").first().isEmpty())
        assertTrue(repo.recent().first().isEmpty())
        assertNotNull(db.knowledgeDao().get(a)!!.deletedAt) // soft delete: still in DB and in backups
        assertTrue(db.notionDao().itemsNeedingSync().isEmpty()) // never sent to (or deleted from) Notion
    }
}
