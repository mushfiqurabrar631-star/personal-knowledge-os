package com.pkos.core.data.features

import com.pkos.core.data.capture.TextSimilarity
import com.pkos.core.data.db.PkosDatabase
import com.pkos.core.data.db.entity.DismissalEntity
import com.pkos.core.data.db.entity.KnowledgeItemEntity
import com.pkos.core.data.db.entity.KnowledgeLinkEntity
import com.pkos.core.data.db.entity.TopicEntity
import com.pkos.core.data.model.FieldOrigin
import com.pkos.core.data.model.ItemStatus
import org.json.JSONObject
import java.util.UUID

enum class NodeType { ITEM, CATEGORY, TOPIC, TAG, CONCEPT }

/** One relationship with its reason and confidence (spec 9 #3). */
data class Edge(
    val type: NodeType,
    val id: String,
    val label: String,
    val reason: String,
    val confidence: Double,
    val origin: FieldOrigin,
    val linkId: String? = null,
)

/** Reasons are stable codes; the app shows them in the owner's language. */
object EdgeReason {
    const val CATEGORY = "category"; const val TOPIC = "topic"; const val SUBTOPIC = "subtopic"; const val TAG = "tag"
    const val SHARED_TAGS = "shared_tags"; const val SAME_TOPIC = "same_topic"; const val SIMILAR_TEXT = "similar_text"
    const val DUPLICATE = "duplicate"; const val USER_LINK = "user_link"; const val AI_RELATED = "ai_related"
    const val IN_PROJECT = "in_project"; const val CONCEPT_IN = "concept_in"; const val CO_OCCURS = "co_occurs"; const val CROSS_LINK = "cross_link"
}

/** A hub on the library map: a project (main category), a topic, or a concept (tag/keyword used by several items). */
data class MapNode(val type: NodeType, val id: String, val label: String, val items: Int, val sampleItemIds: List<String> = emptyList())

/** Hub-to-hub relation; [weight] = number of items behind it. Ids are "<type>:<id>" of [MapNode]s. */
data class MapEdge(val from: String, val to: String, val reason: String, val weight: Int)

data class KnowledgeMap(val nodes: List<MapNode>, val edges: List<MapEdge>) {
    fun key(n: MapNode) = "${n.type.name}:${n.id}"
    fun node(key: String) = nodes.firstOrNull { key(it) == key }
}

/**
 * Knowledge graph (spec 9 #3): computed on demand from items, topics, tags and owner links — nothing is stored except
 * the owner's own links, which AI never touches.
 */
class GraphService(private val db: PkosDatabase, private val clock: () -> Long = System::currentTimeMillis) {
    private val dao get() = db.featureDao()

