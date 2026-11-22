package com.pkos.core.data.features

import androidx.room.withTransaction

import com.pkos.core.data.db.PkosDatabase
import com.pkos.core.data.db.entity.EmbeddingEntity
import com.pkos.core.data.db.entity.KnowledgeFtsEntity
import com.pkos.core.data.db.entity.KnowledgeItemEntity
import com.pkos.core.data.model.ContentType
import com.pkos.core.data.model.ItemPriority
import com.pkos.core.data.model.ItemStatus
import com.pkos.core.data.model.PrivacyScope
import com.pkos.core.data.model.SourcePlatform
import com.pkos.core.data.model.SyncState
import com.pkos.core.data.backup.sha256Hex
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/** Spec 9.1 filters. Null = any. */
data class SearchFilters(
    val categoryId: String? = null,
    val topicId: String? = null,
    val type: ContentType? = null,
    val platform: SourcePlatform? = null,
    val from: Long? = null,
    val to: Long? = null,
    val priority: ItemPriority? = null,
    val status: ItemStatus? = null,
    val pinned: Boolean? = null,
    val notionSynced: Boolean? = null,
) {
    val isEmpty get() = this == SearchFilters()
    fun accepts(i: KnowledgeItemEntity): Boolean =
        (categoryId == null || i.categoryId == categoryId) && (topicId == null || i.topicId == topicId || i.subtopicId == topicId) &&
            (type == null || i.contentType == type) && (platform == null || i.sourcePlatform == platform) &&
            (from == null || i.createdAt >= from) && (to == null || i.createdAt <= to) && (priority == null || i.priority == priority) &&
            (status == null || i.status == status) && (pinned == null || i.pinned == pinned) &&
            (notionSynced == null || (i.syncState == SyncState.NOTION_SYNCED || i.syncState == SyncState.SYNCED) == notionSynced)
}

enum class MatchReason { KEYWORD, RELATED_WORD, SIMILAR_SPELLING, MEANING }

data class SearchHit(val item: KnowledgeItemEntity, val score: Double, val reasons: Set<MatchReason>)

/** Cloud text embeddings (owner opt-in, via cloud AI). Only ever called for non-local-only text. */
interface EmbeddingProvider {
    val model: String
    suspend fun embed(text: String): FloatArray?
}

/**
 * Search (spec 9 #1, 9.1): offline FTS4 keyword search first; "meaning" layer = bilingual related-word expansion +
 * fuzzy spelling (both offline) + optional cloud embeddings. Works fully offline; the semantic layer can be switched off.
 */
class SearchService(internal val db: PkosDatabase, private val clock: () -> Long = System::currentTimeMillis) {
    private val dao get() = db.featureDao()

    /** Brings the FTS index up to date (only items changed since they were indexed). */
    suspend fun refreshIndex(): Int = db.withTransaction {
        dao.pruneFts()
        val stale = dao.staleIndexIds()
        if (stale.isEmpty()) return@withTransaction 0
        val tags = dao.itemTags().groupBy({ it.itemId }, { it.name })
        val topics = dao.topics().associateBy { it.id }
        val cats = db.categoryDao().all().associate { it.id to it.name }
        for (id in stale) {
            val rowId = dao.itemRowId(id) ?: continue
            dao.deleteFtsRow(rowId)
            val i = db.knowledgeDao().get(id) ?: continue
            if (i.deletedAt != null) continue
            val ocr = db.attachmentDao().forItem(id).mapNotNull { it.extractedText }.joinToString(" ")
            dao.insertFts(
                KnowledgeFtsEntity(
                    rowId = rowId, itemId = id, indexedAt = maxOf(i.updatedAt, clock()), title = i.title,
                    content = listOfNotNull(i.rawText, i.summary, i.userNote, ocr).joinToString("\n").take(100_000),
                    tags = tags[id].orEmpty().joinToString(" "),
                    topics = listOfNotNull(i.categoryId?.let { cats[it] }, i.topicId?.let { topics[it]?.name }, i.subtopicId?.let { topics[it]?.name }).joinToString(" "),
                    url = i.sourceUrl.orEmpty(),
                ),
            )
        }
        stale.size
    }

