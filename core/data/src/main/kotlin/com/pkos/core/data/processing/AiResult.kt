package com.pkos.core.data.processing

import com.pkos.core.data.model.ContentType
import com.pkos.core.data.model.ItemPriority
import com.pkos.core.data.model.ItemStatus
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Validated classification, from the cloud model or the local rules (spec 4.4). */
data class AiResult(
    val title: String?,
    val summary: String?,
    /** Category id from the allowed list, or null (= Unsorted). Never invented. */
    val categoryId: String?,
    val topic: String?,
    val contentType: ContentType?,
    val purpose: List<String>,
    val tags: List<String>,
    val priority: ItemPriority,
    val status: ItemStatus,
    val confidence: Double,
    val needsReview: Boolean,
    val reviewReason: String?,
    val subtopic: String? = null,
)

sealed interface ParsedAi {
    data class Valid(val result: AiResult) : ParsedAi
    data class Invalid(val reason: String) : ParsedAi
}

/**
 * Strict validator for the spec 4.4 JSON. Anything malformed is rejected with a reason (used for one repair retry).
 * Unknown categories are not an error but are never accepted: they become null + needs_review ("never invent").
 */
object AiResultParser {
    val PURPOSES = setOf("learn", "reference", "build", "decide", "buy", "revisit", "inspiration")
    private val TYPES = mapOf(
        "article" to ContentType.ARTICLE, "video" to ContentType.VIDEO, "website" to ContentType.WEBSITE,
        "tutorial" to ContentType.TUTORIAL, "idea" to ContentType.IDEA, "note" to ContentType.NOTE,
        "document" to ContentType.DOCUMENT, "image" to ContentType.IMAGE, "screenshot" to ContentType.SCREENSHOT,
        "message" to ContentType.MESSAGE, "tool" to ContentType.TOOL, "other" to ContentType.OTHER,
    )

    /** @param categories allowed categories: id → display name. */
    fun parse(raw: String, categories: Map<String, String>): ParsedAi {
        val json = try {
            JSONObject(stripFences(raw))
        } catch (e: JSONException) {
            return ParsedAi.Invalid("not a JSON object")
        }
        fun str(key: String, required: Boolean, max: Int): Result<String?> {
            if (!json.has(key) || json.isNull(key)) return if (required) Result.failure(IllegalArgumentException("missing $key")) else Result.success(null)
            val v = json.opt(key) as? String ?: return Result.failure(IllegalArgumentException("$key must be a string"))
            return Result.success(v.trim().take(max).ifBlank { null })
        }
        fun list(key: String): Result<List<String>> {
            if (!json.has(key) || json.isNull(key)) return Result.success(emptyList())
            val arr = json.opt(key) as? JSONArray ?: return Result.failure(IllegalArgumentException("$key must be an array"))
            return Result.success((0 until arr.length()).map { i -> (arr.opt(i) as? String ?: return Result.failure(IllegalArgumentException("$key[$i] must be a string"))).trim() }.filter { it.isNotEmpty() })
        }
        return try {
            val title = str("title", required = true, max = 200).getOrThrow()
            val summary = str("summary", required = true, max = 1200).getOrThrow()
            val categoryName = str("main_category", required = false, max = 120).getOrThrow()
            val topic = str("topic", required = false, max = 120).getOrThrow()
            val subtopic = str("subtopic", required = false, max = 120).getOrThrow()
            val typeRaw = str("content_type", required = true, max = 40).getOrThrow()?.lowercase()
            val type = TYPES[typeRaw] ?: return ParsedAi.Invalid("content_type '$typeRaw' not allowed")
            val purpose = list("purpose").getOrThrow().map { it.lowercase() }
            purpose.firstOrNull { it !in PURPOSES }?.let { return ParsedAi.Invalid("purpose '$it' not allowed") }
            val tags = list("tags").getOrThrow().map { it.take(40) }.distinctBy { it.lowercase() }.take(8)
            val priority = when (str("priority", false, 20).getOrThrow()?.lowercase()) {
                null, "normal" -> ItemPriority.NORMAL; "low" -> ItemPriority.LOW; "high" -> ItemPriority.HIGH; "critical" -> ItemPriority.CRITICAL
                else -> return ParsedAi.Invalid("priority not allowed")
            }
            val status = when (str("status", false, 20).getOrThrow()?.lowercase()) {
                null, "inbox" -> ItemStatus.INBOX; "to_review" -> ItemStatus.TO_REVIEW; "to_learn" -> ItemStatus.TO_LEARN
                "in_progress" -> ItemStatus.IN_PROGRESS; "done" -> ItemStatus.DONE; "archived" -> ItemStatus.ARCHIVED
                else -> return ParsedAi.Invalid("status not allowed")
            }
            if (!json.has("confidence") || json.isNull("confidence")) return ParsedAi.Invalid("missing confidence")
            var confidence = json.optDouble("confidence", Double.NaN)
            if (confidence.isNaN()) return ParsedAi.Invalid("confidence must be a number")
            if (confidence > 1.0 && confidence <= 100.0) confidence /= 100.0 // spec allows 0–100
            if (confidence < 0.0 || confidence > 1.0) return ParsedAi.Invalid("confidence out of range")
            val needsReview = json.optBoolean("needs_review", false)
            var reason = str("review_reason", false, 300).getOrThrow()

            val categoryId = categoryName?.let { name ->
                categories.entries.firstOrNull { it.value.equals(name, ignoreCase = true) || it.key.equals(name, ignoreCase = true) }?.key
            }
            var review = needsReview
            if (categoryName != null && categoryId == null) { review = true; reason = reason ?: ReviewReason.UNKNOWN_CATEGORY }
            ParsedAi.Valid(AiResult(title, summary, categoryId, topic, type, purpose.distinct(), tags, priority, status, confidence, review, reason, subtopic))
        } catch (e: IllegalArgumentException) {
            ParsedAi.Invalid(e.message ?: "invalid")
        }
    }

    private fun stripFences(raw: String): String {
        val t = raw.trim()
        if (!t.startsWith("```")) return t
        return t.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    }

    fun toJson(r: AiResult, categories: Map<String, String>): String = JSONObject().apply {
        put("title", r.title); put("summary", r.summary); put("main_category", r.categoryId?.let { categories[it] } ?: JSONObject.NULL)
        put("topic", r.topic ?: JSONObject.NULL); put("subtopic", r.subtopic ?: JSONObject.NULL); put("content_type", r.contentType?.name?.lowercase() ?: "other")
        put("purpose", JSONArray(r.purpose)); put("tags", JSONArray(r.tags)); put("priority", r.priority.name.lowercase())
        put("status", r.status.name.lowercase()); put("confidence", r.confidence); put("needs_review", r.needsReview)
        put("review_reason", r.reviewReason ?: JSONObject.NULL)
    }.toString()
}
