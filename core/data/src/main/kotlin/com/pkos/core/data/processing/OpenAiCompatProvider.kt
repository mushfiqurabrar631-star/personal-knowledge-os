package com.pkos.core.data.processing

import com.pkos.core.data.features.TextGenerator
import org.json.JSONArray
import org.json.JSONObject

/**
 * Second cloud provider (spec 4.6): any OpenAI-compatible `chat/completions` endpoint the owner chooses, with the
 * owner's own key. PKOS never picks a paid endpoint; the owner is warned that their endpoint may charge. Sends the same
 * minimal item data as Gemini. A "payment required" or "insufficient quota" answer stops cloud work (quota), it is never
 * retried in a loop.
 */
object OpenAiCompat {
    const val NAME = "openai_compat"

    /** HTTPS only; returns the base without a trailing slash, or null when unusable. */
    fun normalizeBase(raw: String): String? {
        val b = raw.trim().trimEnd('/')
        if (!b.startsWith("https://") || b.length <= "https://".length || b.any { it.isWhitespace() }) return null
        return b
    }

    fun headers(apiKey: String?): Map<String, String> =
        buildMap { put("Content-Type", "application/json"); apiKey?.takeIf { it.isNotBlank() }?.let { put("Authorization", "Bearer $it") } }

    fun body(model: String, prompt: String, json: Boolean): String = JSONObject().apply {
        put("model", model)
        put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
        put("temperature", 0.2)
        if (json) put("response_format", JSONObject().put("type", "json_object"))
    }.toString()

    fun content(body: String): String? = runCatching {
        JSONObject(body).getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content").ifBlank { null }
    }.getOrNull()

    fun map(reply: HttpReply): AiResponse = when (reply.status) {
        200 -> content(reply.body)?.let { AiResponse.Raw(it) } ?: AiResponse.Failed("empty response")
        402 -> AiResponse.QuotaExceeded("payment required")
        429 -> if (reply.body.contains("insufficient_quota") || reply.body.contains("billing", ignoreCase = true)) AiResponse.QuotaExceeded("quota")
        else AiResponse.RateLimited((reply.retryAfterSeconds ?: 60) * 1000)
        401, 403 -> AiResponse.AuthFailed("HTTP ${reply.status}")
        404 -> AiResponse.Failed("endpoint or model not found")
        in 500..599 -> AiResponse.NetworkError("HTTP ${reply.status}")
        else -> AiResponse.Failed("HTTP ${reply.status}")
    }
}

class OpenAiCompatProvider(
    private val apiKey: String?,
    private val baseUrl: String,
    override val model: String,
    private val http: HttpPost,
) : AiProvider {
    override val name = OpenAiCompat.NAME

    override suspend fun classify(input: AiInput, repairHint: String?): AiResponse {
        val base = OpenAiCompat.normalizeBase(baseUrl) ?: return AiResponse.Failed("endpoint must be https")
        if (model.isBlank()) return AiResponse.Failed("no model")
        val prompt = PromptRules.classifyPrompt(input, repairHint)
        var reply = http.post("$base/chat/completions", OpenAiCompat.headers(apiKey), OpenAiCompat.body(model, prompt, json = true))
        // Some compatible servers do not know response_format: ask once more without it.
        if (reply.status == 400 && reply.body.contains("response_format")) {
            reply = http.post("$base/chat/completions", OpenAiCompat.headers(apiKey), OpenAiCompat.body(model, prompt, json = false))
        }
        return OpenAiCompat.map(reply)
    }
}

/** Ask answers on the owner's compatible endpoint; any failure returns null (offline answer instead). */
class OpenAiCompatTextGenerator(
    private val apiKey: String?,
    private val baseUrl: String,
    private val model: String,
    private val http: HttpPost,
) : TextGenerator {
    override val provider = OpenAiCompat.NAME

    override suspend fun generate(prompt: String): String? = runCatching {
        val base = OpenAiCompat.normalizeBase(baseUrl) ?: return null
        if (model.isBlank()) return null
        val r = http.post("$base/chat/completions", OpenAiCompat.headers(apiKey), OpenAiCompat.body(model, prompt, json = false))
        if (r.status != 200) null else OpenAiCompat.content(r.body)
    }.getOrNull()
}