    suspend fun search(
        query: String,
        filters: SearchFilters = SearchFilters(),
        semantic: Boolean = true,
        embedder: EmbeddingProvider? = null,
        limit: Int = 100,
    ): List<SearchHit> {
        refreshIndex()
        val live = dao.liveItems().associateBy { it.id }
        val q = query.trim()
        if (q.isEmpty()) {
            if (filters.isEmpty) return emptyList()
            return live.values.filter(filters::accepts).sortedByDescending { it.createdAt }.take(limit).map { SearchHit(it, 0.0, emptySet()) }
        }
        val scores = HashMap<String, Double>()
        val reasons = HashMap<String, MutableSet<MatchReason>>()
        fun add(id: String, s: Double, r: MatchReason) { scores[id] = (scores[id] ?: 0.0) + s; reasons.getOrPut(id) { mutableSetOf() } += r }

        val tokens = tokens(q)
        // 1. Keyword (FTS4, prefix match per token); an item matching more tokens scores higher.
        for (t in tokens) safeMatch("\"${t}*\"").forEach { add(it, 1.0, MatchReason.KEYWORD) }
        tokens.forEach { t -> live.values.filter { it.title.contains(t, ignoreCase = true) }.forEach { add(it.id, 0.5, MatchReason.KEYWORD) } }
        if (semantic) {
            // 2. Related words (bilingual concept groups): "টাকা" also finds "finance", "course" also finds "শেখা".
            Concepts.expand(tokens).forEach { w -> safeMatch("\"${w}*\"").forEach { add(it, 0.6, MatchReason.RELATED_WORD) } }
            // 3. Similar spelling (typos, inflections) over title + summary + tags.
            val tagMap = dao.itemTags().groupBy({ it.itemId }, { it.name })
            val long = tokens.filter { it.length >= 4 }.map { trigrams(it) }
            if (long.isNotEmpty()) for (i in live.values) {
                val words = tokens(i.title + " " + tagMap[i.id].orEmpty().joinToString(" "), max = 40).filter { it.length >= 4 }.map { trigrams(it) }
                val sim = long.maxOf { qt -> words.maxOfOrNull { w -> jaccard(qt, w) } ?: 0.0 }
                if (sim >= 0.4 && sim < 1.0) add(i.id, sim, MatchReason.SIMILAR_SPELLING)
            }
            // 4. Optional cloud embeddings: the query text is sent only because the owner turned on cloud AI.
            if (embedder != null) runCatching {
                val qv = embedder.embed(q)
                if (qv != null) for (e in dao.embeddings(embedder.model)) {
                    val cos = cosine(qv, decode(e.vector))
                    if (cos >= 0.62) add(e.knowledgeId, cos * 1.5, MatchReason.MEANING)
                }
            }
        }
        return scores.entries.mapNotNull { (id, s) -> live[id]?.takeIf(filters::accepts)?.let { SearchHit(it, s, reasons[id].orEmpty()) } }
            .sortedWith(compareByDescending<SearchHit> { it.score }.thenByDescending { it.item.updatedAt })
            .take(limit)
    }

    /** Embeds shareable items whose text changed. Local-only items are never sent. */
    suspend fun indexEmbeddings(embedder: EmbeddingProvider, max: Int = 25): Int {
        var n = 0
        for (i in dao.liveItems()) {
            if (n >= max) break
            if (i.privacyScope != PrivacyScope.NORMAL) continue
            val text = embedText(i)
            val hash = sha256Hex(text.toByteArray())
            if (dao.embeddingHash(i.id, embedder.model) == hash) continue
            val v = embedder.embed(text)
            CloudAudit.record(db, i.id, CloudAudit.EMBED, embedder.model, if (v == null) "ERROR" else "OK", text.length, clock())
            if (v == null) break // stop on failure (quota/network); try again later
            dao.upsertEmbedding(EmbeddingEntity(i.id, embedder.model, v.size, encode(v), hash, clock())); n++
        }
        return n
    }

