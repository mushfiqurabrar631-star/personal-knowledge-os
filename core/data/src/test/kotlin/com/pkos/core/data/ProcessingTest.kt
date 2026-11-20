package com.pkos.core.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pkos.core.data.capture.AttachmentStore
import com.pkos.core.data.capture.CaptureFile
import com.pkos.core.data.capture.CaptureRepository
import com.pkos.core.data.capture.CaptureRequest
import com.pkos.core.data.capture.CaptureResult
import com.pkos.core.data.db.PkosDatabase
import com.pkos.core.data.model.CaptureMethod
import com.pkos.core.data.model.ContentType
import com.pkos.core.data.model.ExtractionStatus
import com.pkos.core.data.model.JobState
import com.pkos.core.data.model.PrivacyScope
import com.pkos.core.data.model.ProcessingState
import com.pkos.core.data.processing.AiInput
import com.pkos.core.data.processing.AiProvider
import com.pkos.core.data.processing.AiResponse
import com.pkos.core.data.processing.AiResultParser
import com.pkos.core.data.processing.DefaultTextExtractor
import com.pkos.core.data.processing.GeminiProvider
import com.pkos.core.data.processing.HttpReply
import com.pkos.core.data.processing.LocalClassifier
import com.pkos.core.data.processing.OcrEngine
import com.pkos.core.data.processing.ParsedAi
import com.pkos.core.data.processing.ProcessOutcome
import com.pkos.core.data.processing.ProcessingEngine
import com.pkos.core.data.repository.CategoryRepository
import com.pkos.core.data.repository.StarterTaxonomy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.IOException

private val CATS = StarterTaxonomy.categories.associate { it.id to it.name }

private fun json(category: String? = "Programming & Data", confidence: Any = 0.92, type: String = "tutorial", extra: String = "") = """
{"title":"Kotlin coroutines guide","summary":"How coroutines work.","main_category":${category?.let { "\"$it\"" } ?: "null"},
 "topic":"Kotlin","subtopic":null,"content_type":"$type","purpose":["learn"],"tags":["kotlin","coroutines"],"keywords":[],
 "priority":"normal","status":"inbox","related_item_ids":[],"action_items":[],"confidence":$confidence,"needs_review":false,"review_reason":null$extra}
""".trimIndent()

/** Scripted cloud provider: returns the queued responses in order and records what it was sent. */
private class ScriptedAi(vararg responses: AiResponse) : AiProvider {
    val queue = ArrayDeque(responses.toList())
    val inputs = mutableListOf<Pair<AiInput, String?>>()
    override val name = "gemini"
    override val model = "test-model"
    override suspend fun classify(input: AiInput, repairHint: String?): AiResponse { inputs += input to repairHint; return queue.removeFirst() }
}

