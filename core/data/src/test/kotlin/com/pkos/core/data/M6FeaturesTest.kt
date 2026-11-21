package com.pkos.core.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pkos.core.data.db.PkosDatabase
import com.pkos.core.data.db.entity.AttachmentEntity
import com.pkos.core.data.db.entity.KnowledgeItemEntity
import com.pkos.core.data.db.entity.TagEntity
import com.pkos.core.data.db.entity.TopicEntity
import com.pkos.core.data.features.ActionExtractor
import com.pkos.core.data.features.ActionService
import com.pkos.core.data.features.CollectionRule
import com.pkos.core.data.features.CollectionService
import com.pkos.core.data.features.EdgeReason
import com.pkos.core.data.features.EmbeddingProvider
import com.pkos.core.data.features.ExportOptions
import com.pkos.core.data.features.ExportService
import com.pkos.core.data.features.GeminiEmbeddingProvider
import com.pkos.core.data.features.GeminiTextGenerator
import com.pkos.core.data.features.Granularity
import com.pkos.core.data.features.GraphService
import com.pkos.core.data.features.KnowledgeChat
import com.pkos.core.data.features.MatchReason
import com.pkos.core.data.features.NodeType
import com.pkos.core.data.features.Recommender
import com.pkos.core.data.features.ReminderService
import com.pkos.core.data.features.RuleCondition
import com.pkos.core.data.features.RuleField
import com.pkos.core.data.features.SearchFilters
import com.pkos.core.data.features.SearchService
import com.pkos.core.data.features.TextGenerator
import com.pkos.core.data.features.TimelineService
import com.pkos.core.data.features.TopicService
import com.pkos.core.data.model.ItemPriority
import com.pkos.core.data.model.ItemStatus
import com.pkos.core.data.model.PrivacyScope
import com.pkos.core.data.processing.HttpReply
import com.pkos.core.data.repository.CategoryRepository
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.time.ZoneOffset
import java.util.zip.ZipInputStream

private const val DAY = 86_400_000L