    suspend fun neighbors(itemId: String, maxItems: Int = 12): List<Edge> {
        val item = db.knowledgeDao().get(itemId)?.takeIf { it.deletedAt == null } ?: return emptyList()
        val live = dao.liveItems().filter { it.id != itemId }.associateBy { it.id }
        val tagMap = dao.itemTags().groupBy({ it.itemId }, { it.name.lowercase() })
        val topics = dao.topics().associateBy { it.id }
        val origins = runCatching { JSONObject(item.fieldOrigins) }.getOrDefault(JSONObject())
        fun origin(f: String) = if (origins.optString(f) == "USER") FieldOrigin.USER else FieldOrigin.AI
        val aiConf = item.confidence ?: 0.7
        val out = mutableListOf<Edge>()
        item.categoryId?.let { c -> db.categoryDao().get(c)?.let { out += Edge(NodeType.CATEGORY, c, it.name, EdgeReason.CATEGORY, if (origin("category") == FieldOrigin.USER) 1.0 else aiConf, origin("category")) } }
        item.topicId?.let { t -> topics[t]?.let { out += Edge(NodeType.TOPIC, t, it.name, EdgeReason.TOPIC, if (origin("topic") == FieldOrigin.USER) 1.0 else aiConf, origin("topic")) } }
        item.subtopicId?.let { t -> topics[t]?.let { out += Edge(NodeType.TOPIC, t, it.name, EdgeReason.SUBTOPIC, if (origin("topic") == FieldOrigin.USER) 1.0 else aiConf, origin("topic")) } }
        val myTags = tagMap[itemId].orEmpty().toSet()
        myTags.forEach { out += Edge(NodeType.TAG, it, it, EdgeReason.TAG, 1.0, origin("tags")) }

        // Owner links first (always kept, confidence 1).
        val links = dao.linksFor(itemId)
        val linked = mutableSetOf<String>()
        for (l in links) {
            val other = if (l.fromId == itemId) l.toId else l.fromId
            live[other]?.let {
                out += if (l.origin == FieldOrigin.USER) Edge(NodeType.ITEM, it.id, it.title, EdgeReason.USER_LINK, 1.0, FieldOrigin.USER, l.id)
                else Edge(NodeType.ITEM, it.id, it.title, EdgeReason.AI_RELATED, 0.7, FieldOrigin.AI, null) // AI suggestion, dismissible
                linked += it.id
            }
        }
        // Computed item relations.
        val myShingles = TextSimilarity.shingles(text(item))
        val related = mutableListOf<Edge>()
        for (o in live.values) {
            if (o.id in linked) continue
            val best = mutableListOf<Pair<String, Double>>()
            if (item.duplicateOfId == o.id || o.duplicateOfId == item.id) best += EdgeReason.DUPLICATE to 0.95
            val shared = myTags.intersect(tagMap[o.id].orEmpty().toSet())
            if (shared.isNotEmpty()) best += EdgeReason.SHARED_TAGS to (shared.size.toDouble() / (myTags.size + tagMap[o.id].orEmpty().size - shared.size)).coerceIn(0.3, 0.9)
            if (item.topicId != null && item.topicId == o.topicId) best += EdgeReason.SAME_TOPIC to 0.8
            val sim = TextSimilarity.jaccard(myShingles, TextSimilarity.shingles(text(o)))
            if (sim >= 0.25) best += EdgeReason.SIMILAR_TEXT to sim
            best.maxByOrNull { it.second }?.let { (r, c) -> related += Edge(NodeType.ITEM, o.id, o.title, r, c, FieldOrigin.AI) }
        }
        out += related.sortedByDescending { it.confidence }.take(maxItems)
        return out
    }

    /**
     * Library-wide map (spec 9 #3): projects (main categories), their topics, and concepts — tags and keywords shared by
     * at least [minItems] items — with how they connect across projects. Computed on the phone, never stored, never sent.
     */
    suspend fun overview(maxConcepts: Int = 40, minItems: Int = 2): KnowledgeMap {
        val items = dao.liveItems().filter { it.duplicateOfId == null }
        if (items.isEmpty()) return KnowledgeMap(emptyList(), emptyList())
        val byRecent = items.sortedByDescending { it.updatedAt }
        val categories = db.categoryDao().let { d -> items.mapNotNull { it.categoryId }.distinct().mapNotNull { d.get(it) } }
            .filter { it.id != UNSORTED }.associateBy { it.id }
        val topics = dao.topics().associateBy { it.id }
        val tagMap = dao.itemTags().groupBy({ it.itemId }, { it.name.trim().lowercase() })
        val conceptsOf = items.associate { i ->
            i.id to (tagMap[i.id].orEmpty() + SearchService.jsonWords(i.keywords).map { it.trim().lowercase() }).filter { it.length >= 2 }.toSet()
        }
        val nodes = mutableListOf<MapNode>()
        val edges = mutableListOf<MapEdge>()
        fun sample(ids: Collection<String>) = byRecent.asSequence().map { it.id }.filter { it in ids }.take(5).toList()

        val inCat = items.filter { it.categoryId in categories }.groupBy { it.categoryId!! }
        for ((cid, list) in inCat) nodes += MapNode(NodeType.CATEGORY, cid, categories.getValue(cid).name, list.size, sample(list.map { it.id }.toSet()))
        val inTopic = items.filter { it.topicId in topics }.groupBy { it.topicId!! }
        for ((tid, list) in inTopic) {
            nodes += MapNode(NodeType.TOPIC, tid, topics.getValue(tid).name, list.size, sample(list.map { it.id }.toSet()))
            list.groupingBy { it.categoryId }.eachCount().forEach { (cid, n) -> if (cid in categories) edges += MapEdge("TOPIC:$tid", "CATEGORY:$cid", EdgeReason.IN_PROJECT, n) }
        }
        val holders = mutableMapOf<String, MutableSet<String>>()
        conceptsOf.forEach { (id, cs) -> cs.forEach { holders.getOrPut(it) { mutableSetOf() } += id } }
        val chosen = holders.filter { it.value.size >= minItems }.entries.sortedWith(compareByDescending<Map.Entry<String, MutableSet<String>>> { it.value.size }.thenBy { it.key })
            .take(maxConcepts).associate { it.key to it.value }
        val itemById = items.associateBy { it.id }
        for ((c, ids) in chosen) {
            nodes += MapNode(NodeType.CONCEPT, c, c, ids.size, sample(ids))
            ids.mapNotNull { itemById[it]?.categoryId }.filter { it in categories }.groupingBy { it }.eachCount()
                .forEach { (cid, n) -> edges += MapEdge("CONCEPT:$c", "CATEGORY:$cid", EdgeReason.CONCEPT_IN, n) }
        }
        val names = chosen.keys.sorted()
        for ((i, a) in names.withIndex()) for (b in names.drop(i + 1)) {
            val n = chosen.getValue(a).count { it in chosen.getValue(b) }
            if (n >= minItems) edges += MapEdge("CONCEPT:$a", "CONCEPT:$b", EdgeReason.CO_OCCURS, n)
        }
        // Owner links and AI suggestions that join items in different projects.
        dao.allLinks().mapNotNull { l ->
            val a = itemById[l.fromId]?.categoryId?.takeIf { it in categories }; val b = itemById[l.toId]?.categoryId?.takeIf { it in categories }
            if (a == null || b == null || a == b) null else listOf(a, b).sorted().let { it[0] to it[1] }
        }.groupingBy { it }.eachCount().forEach { (p, n) -> edges += MapEdge("CATEGORY:${p.first}", "CATEGORY:${p.second}", EdgeReason.CROSS_LINK, n) }
        return KnowledgeMap(nodes.sortedWith(compareBy<MapNode> { it.type.ordinal }.thenByDescending { it.items }.thenBy { it.label }), edges.sortedByDescending { it.weight })
    }