@RunWith(AndroidJUnit4::class)
class ProcessingEngineTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var db: PkosDatabase
    private lateinit var store: AttachmentStore
    private lateinit var capture: CaptureRepository
    private var ocrText = "RSO meeting target report"
    private var ocrFailures = 0
    private lateinit var engine: ProcessingEngine

    @Before fun setUp() = runBlocking {
        db = PkosDatabase.inMemory(ApplicationProvider.getApplicationContext())
        CategoryRepository(db).ensureStarterTaxonomy()
        store = AttachmentStore(tmp.newFolder("att"))
        capture = CaptureRepository(db, store, { Long.MAX_VALUE })
        engine = ProcessingEngine(db, store::resolve, DefaultTextExtractor(), OcrEngine { if (ocrFailures-- > 0) throw IOException("busy") else ocrText })
    }

    @After fun tearDown() = db.close()

    private suspend fun save(text: String? = null, files: List<CaptureFile> = emptyList(), privacy: PrivacyScope = PrivacyScope.NORMAL) =
        (capture.capture(CaptureRequest(CaptureMethod.SHARE, text = text, files = files), privacy) as CaptureResult.Saved).itemId

    @Test fun highConfidenceCloudResultOrganizesItem() = runBlocking {
        val id = save("Kotlin coroutines tutorial https://kotlinlang.org/docs/coroutines-guide.html")
        val ai = ScriptedAi(AiResponse.Raw(json()))
        assertEquals(ProcessOutcome.Done(ProcessingState.ORGANIZED), engine.process(id, ai))
        val item = db.knowledgeDao().get(id)!!
        assertEquals("sys-programming", item.categoryId)
        assertEquals(ContentType.TUTORIAL, item.contentType)
        assertEquals("Kotlin coroutines guide", item.title) // system title may be improved
        assertEquals("learn", item.purpose)
        assertTrue(item.rawText!!.startsWith("Kotlin coroutines tutorial")) // original kept
        assertEquals("AI", JSONObject(item.fieldOrigins).getString("category"))
        assertEquals(listOf("OK"), db.aiRunDao().forItem(id).map { it.outcome })
        assertTrue(db.processingJobDao().forItem(id).all { it.state == JobState.SUCCEEDED })
        assertEquals(1, db.itemVersionDao().latestVersion(id))
    }

    @Test fun lowConfidenceGoesToUnsortedNeedsReview() = runBlocking {
        val id = save("something")
        engine.process(id, ScriptedAi(AiResponse.Raw(json(confidence = 0.4))))
        val item = db.knowledgeDao().get(id)!!
        assertEquals(ProcessingState.NEEDS_REVIEW, item.processingState)
        assertEquals(StarterTaxonomy.UNSORTED, item.categoryId)
        assertEquals("something", item.title) // low confidence never renames
    }

    @Test fun inventedCategoryIsNeverAccepted() = runBlocking {
        val id = save("random text here for test")
        engine.process(id, ScriptedAi(AiResponse.Raw(json(category = "Cooking Secrets"))))
        val item = db.knowledgeDao().get(id)!!
        assertEquals(ProcessingState.NEEDS_REVIEW, item.processingState)
        assertEquals(StarterTaxonomy.UNSORTED, item.categoryId)
    }

    @Test fun malformedOutputIsRepairedOnceThenAccepted() = runBlocking {
        val id = save("python data analysis course")
        val ai = ScriptedAi(AiResponse.Raw("Sure! here it is"), AiResponse.Raw(json()))
        assertEquals(ProcessOutcome.Done(ProcessingState.ORGANIZED), engine.process(id, ai))
        assertEquals("not a JSON object", ai.inputs[1].second)
        assertEquals(listOf("OK", "INVALID_OUTPUT"), db.aiRunDao().forItem(id).map { it.outcome }.sortedDescending())
    }

    @Test fun twiceMalformedFallsBackToLocalAndAsksForReview() = runBlocking {
        val id = save("python data analysis course with sql and excel")
        engine.process(id, ScriptedAi(AiResponse.Raw("{"), AiResponse.Raw("""{"title":1}""")))
        val item = db.knowledgeDao().get(id)!!
        assertEquals(com.pkos.core.data.processing.ReviewReason.CLOUD_INVALID, item.reviewReason)
        assertEquals(2, db.aiRunDao().forItem(id).count { it.outcome == "INVALID_OUTPUT" })
        assertTrue(db.aiRunDao().forItem(id).any { it.provider == LocalClassifier.PROVIDER })
    }

    @Test fun rateLimitIsRetriedLaterWithoutLosingTheItem() = runBlocking {
        val id = save("kotlin code")
        val out = engine.process(id, ScriptedAi(AiResponse.RateLimited(45_000)))
        assertTrue(out is ProcessOutcome.Retry)
        assertEquals(ProcessingState.AI_PENDING, db.knowledgeDao().get(id)!!.processingState)
        assertEquals(JobState.RETRY_SCHEDULED, db.processingJobDao().forItem(id).single().state)
        // later the provider answers
        assertEquals(ProcessOutcome.Done(ProcessingState.ORGANIZED), engine.process(id, ScriptedAi(AiResponse.Raw(json()))))
    }

    @Test fun repeatedRateLimitsEventuallyFallBackToLocal() = runBlocking {
        val id = save("python sql database code github")
        repeat(ProcessingEngine.MAX_CLOUD_ATTEMPTS - 1) { assertTrue(engine.process(id, ScriptedAi(AiResponse.RateLimited(1))) is ProcessOutcome.Retry) }
        val out = engine.process(id, ScriptedAi(AiResponse.RateLimited(1)))
        assertTrue(out is ProcessOutcome.Done)
        assertEquals("sys-programming", db.knowledgeDao().get(id)!!.categoryId)
    }

    @Test fun localOnlyItemsNeverReachTheCloud() = runBlocking {
        val id = save("python sql database code", privacy = PrivacyScope.LOCAL_ONLY)
        val ai = ScriptedAi(AiResponse.Raw(json()))
        engine.process(id, ai)
        assertTrue(ai.inputs.isEmpty())
        assertEquals(LocalClassifier.PROVIDER, db.aiRunDao().forItem(id).single().provider)
    }

    @Test fun userEditedFieldsAreNeverOverwritten() = runBlocking {
        val id = save("kotlin coroutines")
        val item = db.knowledgeDao().get(id)!!
        db.knowledgeDao().update(item.copy(categoryId = "sys-ideas", title = "আমার শিরোনাম", fieldOrigins = """{"title":"USER","category":"USER"}"""))
        engine.process(id, ScriptedAi(AiResponse.Raw(json())))
        val after = db.knowledgeDao().get(id)!!
        assertEquals("sys-ideas", after.categoryId)
        assertEquals("আমার শিরোনাম", after.title)
        assertEquals(ContentType.TUTORIAL, after.contentType) // AI may still fill fields the owner did not set
    }

    @Test fun imageIsOcrdAndTextFileIsExtractedBeforeClassification() = runBlocking {
        val id = save(
            files = listOf(
                CaptureFile("shot.png", "image/png", null) { ByteArrayInputStream(ByteArray(64) { 1 }) },
                CaptureFile("notes.txt", "text/plain", null) { ByteArrayInputStream("marketing plan for sales".toByteArray()) },
            ),
        )
        engine.process(id, null)
        val att = db.attachmentDao().forItem(id).associateBy { it.filename }
        assertEquals("RSO meeting target report", att["shot.png"]!!.extractedText)
        assertEquals("marketing plan for sales", att["notes.txt"]!!.extractedText)
        assertTrue(att.values.all { it.extractionStatus == ExtractionStatus.DONE })
        assertFalse(db.knowledgeDao().get(id)!!.processingState.isInProgress)
    }

    @Test fun ocrFailureIsRetriedThenTheItemStillGetsOrganized() = runBlocking {
        ocrFailures = 10
        val id = save(text = "python code", files = listOf(CaptureFile("s.png", "image/png", null) { ByteArrayInputStream(ByteArray(8)) }))
        assertTrue(engine.process(id, null) is ProcessOutcome.Retry)
        assertTrue(engine.process(id, null) is ProcessOutcome.Retry)
        val last = engine.process(id, null)
        assertTrue(last is ProcessOutcome.Done)
        assertEquals(ExtractionStatus.FAILED, db.attachmentDao().forItem(id).single().extractionStatus)
    }

    @Test fun processingIsIdempotent() = runBlocking {
        val id = save("python sql")
        engine.process(id, null)
        assertEquals(ProcessOutcome.NothingToDo, engine.process(id, null))
        assertEquals(1, db.itemVersionDao().latestVersion(id))
    }

    @Test fun localTagsAreLinked() = runBlocking {
        val id = save("python sql database course")
        engine.process(id, null)
        val tags = db.tagDao().observeForItem(id).first().map { it.normalizedName }
        assertTrue(tags.contains("python"))
    }
}

