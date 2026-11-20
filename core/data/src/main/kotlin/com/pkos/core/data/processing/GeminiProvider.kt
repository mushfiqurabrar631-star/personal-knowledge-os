package com.pkos.core.data.processing

import org.json.JSONArray
import org.json.JSONObject

/** Minimal HTTP seam so the provider is testable without network. */
fun interface HttpPost {
    /** @return status code, body, and the Retry-After header (seconds) when present. */
    suspend fun post(url: String, headers: Map<String, String>, body: String): HttpReply
}

data class HttpReply(val status: Int, val body: String, val retryAfterSeconds: Long? = null)

/**
 * Google Gemini (user-supplied API key, free tier). Sends only title, text (trimmed), URL, platform and the allowed
 * category names — never files, notes or other items. The key goes in a header, never in the URL or logs.
 */
class GeminiProvider(
    private val apiKey: String,
    override val model: String = DEFAULT_MODEL,
    private val http: HttpPost,
) : AiProvider {
    override val name = "gemini"

    override suspend fun classify(input: AiInput, repairHint: String?): AiResponse {
        val reply = http.post(
            "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent",
            mapOf("x-goog-api-key" to apiKey, "Content-Type" to "application/json"),
            requestBody(input, repairHint),
        )
        return when (reply.status) {
            200 -> extractText(reply.body)?.let { AiResponse.Raw(it) } ?: AiResponse.Failed("empty response")
            429 -> if (reply.body.contains("per day", ignoreCase = true) || reply.body.contains("PerDay")) AiResponse.QuotaExceeded("daily quota")
            else AiResponse.RateLimited((reply.retryAfterSeconds ?: 60) * 1000)
            400 -> if (reply.body.contains("API_KEY_INVALID") || reply.body.contains("API key not valid")) AiResponse.AuthFailed("invalid key") else AiResponse.Failed("bad request")
            401, 403 -> AiResponse.AuthFailed("HTTP ${reply.status}")
            in 500..599 -> AiResponse.NetworkError("HTTP ${reply.status}")
            else -> AiResponse.Failed("HTTP ${reply.status}")
        }
    }

    private fun extractText(body: String): String? = runCatching {
        val parts = JSONObject(body).getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts")
        (0 until parts.length()).joinToString("") { parts.getJSONObject(it).optString("text") }.ifBlank { null }
    }.getOrNull()

    fun requestBody(input: AiInput, repairHint: String?): String {
        val prompt = buildString {
            appendLine("You organize one saved item for a personal knowledge app. The owner writes Bengali and English.")
            appendLine("Return ONLY a JSON object with exactly these keys: title, summary, main_category, topic, subtopic, content_type, purpose, tags, keywords, priority, status, related_item_ids, action_items, confidence, needs_review, review_reason.")
            appendLine("Rules: main_category must be one of: ${input.allowedCategories.joinToString(" | ")} — or null if none fits; never invent a category.")
            appendLine("content_type: article|video|website|tutorial|idea|note|document|image|message|tool|other. purpose: subset of learn, reference, build, decide, buy, revisit, inspiration (empty if unclear).")
            appendLine("priority: low|normal|high|critical (default normal). status: inbox unless obvious. confidence: 0..1, be honest; below 0.6 set needs_review=true with a short review_reason.")
            appendLine("Do not infer health, religion, politics or other personal attributes. Summary: 1–2 sentences in the item's language. At most 6 tags.")
            if (repairHint != null) appendLine("Your previous answer was rejected: $repairHint. Return valid JSON only.")
            appendLine("--- ITEM ---")
            appendLine("Title: ${input.title}")
            input.url?.let { appendLine("URL: $it") }
            input.platform?.let { appendLine("Platform: $it") }
            input.contentTypeHint?.let { appendLine("Detected type: $it") }
            appendLine("Text:")
            append(input.text.take(6_000))
        }
        return JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            put("generationConfig", JSONObject().put("responseMimeType", "application/json").put("temperature", 0.2))
        }.toString()
    }

    companion object {
        const val DEFAULT_MODEL = "gemini-3.5-flash-lite"
    }
}
