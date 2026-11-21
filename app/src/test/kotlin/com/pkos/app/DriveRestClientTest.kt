package com.pkos.app

import com.pkos.app.backup.DriveRestClient
import com.pkos.core.data.backup.DriveErrorKind
import com.pkos.core.data.backup.DriveException
import com.pkos.core.data.backup.UploadStep
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Drive v3 REST contract over real HTTP (MockWebServer): folder, resumable upload, verification data, errors. */
@org.junit.runner.RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
class DriveRestClientTest {
    @get:Rule val tmp = TemporaryFolder()
    private val server = MockWebServer()
    private lateinit var drive: DriveRestClient

    @Before fun start() {
        server.start()
        drive = DriveRestClient({ "tok" }, baseUrl = server.url("/drive/v3").toString().removeSuffix("/"), uploadUrl = server.url("/upload/drive/v3").toString().removeSuffix("/"))
    }
    @After fun stop() = server.close()

    private fun ok(body: String, code: Int = 200) = MockResponse.Builder().code(code).body(body).build()

    @Test fun folderIsFoundOrCreatedWithBearerTokenAndNarrowQuery() = runBlocking {
        server.enqueue(ok("""{"files":[]}""")); server.enqueue(ok("""{"id":"F1"}"""))
        assertEquals("F1", drive.ensureFolder("Personal Knowledge OS Backups"))
        val search = server.takeRequest()
        assertEquals("Bearer tok", search.headers["Authorization"])
        assertTrue(search.url.queryParameter("q")!!.contains("name = 'Personal Knowledge OS Backups'"))
        val create = server.takeRequest()
        assertEquals("POST", create.method)
        assertTrue(create.body!!.utf8().replace("\\/", "/").contains("application/vnd.google-apps.folder"))
        server.enqueue(ok("""{"files":[{"id":"F1","name":"Personal Knowledge OS Backups"}]}"""))
        assertEquals("F1", drive.ensureFolder("Personal Knowledge OS Backups"))
    }

    @Test fun resumableUploadChunksAndResume() = runBlocking {
        val f = tmp.newFile().apply { writeBytes(ByteArray(1000) { it.toByte() }) }
        val session = server.url("/upload/session/abc").toString()
        server.enqueue(MockResponse.Builder().code(200).addHeader("Location", session).build())
        assertEquals(session, drive.startResumableUpload("F1", "pkos-backup-x.pkosbackup", 1000, mapOf("pkos_backup_id" to "B1")))
        val start = server.takeRequest()
        assertEquals("resumable", start.url.queryParameter("uploadType"))
        assertEquals("1000", start.headers["X-Upload-Content-Length"])
        assertTrue(start.body!!.utf8().contains("\"pkos_backup_id\":\"B1\""))

        server.enqueue(MockResponse.Builder().code(308).addHeader("Range", "bytes=0-399").build())
        assertEquals(UploadStep.Partial(400), drive.uploadChunk(session, f, 0, 400, 1000))
        val chunk = server.takeRequest()
        assertEquals("PUT", chunk.method); assertEquals("bytes 0-399/1000", chunk.headers["Content-Range"]); assertEquals(400, chunk.body!!.size)

        server.enqueue(MockResponse.Builder().code(308).addHeader("Range", "bytes=0-399").build())
        assertEquals(UploadStep.Partial(400), drive.queryUpload(session, 1000))
        assertEquals("bytes */1000", server.takeRequest().headers["Content-Range"])

        server.enqueue(ok("""{"id":"X","name":"n","size":"1000","md5Checksum":"abc","createdTime":"2026-11-21T00:00:00Z","appProperties":{"pkos_backup_id":"B1"}}"""))
        val done = drive.uploadChunk(session, f, 400, 600, 1000) as UploadStep.Done
        assertEquals("abc", done.file.md5); assertEquals(1000L, done.file.size); assertEquals("B1", done.file.appProperties["pkos_backup_id"])
        server.enqueue(MockResponse.Builder().code(308).build())
        assertEquals(UploadStep.Partial(0), drive.queryUpload(session, 1000))
    }

    private suspend fun kind(code: Int, body: String = "", header: Pair<String, String>? = null): DriveException {
        server.enqueue(MockResponse.Builder().code(code).body(body).apply { header?.let { addHeader(it.first, it.second) } }.build())
        return try { drive.getFile("x"); fail(); throw IllegalStateException() } catch (e: DriveException) { e }
    }

    @Test fun errorsMapToHonestKinds() = runBlocking {
        assertEquals(DriveErrorKind.AUTH_REQUIRED, kind(401).kind)
        assertEquals(DriveErrorKind.NOT_FOUND, kind(404).kind)
        assertEquals(DriveErrorKind.STORAGE_FULL, kind(403, """{"error":{"errors":[{"reason":"storageQuotaExceeded"}]}}""").kind)
        val rl = kind(429, "", "Retry-After" to "5")
        assertEquals(DriveErrorKind.RATE_LIMITED, rl.kind); assertEquals(5_000L, rl.retryAfterMs)
        assertEquals(DriveErrorKind.SERVER, kind(503).kind)
        server.enqueue(MockResponse.Builder().code(404).build())
        try { drive.queryUpload(server.url("/s").toString(), 10); fail() } catch (e: DriveException) { assertEquals(DriveErrorKind.SESSION_EXPIRED, e.kind) }
        val url = server.url("/x").toString(); server.close()
        val net = try { DriveRestClient({ "t" }, baseUrl = url).getFile("a"); null } catch (e: DriveException) { e }
        assertEquals(DriveErrorKind.NETWORK, net!!.kind)
    }

    @Test fun quotaAndDownload() = runBlocking {
        server.enqueue(ok("""{"storageQuota":{"limit":"16106127360","usage":"1024"}}"""))
        val q = drive.quota(); assertEquals(16106127360L, q.limitBytes); assertEquals(1024L, q.usageBytes)
        server.enqueue(ok("""{"storageQuota":{"usage":"5"}}"""))
        assertEquals(null, drive.quota().limitBytes)
        server.enqueue(ok("encrypted-bytes"))
        val out = tmp.newFile(); drive.download("X", out)
        assertEquals("encrypted-bytes", out.readText())
        assertEquals("media", server.run { takeRequest(); takeRequest(); takeRequest() }.url.queryParameter("alt"))
    }
}