@RunWith(AndroidJUnit4::class)
class AiResultParserTest {
    @Test fun validJsonParses() {
        val r = (AiResultParser.parse(json(), CATS) as ParsedAi.Valid).result
        assertEquals("sys-programming", r.categoryId); assertEquals(0.92, r.confidence, 1e-9); assertEquals(listOf("learn"), r.purpose)
    }

    @Test fun percentConfidenceIsNormalised() = assertEquals(0.8, (AiResultParser.parse(json(confidence = 80), CATS) as ParsedAi.Valid).result.confidence, 1e-9)

    @Test fun codeFencesAreAccepted() = assertTrue(AiResultParser.parse("```json\n${json()}\n```", CATS) is ParsedAi.Valid)

    @Test fun malformedOrOutOfSchemaIsRejected() {
        listOf(
            "not json", "[]", """{"summary":"x"}""", json(type = "podcast"), json(confidence = 170), json(confidence = "\"high\""),
            json().replace("\"learn\"", "\"party\""), json().replace("\"normal\"", "\"urgent\""), json().replace("[\"kotlin\",\"coroutines\"]", "\"kotlin\""),
        ).forEach { assertTrue(it, AiResultParser.parse(it, CATS) is ParsedAi.Invalid) }
    }

    @Test fun categoryMatchesNameCaseInsensitivelyOrId() {
        assertEquals("sys-business", (AiResultParser.parse(json(category = "business & finance"), CATS) as ParsedAi.Valid).result.categoryId)
        assertEquals("sys-business", (AiResultParser.parse(json(category = "sys-business"), CATS) as ParsedAi.Valid).result.categoryId)
        assertNull((AiResultParser.parse(json(category = null), CATS) as ParsedAi.Valid).result.categoryId)
    }
}

