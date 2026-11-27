package com.pkos.core.data

import com.pkos.core.data.processing.AiInput
import com.pkos.core.data.processing.AiResponse
import com.pkos.core.data.processing.HttpReply
import com.pkos.core.data.processing.OpenAiCompat
import com.pkos.core.data.processing.OpenAiCompatProvider
import com.pkos.core.data.processing.OpenAiCompatTextGenerator
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

/** Phase 1 gap: second (OpenAI-compatible) cloud provider, spec 4.6. Fake HTTP only — no network, no paid call. */
@RunWith(AndroidJUnit4::class)
class P1CompatProviderTest {
    private val input = AiInput("Title", "Body text", "https://a.com", "web", "article", listOf("Programming & Data", "Ideas & Inspiration"))
    private val calls = mutableListOf<Triple<String, Map<String, String>, String>>()
    private fun ok(content: String) = HttpReply(200, JSONObject().put("choices", org.json.JSONArray().put(JSONObject().put("message", JSONObject().put("content", content)))).toString())
    private fun provider(vararg replies: HttpReply, key: String? = "sk-own", base: String = "https://llm.example.com/v1/") = replies.toMutableList().let { q ->
        OpenAiCompatProvider(key, base, "my-model", { u, h, b -> calls += Triple(u, h, b); q.removeAt(0) })
    }

    @Test fun sendsTheSameSchemaToChatCompletionsWithKeyInHeaderOnly() = runBlocking {
        assertEquals(AiResponse.Raw("{\"a\":1}"), provider(ok("{\"a\":1}")).classify(input))
        val (url, headers, body) = calls.single()
        assertEquals("https://llm.example.com/v1/chat/completions", url)
        assertEquals("Bearer sk-own", headers["Authorization"]); assertFalse(url.contains("sk-own")); assertFalse(body.contains("sk-own"))
        val json = JSONObject(body)
        assertEquals("my-model", json.getString("model"))
        assertEquals("json_object", json.getJSONObject("response_format").getString("type"))
        val prompt = json.getJSONArray("messages").getJSONObject(0).getString("content")
        assertTrue(prompt.contains("Programming & Data | Ideas & Inspiration")); assertTrue(prompt.contains("related_item_ids"))
    }

    @Test fun keyIsOptionalAndHttpIsRefusedWithoutAnyCall() = runBlocking {
        provider(ok("{}"), key = null).classify(input)
        assertNull(calls.single().second["Authorization"])
        calls.clear()
        assertTrue(provider(ok("{}"), base = "http://insecure.example.com/v1").classify(input) is AiResponse.Failed)
        assertTrue(calls.isEmpty())
        assertNull(OpenAiCompat.normalizeBase("ftp://x")); assertNull(OpenAiCompat.normalizeBase("https://")); assertEquals("https://a.b/v1", OpenAiCompat.normalizeBase(" https://a.b/v1/ "))
    }

    @Test fun paymentAndQuotaAnswersStopInsteadOfRetrying() = runBlocking {
        assertTrue(provider(HttpReply(402, "payment required")).classify(input) is AiResponse.QuotaExceeded)
        assertTrue(provider(HttpReply(429, """{"error":{"code":"insufficient_quota"}}""")).classify(input) is AiResponse.QuotaExceeded)
        assertEquals(AiResponse.RateLimited(5_000), provider(HttpReply(429, "slow", 5)).classify(input))
        assertTrue(provider(HttpReply(401, "")).classify(input) is AiResponse.AuthFailed)
        assertTrue(provider(HttpReply(502, "")).classify(input) is AiResponse.NetworkError)
        assertTrue(provider(HttpReply(200, "{}")).classify(input) is AiResponse.Failed)
    }

    @Test fun serversWithoutJsonModeGetOneRetryWithoutIt() = runBlocking {
        val r = provider(HttpReply(400, "unknown field response_format"), ok("{\"b\":2}")).classify(input)
        assertEquals(AiResponse.Raw("{\"b\":2}"), r)
        assertEquals(2, calls.size); assertFalse(JSONObject(calls[1].third).has("response_format"))
    }

    @Test fun textGeneratorReportsItsProviderAndFailsSoftly() = runBlocking {
        val g = OpenAiCompatTextGenerator("k", "https://llm.example.com/v1", "m", { _, _, _ -> ok("answer [1]") })
        assertEquals("answer [1]", g.generate("q")); assertEquals(OpenAiCompat.NAME, g.provider)
        assertNull(OpenAiCompatTextGenerator("k", "https://llm.example.com/v1", "m", { _, _, _ -> HttpReply(500, "") }).generate("q"))
    }
}
