package com.pkos.core.data.features

import com.pkos.core.data.db.PkosDatabase
import com.pkos.core.data.db.entity.CollectionEntity
import com.pkos.core.data.db.entity.CollectionItemEntity
import com.pkos.core.data.db.entity.DismissalEntity
import com.pkos.core.data.db.entity.KnowledgeItemEntity
import com.pkos.core.data.model.ItemPriority
import com.pkos.core.data.model.ItemStatus
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Rule fields for smart collections (spec 9 #9), e.g. Category = Programming AND Status = To Learn. */
enum class RuleField { CATEGORY, TOPIC, STATUS, PRIORITY, TYPE, PLATFORM, TAG, PINNED, CREATED_WITHIN_DAYS }

data class RuleCondition(val field: RuleField, val value: String, val negate: Boolean = false)

data class CollectionRule(val matchAll: Boolean = true, val conditions: List<RuleCondition>) {
    fun toJson(): String = JSONObject().put("match", if (matchAll) "all" else "any").put(
        "conditions",
        JSONArray(conditions.map { JSONObject().put("field", it.field.name).put("value", it.value).put("negate", it.negate) }),
    ).toString()

    fun matches(i: KnowledgeItemEntity, tags: Set<String>, now: Long): Boolean {
        if (conditions.isEmpty()) return false
        val results = conditions.map { c ->
            val hit = when (c.field) {
                RuleField.CATEGORY -> i.categoryId == c.value
                RuleField.TOPIC -> i.topicId == c.value || i.subtopicId == c.value
                RuleField.STATUS -> i.status.name == c.value
                RuleField.PRIORITY -> i.priority.name == c.value
                RuleField.TYPE -> i.contentType?.name == c.value
                RuleField.PLATFORM -> i.sourcePlatform?.name == c.value
                RuleField.TAG -> c.value.lowercase() in tags
                RuleField.PINNED -> i.pinned == c.value.toBoolean()
                RuleField.CREATED_WITHIN_DAYS -> c.value.toLongOrNull()?.let { now - i.createdAt <= it * 86_400_000L } == true
            }
            hit != c.negate
        }
        return if (matchAll) results.all { it } else results.any { it }
    }

    companion object {
        fun fromJson(s: String?): CollectionRule? = s?.let {
            runCatching {
                val o = JSONObject(it); val a = o.getJSONArray("conditions")
                CollectionRule(
                    o.optString("match", "all") == "all",
                    (0 until a.length()).map { i -> a.getJSONObject(i).let { c -> RuleCondition(RuleField.valueOf(c.getString("field")), c.getString("value"), c.optBoolean("negate")) } },
                )
            }.getOrNull()
        }
    }
}

/** Manual and rule-based collections. Smart collections are evaluated live, so they never go stale. */
class CollectionService(private val db: PkosDatabase, private val clock: () -> Long = System::currentTimeMillis) {
    private val dao get() = db.featureDao()

    fun observe() = dao.observeCollections()

    suspend fun createManual(name: String): String = create(name, null)
    suspend fun createSmart(name: String, rule: CollectionRule): String = create(name, rule)

    private suspend fun create(name: String, rule: CollectionRule?): String {
        val id = UUID.randomUUID().toString()
        dao.insertCollection(CollectionEntity(id, name.trim().take(80), ruleJson = rule?.toJson(), isSmart = rule != null, createdAt = clock()))
        return id
    }

    suspend fun update(id: String, name: String, rule: CollectionRule?) {
        val c = dao.collection(id) ?: return
        dao.updateCollection(c.copy(name = name.trim().take(80), ruleJson = if (c.isSmart) rule?.toJson() ?: c.ruleJson else null))
    }

    suspend fun delete(id: String) = dao.deleteCollection(id) // items themselves are never touched

    suspend fun add(collectionId: String, itemId: String) = dao.addToCollection(CollectionItemEntity(collectionId, itemId))
    suspend fun remove(collectionId: String, itemId: String) = dao.removeFromCollection(collectionId, itemId)

    suspend fun items(collectionId: String): List<KnowledgeItemEntity> {
        val c = dao.collection(collectionId) ?: return emptyList()
        if (!c.isSmart) return dao.manualItems(collectionId)
        val rule = CollectionRule.fromJson(c.ruleJson) ?: return emptyList()
        val tags = dao.itemTags().groupBy({ it.itemId }, { it.name.lowercase() })
        val now = clock()
        return dao.liveItems().filter { rule.matches(it, tags[it.id].orEmpty().toSet(), now) }.sortedByDescending { it.createdAt }
    }
}

/**
 * Forgotten-knowledge reminder (spec 9 #8): important items (pinned, high priority, to-learn/to-review or with open
 * tasks) not opened for [staleDays] days. Surfacing an item snoozes it for the owner's chosen interval.
 */
class ReminderService(private val db: PkosDatabase, private val clock: () -> Long = System::currentTimeMillis) {
    private val dao get() = db.featureDao()

    suspend fun candidates(intervalDays: Int, limit: Int = 3, staleDays: Int = 30): List<KnowledgeItemEntity> {
        val now = clock()
        val day = 86_400_000L
        val shown = dao.dismissedKeys("remind:").associateWith { dao.dismissedAt(it) ?: 0L }
        return dao.liveItems().asSequence()
            .filter { it.pinned || it.priority == ItemPriority.HIGH || it.priority == ItemPriority.CRITICAL || it.status == ItemStatus.TO_LEARN || it.status == ItemStatus.TO_REVIEW }
            .filter { it.status != ItemStatus.ARCHIVED && it.status != ItemStatus.DONE }
            .filter { now - (it.lastOpenedAt ?: it.createdAt) >= staleDays * day }
            .filter { (shown["remind:${it.id}"] ?: 0L) < now - intervalDays * day }
            .sortedWith(compareByDescending<KnowledgeItemEntity> { it.pinned }.thenBy { it.lastOpenedAt ?: it.createdAt })
            .take(limit).toList()
    }

    suspend fun markShown(itemId: String) = dao.dismiss(DismissalEntity("remind:$itemId", clock()))
}
