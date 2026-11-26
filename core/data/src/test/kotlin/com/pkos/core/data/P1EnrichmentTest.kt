package com.pkos.core.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pkos.core.data.capture.AttachmentStore
import com.pkos.core.data.capture.CaptureRepository
import com.pkos.core.data.capture.CaptureRequest
import com.pkos.core.data.capture.CaptureResult
import com.pkos.core.data.db.PkosDatabase
import com.pkos.core.data.features.EdgeReason
import com.pkos.core.data.features.GraphService
import com.pkos.core.data.features.SearchService
import com.pkos.core.data.model.CaptureMethod
import com.pkos.core.data.model.PrivacyScope
import com.pkos.core.data.processing.AiInput
import com.pkos.core.data.processing.AiProvider
import com.pkos.core.data.processing.AiResponse
import com.pkos.core.data.processing.AiResultParser
import com.pkos.core.data.processing.DefaultTextExtractor
import com.pkos.core.data.processing.LocalKeywords
import com.pkos.core.data.processing.ParsedAi
import com.pkos.core.data.processing.ProcessingEngine
import com.pkos.core.data.processing.ReviewReason
import com.pkos.core.data.processing.SensitiveData
import com.pkos.core.data.repository.CategoryRepository
import com.pkos.core.data.repository.KnowledgeRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** Spec 4.1/4.4: keywords, entities, related items, spec-style action_items, sensitive-data check before cloud. */
@RunWith(AndroidJUnit4::class)
class P1EnrichmentTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var db: PkosDatabase
    private lateinit var capture: CaptureRepository
    private lateinit var engine: ProcessingEngine
    private var now = 1_800_000_000_000L

    private class Cloud(val reply: (AiInput) -> String) : AiProvider {
        val inputs = mutableListOf<AiInput>()
        override val name = "gemini"; override val model = "m"
        override suspend fun classify(input: AiInput, repairHint: String?): AiResponse { inputs += input; return AiResponse.Raw(reply(input)) }
    }

    private fun json(extra: String) = """{"title":"Kotlin flows","summary":"Flows.","main_category":"Programming & Data","topic":"Kotlin","subtopic":null,
        "content_type":"article","purpose":["learn"],"tags":["kotlin"],"priority":"normal","status":"inbox","confidence":0.9,"needs_review":false,"review_reason":null$extra}"""

    @Before fun setUp() = runBlocking {
        db = PkosDatabase.inMemory(ApplicationProvider.getApplicationContext()); CategoryRepository(db).ensureStarterTaxonomy()
        val store = AttachmentStore(tmp.newFolder())
        capture = CaptureRepository(db, store, { Long.MAX_VALUE }, clock = { now++ })
        engine = ProcessingEngine(db, store::resolve, DefaultTextExtractor(), null, clock = { now++ })
    }
    @After fun tearDown() = db.close()
    private fun save(t: String, p: PrivacyScope = PrivacyScope.NORMAL) = runBlocking { (capture.capture(CaptureRequest(CaptureMethod.SHARE, text = t), p) as CaptureResult.Saved).itemId }

    @Test fun parserAcceptsSpecSchemaWithObjectsAndEnrichment() {
        val cats = mapOf("sys-programming" to "Programming & Data")
        val r = AiResultParser.parse(json(""","keywords":["coroutine","stream"],"entities":["JetBrains"],"related_item_ids":["r1"],
            "action_items":[{"text":"Read the flow guide","due_date":"2027-01-01"},"Try a sample"]"""), cats)
        r as ParsedAi.Valid
        assertEquals(listOf("Read the flow guide", "Try a sample"), r.result.actionItems) // due date dropped
        assertEquals(listOf("coroutine", "stream"), r.result.keywords); assertEquals(listOf("JetBrains"), r.result.entities)
        assertEquals(listOf("r1"), r.result.relatedRefs)
        assertTrue(AiResultParser.parse(json(""","action_items":[42]"""), cats) is ParsedAi.Invalid)
    }

    @Test fun cloudEnrichmentIsStoredSearchableAndLinked() = runBlocking {
        val older = save("Kotlin flows deep dive")
        val id = save("Kotlin flows article about cold streams")
        val cloud = Cloud { input -> json(""","keywords":["coldstream"],"entities":["JetBrains"],"related_item_ids":["${input.candidates.first { it.second.contains("deep dive") }.first}"],"action_items":[]""") }
        engine.process(id, cloud)
        assertTrue(cloud.inputs.single().candidates.any { it.second.contains("deep dive") }) // only titles of other items
        val item = db.knowledgeDao().get(id)!!
        assertEquals(listOf("coldstream"), JSONArray(item.keywords).let { a -> (0 until a.length()).map(a::getString) })
        assertTrue(item.entities!!.contains("JetBrains"))
        val s = SearchService(db, { now }); s.refreshIndex()
        assertEquals(listOf(id), s.search("coldstream", semantic = false).map { it.item.id })
        assertEquals(listOf(id), s.search("jetbrains", semantic = false).map { it.item.id })
        assertTrue(GraphService(db, { now }).neighbors(id).any { it.id == older && it.reason == EdgeReason.AI_RELATED })
    }

    @Test fun localEngineAlsoPicksKeywords() {
        assertEquals(listOf("budget", "saving"), LocalKeywords.pick("budget saving plan budget saving tips budget", exclude = listOf("plan")).take(2))
    }

    @Test fun sensitiveTextStaysOnThePhoneUnlessTheOwnerAllows() = runBlocking {
        assertTrue(SensitiveData.Kind.CARD in SensitiveData.find("card 4111 1111 1111 1111 exp 12/29"))
        assertFalse(SensitiveData.Kind.CARD in SensitiveData.find("order 1234 5678 9012 3456")) // fails Luhn
        assertTrue(SensitiveData.Kind.OTP in SensitiveData.find("Your OTP is 482913"))
        assertTrue(SensitiveData.Kind.PASSWORD in SensitiveData.find("wifi password: hunter22"))
        assertTrue(SensitiveData.Kind.SECRET_KEY in SensitiveData.find("key AIzaSyA1234567890abcdefghijklmnopqrstu"))
        assertTrue(SensitiveData.Kind.NATIONAL_ID in SensitiveData.find("NID 1234567890123"))
        assertTrue(SensitiveData.find("Kotlin flows are cold streams").isEmpty())

        val id = save("bank login password: hunter22 and notes about kotlin")
        val cloud = Cloud { json(""","action_items":[]""") }
        engine.process(id, cloud)
        assertTrue(cloud.inputs.isEmpty()) // nothing sent
        val item = db.knowledgeDao().get(id)!!
        assertEquals(PrivacyScope.LOCAL_ONLY, item.privacyScope)
        assertEquals(ReviewReason.SENSITIVE_LOCAL, item.reviewReason)

        // The owner explicitly allows cloud for this item: respected on the next run.
        KnowledgeRepository(db, clock = { now++ }).setLocalOnly(id, false)
        db.processingJobDao().insert(com.pkos.core.data.db.entity.ProcessingJobEntity(java.util.UUID.randomUUID().toString(), id, com.pkos.core.data.model.ProcessingJobType.AI_CLASSIFY, com.pkos.core.data.model.JobState.QUEUED, 0, null, null, now, now))
        engine.process(id, cloud)
        assertEquals(1, cloud.inputs.size)
    }
}