/** M6: search, chat, graph, topics, recommendations, timeline, actions, reminders, collections, export. */
@RunWith(AndroidJUnit4::class)
class M6FeaturesTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var db: PkosDatabase
    private var now = 1_800_000_000_000L
    private val clock = { now }
    private var n = 0

    @Before fun setUp() = runBlocking {
        db = PkosDatabase.inMemory(ApplicationProvider.getApplicationContext())
        CategoryRepository(db).ensureStarterTaxonomy()
    }

    @After fun tearDown() = db.close()

    private suspend fun item(title: String, text: String? = null, tags: List<String> = emptyList(), topic: String? = null, status: ItemStatus = ItemStatus.INBOX,
                             privacy: PrivacyScope = PrivacyScope.NORMAL, created: Long = now, pinned: Boolean = false, priority: ItemPriority = ItemPriority.NORMAL,
                             category: String? = "sys-programming", url: String? = null): String {
        val id = "i${n++}"
        db.knowledgeDao().insert(KnowledgeItemEntity(id = id, title = title, rawText = text, categoryId = category, topicId = topic, status = status,
            privacyScope = privacy, createdAt = created, updatedAt = created, pinned = pinned, priority = priority, sourceUrl = url))
        tags.forEach { t -> db.tagDao().insertIgnore(TagEntity("t-$t", t, t)); db.tagDao().linkByName(id, t) }
        return id
    }

    private suspend fun topic(id: String, name: String, user: Boolean = false, cat: String = "sys-programming") =
        db.topicDao().insert(TopicEntity(id, cat, null, name, isUserCreated = user, createdAt = now)).let { id }

    private fun search() = SearchService(db, clock)

    // 1 — search
    @Test fun keywordSearchWorksOfflineWithFiltersAndReasons() = runBlocking {
        val a = item("Kotlin coroutines guide", "structured concurrency with flows", tags = listOf("android"))
        val b = item("রান্নার রেসিপি", "ভাত ও ডাল", category = "sys-personal")
        item("Deleted kotlin", "kotlin").also { db.knowledgeDao().softDelete(it, now) }
        val s = search(); s.refreshIndex()
        val hits = s.search("kotlin", semantic = false)
        assertEquals(listOf(a), hits.map { it.item.id })
        assertTrue(MatchReason.KEYWORD in hits[0].reasons)
        assertEquals(listOf(b), s.search("রেসিপি", semantic = false).map { it.item.id })
        assertTrue(s.search("kotlin", SearchFilters(categoryId = "sys-personal"), semantic = false).isEmpty())
        assertEquals(listOf(a), s.search("andr", semantic = false).map { it.item.id }) // prefix + tags indexed
        // empty query + filter lists the filtered items
        assertEquals(listOf(b), s.search("", SearchFilters(categoryId = "sys-personal")).map { it.item.id })
    }

    @Test fun relatedWordsAndSpellingWithoutNetwork() = runBlocking {
        val a = item("Money saving plan", "monthly budget")
        val c = item("Kubernetes cheatsheet", "pods")
        val s = search(); s.refreshIndex()
        val bn = s.search("টাকা", semantic = true)
        assertEquals(a, bn.firstOrNull()?.item?.id); assertTrue(MatchReason.RELATED_WORD in bn[0].reasons)
        val typo = s.search("kubernetis", semantic = true)
        assertEquals(c, typo.firstOrNull()?.item?.id); assertTrue(MatchReason.SIMILAR_SPELLING in typo[0].reasons)
        assertTrue(s.search("টাকা", semantic = false).isEmpty()) // semantic off → keyword only
    }

    @Test fun embeddingsNeverIndexLocalOnlyAndMatchByMeaning() = runBlocking {
        val a = item("Car maintenance", "engine oil change interval")
        val local = item("Private diary", "engine thoughts", privacy = PrivacyScope.LOCAL_ONLY)
        val seen = mutableListOf<String>()
        val emb = object : EmbeddingProvider {
            override val model = "fake"
            override suspend fun embed(text: String): FloatArray { seen += text; return if (text.contains("vehicle") || text.contains("Car")) floatArrayOf(1f, 0f) else floatArrayOf(0f, 1f) }
        }
        val s = search(); s.refreshIndex()
        assertEquals(1, s.indexEmbeddings(emb))
        assertTrue(seen.none { it.contains("diary") })
        val hits = s.search("vehicle upkeep", semantic = true, embedder = emb)
        assertEquals(a, hits.first().item.id); assertTrue(MatchReason.MEANING in hits.first().reasons)
        assertTrue(hits.none { it.item.id == local && MatchReason.MEANING in it.reasons })
        assertEquals(0, s.indexEmbeddings(emb)) // unchanged text is not re-embedded
    }

    // 2 — chat
    @Test fun chatCitesOnlyRealSourcesAndWithholdsLocalOnly() = runBlocking {
        item("Kotlin flows", "Flows are cold streams. They emit values lazily.")
        item("Kotlin secret note", "kotlin private thing", privacy = PrivacyScope.LOCAL_ONLY)
        val s = search(); s.refreshIndex()
        var prompt = ""
        val ok = KnowledgeChat(s).ask("kotlin flows", TextGenerator { prompt = it; "Flows are cold streams [1]." })
        assertEquals("Flows are cold streams [1].", ok.text); assertTrue(ok.byCloud)
        assertEquals(1, ok.sources.size); assertEquals(1, ok.localOnlyWithheld)
        assertFalse(prompt.contains("private thing"))
        val bad = KnowledgeChat(s).ask("kotlin flows", TextGenerator { "Made up [7]." })
        assertNull(bad.text); assertTrue(bad.ungrounded)
        val none = KnowledgeChat(s).ask("kotlin flows", TextGenerator { "NOT_IN_LIBRARY" })
        assertTrue(none.noSources)
        val offline = KnowledgeChat(s).ask("kotlin flows", null)
        assertTrue(offline.text!!.contains("[1]")); assertFalse(offline.byCloud)
        assertTrue(KnowledgeChat(s).ask("zebra quantum", null).noSources)
    }

    // 3 — graph + 5 — recommendations
    @Test fun graphEdgesUserLinksAndDismissibleRecommendations() = runBlocking {
        topic("tp-k", "Kotlin")
        val a = item("Coroutines basics", "launch async", tags = listOf("kotlin", "async"), topic = "tp-k")
        val b = item("Flow operators", "map filter", tags = listOf("kotlin", "async"), topic = "tp-k")
        val c = item("Gardening", "tomatoes", category = "sys-personal")
        val g = GraphService(db, clock)
        val edges = g.neighbors(a)
        assertTrue(edges.any { it.type == NodeType.TOPIC && it.id == "tp-k" })
        assertTrue(edges.any { it.type == NodeType.ITEM && it.id == b })
        assertTrue(edges.none { it.id == c })
        assertTrue(g.link(a, c, note = "both weekend"))
        assertFalse(g.link(c, a)) // same pair once
        assertTrue(g.neighbors(a).any { it.id == c && it.reason == EdgeReason.USER_LINK })
        val rec = Recommender(db, g, clock)
        assertTrue(rec.relatedTo(a).any { it.id == b })
        rec.dismissRelated(a, b)
        assertTrue(rec.relatedTo(a).none { it.id == b })
        val learn = item("Learn Compose", status = ItemStatus.TO_LEARN, created = now - 5 * DAY)
        assertEquals(listOf(learn), rec.nextUseful().map { it.id })
        rec.dismissHome(learn); assertTrue(rec.nextUseful().isEmpty())
    }

    // 4 — topics
    @Test fun topicDuplicateProtectionCapAndMerge() = runBlocking {
        topic("tp1", "Machine Learning")
        val t = TopicService(db, clock)
        assertEquals("tp1", t.findSimilar("sys-programming", null, "machine-learning")?.id)
        assertEquals("tp1", t.findSimilar("sys-programming", null, "Machine Learnings")?.id)
        assertNull(t.findSimilar("sys-programming", null, "Gardening"))
        topic("tp2", "Machine Learn")
        val a = item("x", topic = "tp2")
        val sug = t.mergeSuggestions()
        assertEquals(1, sug.size)
        t.merge(sug[0].from.id, sug[0].into.id)
        assertTrue(db.knowledgeDao().get(a)!!.topicId in setOf("tp1", "tp2"))
        assertEquals(1, db.featureDao().topics().count { it.name.startsWith("Machine") })
        topic("tp3", "Deep Learning A"); topic("tp4", "Deep Learning B")
        t.mergeSuggestions().forEach { t.dismiss(it.from.id, it.into.id) }
        assertTrue(t.mergeSuggestions().isEmpty())
        repeat(30) { topic("auto$it", "Auto topic number $it") }
        assertFalse(t.canCreateAuto("sys-programming"))
        assertTrue(t.canCreateAuto("sys-personal"))
    }

    // 6 — timeline
    @Test fun timelineBucketsCapturesByDayWeekMonth() = runBlocking {
        item("today"); item("yesterday", created = now - DAY); item("old", created = now - 40 * DAY)
        val tl = TimelineService(db, ZoneOffset.UTC)
        val days = tl.build(Granularity.DAY, periods = 3, now = now)
        assertEquals(2, days.size) // only periods with activity
        assertEquals(2, days.sumOf { it.captured })
        assertEquals(3, tl.build(Granularity.MONTH, periods = 3, now = now).sumOf { it.captured })
        assertTrue(tl.build(Granularity.WEEK, periods = 2, now = now).sumOf { it.captured } >= 2)
    }

    // 7 — actions
    @Test fun actionsAreProposedWithoutDatesAndNeedConfirmation() = runBlocking {
        val found = ActionExtractor.extract("- [ ] Buy milk tomorrow\nTODO: call the bank\nআগামীকাল রিপোর্ট জমা দিতে হবে\nIdea: a reel counter widget\nJust a sentence.")
        assertTrue(found.any { it.text.contains("Buy milk") && it.kind == "TASK" })
        assertTrue(found.any { it.text.contains("call the bank", ignoreCase = true) })
        assertTrue(found.any { it.text.contains("রিপোর্ট") })
        assertTrue(found.any { it.kind == "IDEA" })
        val id = item("Notes", "TODO: renew passport")
        val svc = ActionService(db, clock)
        assertEquals(1, svc.propose(id))
        assertEquals(0, svc.propose(id)) // only once
        val a = db.featureDao().observeActionsFor(id).first().single()
        assertEquals(ActionService.PROPOSED, a.status); assertNull(a.dueAt)
        svc.confirm(a.id, dueAt = now + 3 * DAY)
        val c = db.featureDao().action(a.id)!!
        assertEquals(ActionService.CONFIRMED, c.status); assertEquals(now + 3 * DAY, c.dueAt)
    }

    // 8 — reminders
    @Test fun remindersPickStaleImportantItemsAndSnooze() = runBlocking {
        val p = item("Pinned old", pinned = true, created = now - 60 * DAY)
        item("Fresh pinned", pinned = true, created = now - DAY)
        item("Ordinary old", created = now - 60 * DAY)
        val r = ReminderService(db, clock)
        assertEquals(listOf(p), r.candidates(7).map { it.id })
        r.markShown(p)
        assertTrue(r.candidates(7).isEmpty())
        now += 8 * DAY
        assertEquals(listOf(p), r.candidates(7).map { it.id })
    }

    // 9 — collections
    @Test fun manualAndSmartCollections() = runBlocking {
        val a = item("A", tags = listOf("kotlin"), status = ItemStatus.TO_LEARN)
        val b = item("B", tags = listOf("kotlin"))
        val c = item("C", category = "sys-personal")
        val svc = CollectionService(db, clock)
        val manual = svc.createManual("Reading list")
        svc.add(manual, c)
        assertEquals(listOf(c), svc.items(manual).map { it.id })
        val rule = CollectionRule(true, listOf(RuleCondition(RuleField.TAG, "kotlin"), RuleCondition(RuleField.STATUS, "TO_LEARN", negate = true)))
        assertEquals(rule, CollectionRule.fromJson(rule.toJson()))
        val smart = svc.createSmart("Kotlin not learning", rule)
        assertEquals(listOf(b), svc.items(smart).map { it.id })
        item("D", tags = listOf("kotlin")).also { d -> assertTrue(svc.items(smart).any { it.id == d }) } // live
        svc.delete(manual)
        assertNotNull(db.knowledgeDao().get(c)) // deleting a collection never deletes items
        assertNotNull(a)
    }

    // 10 — export & integrity
    @Test fun exportBundleHasAllFormatsChecksumsAndRespectsPrivacy() = runBlocking {
        val root = tmp.newFolder()
        val bytes = ByteArray(200) { it.toByte() }
        File(root, "f1.bin").writeBytes(bytes)
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val a = item("Shared, \"quoted\"", "=cmd()", tags = listOf("kotlin"))
        db.attachmentDao().insert(AttachmentEntity("att1", a, "application/octet-stream", "f1.bin", 200, sha, "f1.bin", createdAt = now))
        item("Secret", "local only text", privacy = PrivacyScope.LOCAL_ONLY)
        val out = ByteArrayOutputStream()
        val res = ExportService(db, root, "test", clock).export(out)
        assertEquals(1, res.items); assertEquals(1, res.attachments); assertEquals(1, res.skippedLocalOnly)
        val files = linkedMapOf<String, ByteArray>()
        ZipInputStream(out.toByteArray().inputStream()).use { z -> generateSequence { z.nextEntry }.forEach { files[it.name] = z.readBytes() } }
        assertTrue(files.keys.containsAll(listOf("pkos-export.json", "items.csv", "attachments/f1.bin", "integrity-report.json")))
        assertTrue(files.keys.any { it.startsWith("markdown/") })
        val all = files.values.joinToString("") { String(it) }
        assertFalse(all.contains("local only text"))
        val csv = String(files["items.csv"]!!)
        assertTrue(csv.contains("\"Shared, \"\"quoted\"\"\"")); assertTrue(csv.contains("\"'=cmd()\"") || !csv.contains("=cmd()\""))
        val report = JSONObject(String(files["integrity-report.json"]!!))
        assertFalse(report.getBoolean("secrets_included"))
        assertEquals(sha, report.getJSONObject("sha256").getString("attachments/f1.bin"))
        val withLocal = ByteArrayOutputStream()
        assertEquals(2, ExportService(db, root, "test", clock).export(withLocal, ExportOptions(includeLocalOnly = true)).items)

        val ok = ExportService(db, root, "test", clock).integrity(null)
        assertTrue(ok.healthy)
        File(root, "f1.bin").writeBytes(ByteArray(3))
        val bad = ExportService(db, root, "test", clock).integrity(null)
        assertEquals(1, bad.corruptFiles); assertFalse(bad.healthy)
    }

    @Test fun geminiHelpersFailSoftly() = runBlocking {
        val gen = GeminiTextGenerator("k", "m") { _, h, _ -> assertEquals("k", h["x-goog-api-key"]); HttpReply(200, """{"candidates":[{"content":{"parts":[{"text":"hi [1]"}]}}]}""") }
        assertEquals("hi [1]", gen.generate("p"))
        assertNull(GeminiTextGenerator("k", "m") { _, _, _ -> HttpReply(429, "") }.generate("p"))
        val emb = GeminiEmbeddingProvider("k", http = { url, _, _ -> assertTrue(url.endsWith(":embedContent")); HttpReply(200, """{"embedding":{"values":[0.1,0.2]}}""") })
        assertEquals(2, emb.embed("x")!!.size)
        assertNull(GeminiEmbeddingProvider("k", http = { _, _, _ -> throw java.io.IOException() }).embed("x"))
    }
}