class LocalClassifierTest {
    private fun run(text: String, url: String? = null) =
        LocalClassifier.classify(AiInput("t", text, url, null, null, CATS.values.toList()), null, null)

    @Test fun bengaliAndEnglishKeywordsMap() {
        assertEquals("sys-business", run("নতুন ব্যবসা শুরু করার মার্কেটিং আইডিয়া এবং বিক্রি বাড়ানোর উপায়").categoryId)
        assertEquals("sys-programming", run("Python SQL tutorial for data analysis with excel").categoryId)
        assertEquals("sys-education", run("MBA class lecture notes chapter 3 exam").categoryId)
    }

    @Test fun localConfidenceIsCappedBelowHigh() {
        val r = run("python sql database code github api kotlin java developer programming")
        assertTrue(r.confidence < 0.85 && r.confidence >= 0.6)
    }

    @Test fun unclearTextIsLowAndNotGuessed() {
        val r = run("hello there")
        assertNull(r.categoryId); assertTrue(r.needsReview); assertTrue(r.confidence < 0.6)
    }

    @Test fun wordBoundariesAvoidFalseHits() = assertNull(run("she said the rain stopped").categoryId) // "ai" inside "said"/"rain"

    @Test fun healthIsNeverInferred() = assertTrue(run("doctor medicine health blood pressure").categoryId != StarterTaxonomy.HEALTH)

    @Test fun summaryIsExtractiveAndSkipsUrls() {
        val s = LocalClassifier.extractiveSummary("প্রথম বাক্যটি বেশ লম্বা এবং দরকারি। দ্বিতীয় বাক্য। https://x.com/a")!!
        assertTrue(s.startsWith("প্রথম বাক্যটি")); assertFalse(s.contains("https"))
    }
}

@RunWith(AndroidJUnit4::class)
class GeminiProviderTest {
    private fun provider(reply: HttpReply, sink: MutableList<Triple<String, Map<String, String>, String>> = mutableListOf()) =
        GeminiProvider("KEY123", "m", { url, headers, body -> sink += Triple(url, headers, body); reply })

    private val input = AiInput("Title", "Body text", "https://a.com", "web", "article", listOf("Programming & Data", "Ideas & Inspiration"))

    @Test fun keyGoesInHeaderNotUrlAndPromptListsAllowedCategories() = runBlocking {
        val sink = mutableListOf<Triple<String, Map<String, String>, String>>()
        provider(HttpReply(200, """{"candidates":[{"content":{"parts":[{"text":"{}"}]}}]}"""), sink).classify(input)
        val (url, headers, rawBody) = sink.single()
        val body = rawBody.replace("\\/", "/")
        assertFalse(url.contains("KEY123")); assertEquals("KEY123", headers["x-goog-api-key"])
        assertTrue(body.contains("Programming & Data | Ideas & Inspiration"))
        assertTrue(body.contains("application/json"))
    }

    @Test fun statusCodesMapToHonestOutcomes() = runBlocking {
        assertEquals(AiResponse.Raw("{\"a\":1}"), provider(HttpReply(200, """{"candidates":[{"content":{"parts":[{"text":"{\"a\":1}"}]}}]}""")).classify(input))
        assertEquals(AiResponse.RateLimited(7_000), provider(HttpReply(429, "slow down", 7)).classify(input))
        assertTrue(provider(HttpReply(429, "Quota exceeded for GenerateRequestsPerDay")).classify(input) is AiResponse.QuotaExceeded)
        assertTrue(provider(HttpReply(400, "API key not valid")).classify(input) is AiResponse.AuthFailed)
        assertTrue(provider(HttpReply(403, "")).classify(input) is AiResponse.AuthFailed)
        assertTrue(provider(HttpReply(503, "")).classify(input) is AiResponse.NetworkError)
        assertTrue(provider(HttpReply(200, "{}")).classify(input) is AiResponse.Failed)
    }

    @Test fun repairHintIsSent() = runBlocking {
        val sink = mutableListOf<Triple<String, Map<String, String>, String>>()
        provider(HttpReply(200, "{}"), sink).classify(input, "missing title")
        assertTrue(sink.single().third.contains("previous answer was rejected: missing title"))
    }
}
