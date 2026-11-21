package com.pkos.core.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pkos.core.data.capture.AttachmentStore
import com.pkos.core.data.capture.CaptureRepository
import com.pkos.core.data.capture.CaptureRequest
import com.pkos.core.data.capture.CaptureResult
import com.pkos.core.data.db.PkosDatabase
import com.pkos.core.data.features.EmbeddingProvider
import com.pkos.core.data.features.KnowledgeChat
import com.pkos.core.data.features.SearchService
import com.pkos.core.data.features.TextGenerator
import com.pkos.core.data.model.CaptureMethod
import com.pkos.core.data.model.PrivacyScope
import com.pkos.core.data.processing.AiInput
import com.pkos.core.data.processing.AiProvider
import com.pkos.core.data.processing.AiResponse
import com.pkos.core.data.processing.DefaultTextExtractor
import com.pkos.core.data.processing.ProcessingEngine
import com.pkos.core.data.repository.CategoryRepository
import com.pkos.core.data.repository.KnowledgeRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** M7 release blocker: a local-only item never reaches cloud AI, cloud embeddings or a cloud chat prompt. */
@RunWith(AndroidJUnit4::class)
class M7SecurityTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var db: PkosDatabase
    private var now = 1_800_000_000_000L
    private val sent = mutableListOf<String>()

    private val spyCloud = object : AiProvider {
        override val name = "gemini"; override val model = "spy"
        override suspend fun classify(input: AiInput, repairHint: String?): AiResponse { sent += input.title + " " + input.text; return AiResponse.Failed("spy") }
    }
    private val spyEmbed = object : EmbeddingProvider {
        override val model = "spy"
        override suspend fun embed(text: String): FloatArray { sent += text; return floatArrayOf(1f, 0f) }
    }

    @Before fun setUp() = runBlocking { db = PkosDatabase.inMemory(ApplicationProvider.getApplicationContext()); CategoryRepository(db).ensureStarterTaxonomy() }
    @After fun tearDown() = db.close()

    @Test fun localOnlyNeverLeavesThePhone() = runBlocking {
        val store = AttachmentStore(tmp.newFolder())
        val capture = CaptureRepository(db, store, { Long.MAX_VALUE }, clock = { now++ })
        val engine = ProcessingEngine(db, store::resolve, DefaultTextExtractor(), null, clock = { now++ })
        val secret = (capture.capture(CaptureRequest(CaptureMethod.SHARE, text = "SECRET diary kotlin entry"), PrivacyScope.LOCAL_ONLY) as CaptureResult.Saved).itemId
        val normal = (capture.capture(CaptureRequest(CaptureMethod.SHARE, text = "public kotlin article")) as CaptureResult.Saved).itemId
        engine.process(secret, spyCloud); engine.process(normal, spyCloud)
        val search = SearchService(db, { now }); search.refreshIndex(); search.indexEmbeddings(spyEmbed)
        var prompt = ""
        KnowledgeChat(search).ask("kotlin", TextGenerator { prompt = it; "x [1]" }, spyEmbed)
        assertFalse(sent.any { it.contains("SECRET") }); assertFalse(prompt.contains("SECRET"))
        assertTrue(sent.any { it.contains("public") }) // the spy really was called for normal items

        // Switching a normal item to local-only afterwards drops its cloud embedding and stops further sending.
        assertEquals(1, db.featureDao().embeddings("spy").count { it.knowledgeId == normal })
        KnowledgeRepository(db, clock = { now++ }).setLocalOnly(normal, true)
        assertEquals(0, db.featureDao().embeddings("spy").count { it.knowledgeId == normal })
        sent.clear(); prompt = ""
        engine.process(normal, spyCloud); search.indexEmbeddings(spyEmbed)
        KnowledgeChat(search).ask("public kotlin", TextGenerator { prompt = it; "x [1]" }, spyEmbed)
        assertFalse(sent.any { it.contains("public kotlin article") }); assertFalse(prompt.contains("public kotlin article"))
    }
}
