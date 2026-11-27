package com.pkos.core.data.processing

import java.io.File

/** Reads text out of a stored file (plain text, PDF, …). Returns null when the type is not supported. */
fun interface TextExtractor {
    /** @throws Exception on a real read failure (retried); return null for "not supported". */
    suspend fun extract(file: File, mimeType: String?): String?
}

/** On-device OCR. Returns recognised text (possibly empty). */
fun interface OcrEngine {
    suspend fun recognize(file: File): String
    /** OCR for a PDF without a text layer (scanned). null = not supported by this engine. */
    suspend fun recognizePdf(file: File): String? = null
}

/** What a classifier sees. Kept minimal on purpose (data minimisation, spec 3). */
data class AiInput(
    val title: String,
    val text: String,
    val url: String?,
    val platform: String?,
    val contentTypeHint: String?,
    val allowedCategories: List<String>,
    /** Other saved items (normal privacy only) the model may point to as related: ref → title. */
    val candidates: List<Pair<String, String>> = emptyList(),
)

sealed interface AiResponse {
    /** Raw JSON text from the model (validated by [AiResultParser]). */
    data class Raw(val json: String) : AiResponse
    data class RateLimited(val retryAfterMs: Long) : AiResponse
    data class QuotaExceeded(val detail: String) : AiResponse
    data class AuthFailed(val detail: String) : AiResponse
    data class NetworkError(val detail: String) : AiResponse
    data class Failed(val detail: String) : AiResponse
}

/** Optional cloud provider (spec 4.6). Only called when the owner enabled cloud AI and the item is not local-only. */
interface AiProvider {
    val name: String
    val model: String
    suspend fun classify(input: AiInput, repairHint: String? = null): AiResponse
}

const val PROMPT_VERSION = "pkos-classify-v1"

/** Plain text formats are read directly; PDFs go to [pdf] when available. Other types are "unsupported", not errors. */
class DefaultTextExtractor(private val pdf: TextExtractor? = null) : TextExtractor {
    override suspend fun extract(file: File, mimeType: String?): String? {
        val mime = mimeType?.lowercase().orEmpty()
        val ext = file.extension.lowercase()
        return when {
            mime.startsWith("text/") || mime in TEXT_MIMES || ext in TEXT_EXT -> file.bufferedReader(Charsets.UTF_8).use { r ->
                val buf = CharArray(200_000); val n = r.read(buf); if (n <= 0) "" else String(buf, 0, n)
            }
            mime == "application/pdf" || ext == "pdf" -> pdf?.extract(file, mimeType)
            else -> null
        }
    }

    private companion object {
        val TEXT_MIMES = setOf("application/json", "application/xml", "application/csv", "application/x-subrip")
        val TEXT_EXT = setOf("txt", "md", "csv", "json", "xml", "html", "htm", "srt", "log")
    }
}

/**
 * Review reasons written by PKOS itself are stable codes; the app shows them in the owner's language.
 * Free text from a cloud model (its own review_reason) is shown as written.
 */
object ReviewReason {
    const val NO_TOPIC = "pkos:no_topic"
    const val AMBIGUOUS = "pkos:ambiguous"
    const val UNKNOWN_CATEGORY = "pkos:unknown_category"
    const val LOW_CONFIDENCE = "pkos:low_confidence"
    const val CLOUD_INVALID = "pkos:cloud_invalid"
    const val CLOUD_QUOTA = "pkos:cloud_quota"
    const val CLOUD_AUTH = "pkos:cloud_auth"
    const val CLOUD_UNAVAILABLE = "pkos:cloud_unavailable"
    /** Looked like a password, card number, OTP, key or ID number: kept on this phone (spec 4.1 step 13). */
    const val SENSITIVE_LOCAL = "pkos:sensitive_local"
}

/** Prompt parts shared by every cloud provider so they all return the same schema (spec 4.4). */
object PromptRules {
    fun classifyPrompt(input: AiInput, repairHint: String?): String = buildString {
        appendLine("You organize one saved item for a personal knowledge app. The owner writes Bengali and English.")
        appendLine("Return ONLY a JSON object with exactly these keys: title, summary, main_category, topic, subtopic, content_type, purpose, tags, keywords, entities, priority, status, related_item_ids, action_items, confidence, needs_review, review_reason.")
        appendLine("Rules: main_category must be one of: ${input.allowedCategories.joinToString(" | ")} — or null if none fits; never invent a category.")
        appendLine("content_type: article|video|website|tutorial|idea|note|document|image|message|tool|other. purpose: subset of learn, reference, build, decide, buy, revisit, inspiration (empty if unclear).")
        appendLine("priority: low|normal|high|critical (default normal). status: inbox unless obvious. confidence: 0..1, be honest; below 0.6 set needs_review=true with a short review_reason.")
        appendLine("Do not infer health, religion, politics or other personal attributes. Summary: 1–2 sentences in the item's language. At most 6 tags.")
        appendLine(PromptRules.ENRICH)
        if (repairHint != null) appendLine("Your previous answer was rejected: $repairHint. Return valid JSON only.")
        appendLine("--- ITEM ---")
        appendLine("Title: ${input.title}")
        input.url?.let { appendLine("URL: $it") }
        input.platform?.let { appendLine("Platform: $it") }
        input.contentTypeHint?.let { appendLine("Detected type: $it") }
        appendLine("Text:")
        appendLine(input.text.take(6_000))
        PromptRules.candidates(input)?.let { append(it) }
    }

    const val ENRICH = "keywords: up to 10 search words (not repeating tags). entities: up to 10 names of people, organisations, places, products or tools that appear in the text — never guess. " +
        "action_items: short tasks or ideas that the text itself states, as strings; never add a date. related_item_ids: only refs from CANDIDATES that are clearly about the same subject, else []."

    fun candidates(input: AiInput): String? = input.candidates.takeIf { it.isNotEmpty() }?.let { c ->
        "\n--- CANDIDATES (ref: title) ---\n" + c.joinToString("\n") { (ref, title) -> "$ref: ${title.take(120)}" }
    }
}
