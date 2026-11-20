package com.pkos.core.notion

import com.pkos.core.notion.api.HttpRequest
import com.pkos.core.notion.api.HttpResponse
import com.pkos.core.notion.api.HttpTransport
import com.pkos.core.notion.api.NotionClient
import com.pkos.core.notion.api.NotionErrorKind
import com.pkos.core.notion.api.NotionResult
import com.pkos.core.notion.api.RateLimiter
import com.pkos.core.notion.sync.RetryPolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ClientAndRetryTest {

    @Test
    fun sendsAuthAndVersionHeadersAndParsesErrors() = runBlocking {
        var seen: HttpRequest? = null
        val transport = object : HttpTransport {
            override suspend fun execute(request: HttpRequest): HttpResponse {
                seen = request
                return HttpResponse(429, mapOf("retry-after" to "3"), """{"object":"error","code":"rate_limited","message":"slow down"}""")
            }
        }
        val r = NotionClient(transport, { "ntn_abc" }, RateLimiter.none()).retrieveDataSource("ds1")
        assertEquals("Bearer ntn_abc", seen!!.headers["Authorization"])
        assertEquals("2026-03-11", seen!!.headers["Notion-Version"])
        assertEquals("https://api.notion.com/v1/data_sources/ds1", seen!!.url)
        r as NotionResult.Err
        assertEquals(NotionErrorKind.RATE_LIMITED, r.error.kind)
        assertEquals(3L, r.error.retryAfterSeconds)
        assertEquals("rate_limited", r.error.code)
    }

    @Test
    fun nonJsonSuccessIsInvalidResponse() = runBlocking {
        val transport = object : HttpTransport {
            override suspend fun execute(request: HttpRequest) = HttpResponse(200, body = "<html>")
        }
        val r = NotionClient(transport, { "t" }, RateLimiter.none()).retrievePage("p")
        assertTrue(r is NotionResult.Err && r.error.kind == NotionErrorKind.INVALID_RESPONSE)
    }

    @Test
    fun rateLimiterSpacesCalls() = runBlocking {
        var now = 0L
        val sleeps = mutableListOf<Long>()
        val limiter = RateLimiter(350, clockMs = { now }, sleeper = { sleeps += it; now += it })
        limiter.acquire(); now += 100; limiter.acquire(); limiter.acquire()
        assertEquals(listOf(250L, 350L), sleeps)
    }

    @Test
    fun backoffGrowsAndIsCapped() {
        val p = RetryPolicy(baseDelayMs = 2_000, maxDelayMs = 60_000, random = Random(42))
        val d0 = p.delayFor(0); val d3 = p.delayFor(3); val d20 = p.delayFor(20)
        assertTrue(d0 in 1_000..2_000)
        assertTrue(d3 in 8_000..16_000)
        assertTrue(d20 in 30_000..60_000)
        assertTrue(p.exhausted(7))
        assertEquals(60_000, p.clampServerDelay(10_000_000))
    }
}
