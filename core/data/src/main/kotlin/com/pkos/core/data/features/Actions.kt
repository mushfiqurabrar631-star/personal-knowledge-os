package com.pkos.core.data.features

import com.pkos.core.data.db.PkosDatabase
import com.pkos.core.data.db.entity.ActionItemEntity
import com.pkos.core.data.model.FieldOrigin
import java.util.UUID

/**
 * Auto action extraction (spec 9 #7): finds task-like and idea-like sentences (Bengali + English) in the owner's own
 * text, plus action items returned by cloud AI. Everything starts as PROPOSED; nothing becomes a task without the
 * owner's confirmation, and due dates are never inferred — only the owner can set one.
 */
object ActionExtractor {
    data class Candidate(val text: String, val kind: String)

    private val task = listOf(
        Regex("^\\s*(?:[-*•]\\s*)?\\[ ]\\s*(.+)"), // markdown checkbox
        Regex("(?i)\\b(?:todo|to-do|to do)\\s*[:\\-]\\s*(.+)"),
        Regex("(?i)\\b(?:need to|have to|must|should|remember to|don't forget to|let's)\\s+(.+)"),
        Regex("(.+?(?:করতে হবে|করা লাগবে|কিনতে হবে|শিখতে হবে|দেখতে হবে|পড়তে হবে|জমা দিতে হবে|মনে রাখতে হবে).*)"),
        Regex("^\\s*(?:\\d+[.)]|[-*•])\\s*((?:buy|call|email|send|book|read|watch|learn|finish|submit|pay|check|fix|write|প্রস্তুত|কিনুন|কল|পাঠান)\\b.+)", RegexOption.IGNORE_CASE),
    )
    private val idea = listOf(
        Regex("(?i)\\bidea\\s*[:\\-]\\s*(.+)"),
        Regex("(?:আইডিয়া|ধারণা)\\s*[:\\-]\\s*(.+)"),
        Regex("(?i)\\b(?:what if|maybe we could|we could)\\s+(.+)"),
    )

    fun extract(text: String?, max: Int = 5): List<Candidate> {
        if (text.isNullOrBlank()) return emptyList()
        val parts = text.split(Regex("\\n|(?<=[.!?।])\\s+")).map { it.trim() }.filter { it.length in 6..300 }
        val out = LinkedHashMap<String, Candidate>()
        for (p in parts) {
            val t = task.firstNotNullOfOrNull { it.find(p)?.groupValues?.get(1) }?.let { Candidate(clean(it), "TASK") }
                ?: idea.firstNotNullOfOrNull { it.find(p)?.groupValues?.get(1) }?.let { Candidate(clean(it), "IDEA") }
            if (t != null && t.text.length >= 4) out.putIfAbsent(t.text.lowercase(), t)
            if (out.size >= max) break
        }
        return out.values.toList()
    }

    private fun clean(s: String) = s.trim().trimEnd('.', '।', '!').replaceFirstChar { it.uppercase() }.take(200)
}

class ActionService(private val db: PkosDatabase, private val clock: () -> Long = System::currentTimeMillis) {
    private val dao get() = db.featureDao()

    /** Proposes actions once per item (text + cloud items). Returns how many were proposed. */
    suspend fun propose(itemId: String, aiItems: List<String> = emptyList()): Int {
        if (dao.actionCount(itemId) > 0) return 0
        val item = db.knowledgeDao().get(itemId) ?: return 0
        val fromText = ActionExtractor.extract(listOfNotNull(item.rawText, item.userNote).joinToString("\n"))
        val fromAi = aiItems.map { it.trim() }.filter { it.length in 4..200 }.map { ActionExtractor.Candidate(it, "TASK") }
        val all = (fromText + fromAi).distinctBy { it.text.lowercase() }.take(6)
        val now = clock()
        all.forEach { c ->
            dao.insertAction(ActionItemEntity(UUID.randomUUID().toString(), itemId, c.text, c.kind, PROPOSED, null, if (c in fromAi) FieldOrigin.AI else FieldOrigin.SYSTEM, now, now))
        }
        return all.size
    }

    /** Owner confirms (optionally edits the text and sets a due date themselves). */
    suspend fun confirm(id: String, text: String? = null, dueAt: Long? = null) {
        val a = dao.action(id) ?: return
        dao.updateAction(id, CONFIRMED, dueAt, text?.trim()?.ifEmpty { null } ?: a.text, clock())
    }

    suspend fun dismiss(id: String) { val a = dao.action(id) ?: return; dao.updateAction(id, DISMISSED, a.dueAt, a.text, clock()) }
    suspend fun done(id: String, done: Boolean = true) { val a = dao.action(id) ?: return; dao.updateAction(id, if (done) DONE else CONFIRMED, a.dueAt, a.text, clock()) }

    /** Owner adds a task by hand to an item. */
    suspend fun add(itemId: String, text: String, dueAt: Long? = null): String {
        val id = UUID.randomUUID().toString(); val now = clock()
        dao.insertAction(ActionItemEntity(id, itemId, text.trim(), "TASK", CONFIRMED, dueAt, FieldOrigin.USER, now, now))
        return id
    }

    companion object {
        const val PROPOSED = "PROPOSED"; const val CONFIRMED = "CONFIRMED"; const val DONE = "DONE"; const val DISMISSED = "DISMISSED"
    }
}