    /** Owner confirms a relation; kept forever unless the owner removes it. */
    suspend fun link(fromId: String, toId: String, relation: String = "related", note: String? = null): Boolean {
        if (fromId == toId) return false
        val (a, b) = if (fromId < toId) fromId to toId else toId to fromId
        return dao.insertLink(KnowledgeLinkEntity(UUID.randomUUID().toString(), a, b, relation, note, FieldOrigin.USER, clock())) != -1L
    }

    suspend fun unlink(linkId: String) = dao.deleteUserLink(linkId)

    private fun text(i: KnowledgeItemEntity) = listOfNotNull(i.title, i.summary, i.rawText?.take(600)).joinToString(" ")

    private companion object { const val UNSORTED = "sys-unsorted" }
}

/**
 * Auto topic creation guard (spec 9 #4): reuse a near-identical topic name instead of creating a new one, cap automatic
 * topics per category (no taxonomy explosion) and suggest merges for near-duplicates. Owner-created topics are never
 * merged away without the owner's tap.
 */
class TopicService(private val db: PkosDatabase, private val clock: () -> Long = System::currentTimeMillis) {
    private val dao get() = db.featureDao()

    data class MergeSuggestion(val from: TopicEntity, val into: TopicEntity, val similarity: Double, val fromCount: Int)

    /** Existing topic that is the same or nearly the same name, or null. */
    suspend fun findSimilar(categoryId: String, parentId: String?, name: String): TopicEntity? {
        val key = normalize(name)
        val candidates = dao.topics().filter { it.categoryId == categoryId && it.parentTopicId == parentId }
        candidates.firstOrNull { normalize(it.name) == key }?.let { return it }
        return candidates.map { it to similarity(it.name, name) }.filter { it.second >= SIMILAR }.maxByOrNull { it.second }?.first
    }

    /** Whether one more automatic topic may be created in this category. */
    suspend fun canCreateAuto(categoryId: String): Boolean =
        dao.topics().count { it.categoryId == categoryId && !it.isUserCreated && it.parentTopicId == null } < MAX_AUTO_TOPICS_PER_CATEGORY

