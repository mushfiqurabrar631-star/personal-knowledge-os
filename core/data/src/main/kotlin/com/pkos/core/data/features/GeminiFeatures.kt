package com.pkos.core.data.features

import com.pkos.core.data.processing.HttpPost
import org.json.JSONArray
import org.json.JSONObject

/**
 * Optional cloud helpers on the owner's own Gemini key (free tier; no paid service is enabled by PKOS).
 * Callers pass only NORMAL-privacy text; any failure returns null so the app falls back to offline search/answers.
 */
class GeminiTextGenerator(private val apiKey: String, private val model: String, private val http: HttpPost) : TextGenerator {
    override suspend fun generate(prompt: String): String? = runCatching {
        val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            .put("generationConfig", JSONObject().put("temperature", 0.2)).toString()
        val r = http.post("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent", mapOf("x-goog-api-key" to apiKey, "Content-Type" to "application/json"), body)
        if (r.status != 200) return null
        val parts = JSONObject(r.body).getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts")
        (0 until parts.length()).joinToString("") { parts.getJSONObject(it).optString("text") }.ifBlank { null }
    }.getOrNull()
}

class GeminiEmbeddingProvider(private val apiKey: String, override val model: String = DEFAULT_MODEL, private val http: HttpPost) : EmbeddingProvider {
    override suspend fun embed(text: String): FloatArray? = runCatching {
        val body = JSONObject().put("model", "models/$model").put("content", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", text.take(4_000)))))
            .put("outputDimensionality", 256).toString()
        val r = http.post("https://generativelanguage.googleapis.com/v1beta/models/$model:embedContent", mapOf("x-goog-api-key" to apiKey, "Content-Type" to "application/json"), body)
        if (r.status != 200) return null
        val v = JSONObject(r.body).getJSONObject("embedding").getJSONArray("values")
        FloatArray(v.length()) { v.getDouble(it).toFloat() }.takeIf { it.isNotEmpty() }
    }.getOrNull()

    companion object { const val DEFAULT_MODEL = "gemini-embedding-001" }
}
