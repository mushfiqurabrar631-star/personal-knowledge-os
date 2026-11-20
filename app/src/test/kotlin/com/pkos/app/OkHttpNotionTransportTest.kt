package com.pkos.app

import com.pkos.app.notion.OkHttpNotionTransport
import com.pkos.core.notion.FakeNotion
import com.pkos.core.notion.TEST_DS
import com.pkos.core.notion.api.HttpRequest
import com.pkos.core.notion.api.NotionClient
import com.pkos.core.notion.api.RateLimiter
import com.pkos.core.notion.model.SyncItem
import com.pkos.core.notion.sync.NotionFileUploader
import com.pkos.core.notion.sync.NotionSyncEngine
import com.pkos.core.notion.sync.SyncJournal
import com.pkos.core.notion.sync.SyncOutcome
import com.pkos.core.notion.sync.WriteIntent
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.time.Instant
import java.util.UUID

/**
 * The real OkHttp transport over real HTTP (local MockWebServer). The last test runs the whole sync engine over
 * HTTP against the in-memory Notion (FakeNotion) to prove the transport carries every call type correctly.
 */
class OkHttpNotionTransportTest {
    @get:Rule val tmp = TemporaryFolder()
    private val server = MockWebServer()
    private val transport = OkHttpNotionTransport()

    @Before fun start() = server.start()
    @After fun stop() = server.close()

    @Test fun patchSendsJsonBodyAndHeaders() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body("""{"object":"page"}""").build())
        val res = transport.execute(HttpRequest("PATCH", server.url("/v1/pages/abc").toString(), mapOf("Authorization" to "Bearer t", "Notion-Version" to "2026-03-11"), """{"properties":{}}"""))
        assertEquals(200, res.status)
        val req = server.takeRequest()
        assertEquals("PATCH", req.method)
        assertEquals("Bearer t", req.headers["Authorization"])
        assertEquals("2026-03-11", req.headers["Notion-Version"])
        assertEquals("""{"properties":{}}""", req.body!!.utf8())
    }

    @Test fun errorStatusAndRetryAfterArePassedThrough() = runBlocking {
        server.enqueue(MockResponse.Builder().code(429).addHeader("Retry-After", "9").body("""{"code":"rate_limited"}""").build())
        val res = transport.execute(HttpRequest("GET", server.url("/x").toString(), emptyMap(), null))
        assertEquals(429, res.status); assertEquals("9", res.header("Retry-After"))
    }

    @Test fun networkFailureThrowsIOException() = runBlocking {
        val url = server.url("/x").toString()
        server.close()
        val thrown = runCatching { transport.execute(HttpRequest("GET", url, emptyMap(), null)) }.exceptionOrNull()
        assertTrue(thrown is IOException)
    }

    @Test fun multipartUploadCarriesTheFile() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body("""{"status":"uploaded"}""").build())
        val f = tmp.newFile("a.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        transport.executeMultipart(server.url("/v1/file_uploads/x/send").toString(), mapOf("Authorization" to "Bearer t"), "file", "a.png", "image/png", f)
        val req = server.takeRequest()
        assertTrue(req.headers["Content-Type"]!!.startsWith("multipart/form-data"))
        val body = req.body!!.utf8()
        assertTrue(body.contains("name=\"file\"; filename=\"a.png\""))
        assertTrue(body.contains("Content-Type: image/png"))
    }

    @Test fun fullSyncEngineWorksOverRealHttp() = runBlocking {
        val fake = FakeNotion()
        val legacy = (1..15).map { fake.seedLegacyPage("Old $it") }.associateWith { fake.pages.getValue(it) }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = runBlocking {
                val path = request.url.encodedPath.removePrefix("/v1") + (request.url.encodedQuery?.let { "?$it" } ?: "")
                val r = if (path.endsWith("/send")) {
                    val f = tmp.newFile(); f.writeBytes(request.body?.toByteArray() ?: ByteArray(0))
                    fake.executeMultipart("x/file_uploads/" + path.substringAfter("/file_uploads/"), emptyMap(), "file", "a.png", "image/png", f)
                } else {
                    fake.execute(HttpRequest(request.method!!, "https://api.notion.com/v1$path", mapOf("Notion-Version" to request.headers["Notion-Version"].orEmpty()), request.body?.utf8()?.ifEmpty { null }))
                }
                MockResponse.Builder().code(r.status).body(r.body).apply { r.headers.forEach { (k, v) -> addHeader(k, v) } }.build()
            }
        }
        val client = NotionClient(transport, { "secret" }, RateLimiter.none(), baseUrl = server.url("/v1").toString().removeSuffix("/"))
        val journal = object : SyncJournal {
            val m = mutableMapOf<String, WriteIntent>()
            override suspend fun lastIntent(captureId: String) = m[captureId]
            override suspend fun recordIntent(intent: WriteIntent) { m[intent.captureId] = intent }
        }
        val engine = NotionSyncEngine(client, TEST_DS, journal)
        val up = NotionFileUploader(client).upload(tmp.newFile("s.png").apply { writeBytes(ByteArray(64)) }, "s.png", "image/png") as NotionFileUploader.Result.Uploaded
        val item = SyncItem(UUID.randomUUID().toString(), "HTTP end-to-end", "https://example.com/a", Instant.now(),
            attachments = listOf(com.pkos.core.notion.model.NotionFileRef("s.png", fileUploadId = up.fileUploadId)))
        val first = engine.sync(item, null) as SyncOutcome.Synced
        val second = engine.sync(item.copy(pinned = true), first.link) as SyncOutcome.Synced
        assertTrue(first.created && second.propertiesWritten)
        assertEquals(16, fake.pages.size)
        legacy.forEach { (id, p) -> assertEquals(p, fake.pages.getValue(id)) }
    }
}