    private fun embedText(i: KnowledgeItemEntity) = listOfNotNull(i.title, i.summary, i.rawText?.take(2_000)).joinToString("\n")

    private suspend fun safeMatch(match: String): List<String> = runCatching { dao.ftsMatch(match, 500) }.getOrDefault(emptyList())

    companion object {
        fun tokens(q: String, max: Int = 8): List<String> = q.lowercase().split(Regex("[^\\p{L}\\p{N}\\p{M}]+")).filter { it.length >= 2 }.distinct().take(max)
        fun trigrams(w: String): Set<String> = " $w ".windowed(3).toSet()
        fun jaccard(a: Set<String>, b: Set<String>): Double = if (a.isEmpty() || b.isEmpty()) 0.0 else a.intersect(b).size.toDouble() / a.union(b).size

        fun cosine(a: FloatArray, b: FloatArray): Double {
            if (a.size != b.size) return 0.0
            var dot = 0.0; var na = 0.0; var nb = 0.0
            for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
            return if (na == 0.0 || nb == 0.0) 0.0 else dot / (sqrt(na) * sqrt(nb))
        }

        fun encode(v: FloatArray): ByteArray = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { v.forEach { putFloat(it) } }.array()
        fun decode(b: ByteArray): FloatArray = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).let { bb -> FloatArray(b.size / 4) { bb.getFloat() } }
    }
}

/** Small bilingual (bn/en) related-word groups for offline meaning-aware search. */
object Concepts {
    private val groups: List<Set<String>> = listOf(
        setOf("টাকা", "অর্থ", "money", "finance", "বিনিয়োগ", "invest", "investment", "সঞ্চয়", "saving", "budget", "বাজেট"),
        setOf("শেখা", "শিখ", "learn", "learning", "course", "কোর্স", "tutorial", "টিউটোরিয়াল", "lesson", "ক্লাস", "class"),
        setOf("চাকরি", "job", "career", "ক্যারিয়ার", "cv", "resume", "সিভি", "interview", "ইন্টারভিউ"),
        setOf("প্রোগ্রামিং", "programming", "code", "কোড", "coding", "developer", "python", "kotlin", "sql", "ডেটা", "data"),
        setOf("মোবাইল", "ফোন", "mobile", "phone", "android", "অ্যান্ড্রয়েড", "app", "অ্যাপ"),
        setOf("ভিডিও", "video", "youtube", "ইউটিউব", "reel", "রিল"),
        setOf("ব্যবসা", "business", "sales", "বিক্রি", "marketing", "মার্কেটিং", "customer", "গ্রাহক", "startup"),
        setOf("রান্না", "recipe", "রেসিপি", "খাবার", "food", "cooking"),
        setOf("ভ্রমণ", "travel", "trip", "tour", "ট্যুর"),
        setOf("ইংরেজি", "english", "ielts", "grammar", "vocabulary", "শব্দ"),
        setOf("আইডিয়া", "idea", "ধারণা", "inspiration", "অনুপ্রেরণা"),
        setOf("এআই", "ai", "chatgpt", "gemini", "claude", "llm"),
        setOf("বই", "book", "reading", "পড়া", "pdf"),
        setOf("পরীক্ষা", "exam", "test", "quiz", "প্রশ্ন", "question"),
        setOf("ডিজাইন", "design", "ui", "logo", "লোগো"),
        setOf("ছবি", "photo", "image", "screenshot", "স্ক্রিনশট"),
        setOf("কেনা", "কিনতে", "buy", "price", "দাম", "shopping", "offer", "অফার"),
        setOf("স্বাস্থ্য", "health", "doctor", "ডাক্তার", "fitness", "ব্যায়াম", "exercise"),
        setOf("মিটিং", "meeting", "report", "রিপোর্ট", "project", "প্রজেক্ট", "task", "কাজ"),
    )

    fun expand(tokens: List<String>): Set<String> {
        val out = LinkedHashSet<String>()
        for (t in tokens) for (g in groups) if (g.any { it == t || (t.length >= 3 && it.startsWith(t)) || (it.length >= 3 && t.startsWith(it)) }) out += g
        return out - tokens.toSet()
    }
}