    suspend fun mergeSuggestions(): List<MergeSuggestion> {
        val topics = dao.topics()
        val counts = dao.liveItems().groupingBy { it.topicId }.eachCount()
        val dismissed = dao.dismissedKeys("merge:").toSet()
        val out = mutableListOf<MergeSuggestion>()
        for ((i, a) in topics.withIndex()) for (b in topics.drop(i + 1)) {
            if (a.categoryId != b.categoryId || a.parentTopicId != b.parentTopicId) continue
            val sim = similarity(a.name, b.name)
            if (sim < MERGE_SUGGEST) continue
            val (from, into) = when {
                a.isUserCreated && !b.isUserCreated -> b to a
                b.isUserCreated && !a.isUserCreated -> a to b
                (counts[a.id] ?: 0) >= (counts[b.id] ?: 0) -> b to a
                else -> a to b
            }
            if (key(from.id, into.id) in dismissed) continue
            out += MergeSuggestion(from, into, sim, counts[from.id] ?: 0)
        }
        return out.sortedByDescending { it.similarity }
    }

    /** Moves items and subtopics of [fromId] into [intoId] and removes [fromId]. Only on the owner's tap. */
    suspend fun merge(fromId: String, intoId: String) {
        val now = clock()
        dao.moveTopic(fromId, intoId, now)
        dao.reparentTopics(fromId, intoId)
        dao.deleteTopic(fromId)
    }

    suspend fun dismiss(fromId: String, intoId: String) = dao.dismiss(DismissalEntity(key(fromId, intoId), clock()))

    private fun key(a: String, b: String) = "merge:" + listOf(a, b).sorted().joinToString("|")

    companion object {
        const val SIMILAR = 0.82
        const val MERGE_SUGGEST = 0.6
        const val MAX_AUTO_TOPICS_PER_CATEGORY = 30

        fun normalize(name: String): String = name.lowercase().replace(Regex("[^\\p{L}\\p{N}\\p{M}]+"), " ").trim()
            .split(' ').joinToString(" ") { w -> if (w.length > 3 && w.endsWith("s") && !w.endsWith("ss")) w.dropLast(1) else w }

        fun similarity(a: String, b: String): Double {
            val na = normalize(a); val nb = normalize(b)
            if (na == nb) return 1.0
            return TextSimilarity.jaccard(TextSimilarity.shingles(na), TextSimilarity.shingles(nb))
        }
    }
}

/** Smart recommendations (spec 9 #5) — computed locally, each dismissible. */
class Recommender(private val db: PkosDatabase, private val graph: GraphService, private val clock: () -> Long = System::currentTimeMillis) {
    private val dao get() = db.featureDao()

    suspend fun relatedTo(itemId: String, limit: Int = 5): List<Edge> {
        val dismissed = dao.dismissedKeys("rec:$itemId:").map { it.substringAfterLast(':') }.toSet()
        return graph.neighbors(itemId, maxItems = 20).filter { it.type == NodeType.ITEM && it.id !in dismissed && it.reason != EdgeReason.DUPLICATE }.take(limit)
    }

    /** "Next useful item": to-learn / in-progress / pinned items in topics the owner is active in, not opened lately. */
    suspend fun nextUseful(limit: Int = 3): List<KnowledgeItemEntity> {
        val now = clock()
        val items = dao.liveItems()
        val activeTopics = items.filter { now - it.createdAt < 14 * DAY || (it.lastOpenedAt ?: 0) > now - 14 * DAY }.mapNotNull { it.topicId }.toSet()
        val snoozed = dao.dismissedKeys("rec:home:").toSet()
        return items.asSequence()
            .filter { it.status == ItemStatus.TO_LEARN || it.status == ItemStatus.IN_PROGRESS || it.pinned }
            .filter { "rec:home:${it.id}" !in snoozed }
            .filter { now - (it.lastOpenedAt ?: it.createdAt) > 2 * DAY }
            .sortedWith(compareByDescending<KnowledgeItemEntity> { it.topicId in activeTopics }.thenByDescending { it.status == ItemStatus.IN_PROGRESS }.thenBy { it.lastOpenedAt ?: it.createdAt })
            .take(limit).toList()
    }

    suspend fun dismissRelated(itemId: String, targetId: String) = dao.dismiss(DismissalEntity("rec:$itemId:$targetId", clock()))
    suspend fun dismissHome(itemId: String) = dao.dismiss(DismissalEntity("rec:home:$itemId", clock()))

    companion object { const val DAY = 86_400_000L }
}
