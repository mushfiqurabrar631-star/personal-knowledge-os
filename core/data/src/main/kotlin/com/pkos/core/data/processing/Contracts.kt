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
}

/** What a classifier sees. Kept minimal on purpose (data minimisation, spec 3). */
data class AiInput(
    val title: String,
    val text: String,
    val url: String?,
    val platform: String?,
    val contentTypeHint: String?,
    val allowedCategories: List<String>,
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
}
