package com.pkos.core.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pkos.core.data.capture.AttachmentStore
import com.pkos.core.data.capture.CaptureFile
import com.pkos.core.data.capture.CaptureRepository
import com.pkos.core.data.capture.CaptureRequest
import com.pkos.core.data.capture.CaptureResult
import com.pkos.core.data.db.PkosDatabase
import com.pkos.core.data.model.CaptureMethod
import com.pkos.core.data.model.JobState
import com.pkos.core.data.model.PrivacyScope
import com.pkos.core.data.model.SyncState
import com.pkos.core.data.backup.DriveErrorKind
import com.pkos.core.data.backup.FakeDrive
import com.pkos.core.data.notion.ConnectionReport
import com.pkos.core.data.notion.DriveLargeFileHost
import com.pkos.core.data.notion.LargeFileHost
import com.pkos.core.data.notion.NotionSyncResult
import com.pkos.core.data.notion.NotionSyncService
import com.pkos.core.data.processing.DefaultTextExtractor
import com.pkos.core.data.processing.ProcessingEngine
import com.pkos.core.data.repository.CategoryRepository
import com.pkos.core.notion.FakeNotion
import com.pkos.core.notion.api.NotionClient
import com.pkos.core.notion.api.RateLimiter
import com.pkos.core.notion.sync.NotionFileUploader
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream

@RunWith(AndroidJUnit4::class)
class NotionSyncServiceTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var db: PkosDatabase
    private lateinit var store: AttachmentStore
    private lateinit var fake: FakeNotion
    private lateinit var legacy: Map<String, JsonObject>
    private var token: String? = "secret_test"
    private var now = 1_800_000_000_000L
    private lateinit var service: NotionSyncService

    @Before fun setUp() = runBlocking {
        db = PkosDatabase.inMemory(ApplicationProvider.getApplicationContext())
        CategoryRepository(db).ensureStarterTaxonomy()
        store = AttachmentStore(tmp.newFolder("att"))
        fake = FakeNotion()
        legacy = (1..15).map { fake.seedLegacyPage("Old entry $it") }.associateWith { fake.pages.getValue(it) }
        service = build()
    }

    private fun build(maxBytes: Long = NotionFileUploader.FREE_PLAN_MAX_BYTES, host: LargeFileHost? = null): NotionSyncService {
        val client = NotionClient(fake, { token }, RateLimiter.none())
        return NotionSyncService(db, client, store::resolve, clock = { now }, uploader = NotionFileUploader(client, maxBytes), largeFiles = { host })
    }

    @After fun tearDown() = db.close()

    private suspend fun captured(text: String, files: List<CaptureFile> = emptyList(), privacy: PrivacyScope = PrivacyScope.NORMAL): String {
        val id = (CaptureRepository(db, store, { Long.MAX_VALUE }, clock = { now }).capture(CaptureRequest(CaptureMethod.SHARE, text = text, files = files), privacy) as CaptureResult.Saved).itemId
        ProcessingEngine(db, store::resolve, DefaultTextExtractor(), null, clock = { now }).process(id, null)
        return id
    }

    private fun pageOf(id: String) = fake.pages.values.single { it.toString().contains(id) && it["object"]?.jsonPrimitive?.content != "block" }
    private fun prop(page: JsonObject, name: String) = page["properties"]!!.jsonObject[name]!!.jsonObject
    private fun legacyUntouched() = legacy.forEach { (id, p) -> assertEquals(p, fake.pages.getValue(id)) }
    private suspend fun item(id: String) = db.knowledgeDao().get(id)!!

    @Test fun capturedItemIsCreatedVerifiedAndMapped() = runBlocking {
        val id = captured("Python SQL tutorial for data analysis https://youtu.be/abc?si=x")
        assertEquals(listOf(id), service.itemsNeedingSync())
        val r = service.sync(id) as NotionSyncResult.Synced
        assertTrue(r.created)
        assertEquals(SyncState.NOTION_SYNCED, item(id).syncState)
        val m = db.notionDao().mapping(id)!!
        assertEquals(r.pageUrl, m.notionUrl)
        assertTrue(prop(fake.pages.getValue(m.notionPageId), "Capture ID").toString().contains(id))
        assertEquals("Youtube", prop(fake.pages.getValue(m.notionPageId), "platform ")["select"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertTrue(fake.optionViolations.isEmpty())
        assertEquals(JobState.SUCCEEDED, db.notionDao().job(NotionSyncService.jobId(id))!!.state)
        assertTrue(service.itemsNeedingSync().isEmpty())
        legacyUntouched()
    }

    @Test fun unchangedResyncWritesNothingAndLocalEditSendsOnlyTheChange() = runBlocking {
        val id = captured("Python SQL tutorial")
        service.sync(id)
        val before = fake.calls.size
        service.sync(id)
        assertTrue(fake.calls.drop(before).none { it.method != "GET" && !it.path.endsWith("/query") })
        now += 1_000
        db.knowledgeDao().setPinned(id, true, now)
        assertEquals(listOf(id), service.itemsNeedingSync())
        service.sync(id) as NotionSyncResult.Synced
        val patch = fake.calls.last { it.method == "PATCH" && it.path.startsWith("/pages/") }
        assertEquals(setOf("Pinned"), patch.body!!["properties"]!!.jsonObject.keys)
        assertEquals(1, fake.createCount())
    }

    @Test fun localOnlyItemsAreNeverSent() = runBlocking {
        val id = captured("private python note", privacy = PrivacyScope.LOCAL_ONLY)
        assertTrue(service.itemsNeedingSync().isEmpty())
        assertEquals(NotionSyncResult.Skipped, service.sync(id))
        assertEquals(0, fake.createCount())
    }

    @Test fun filesAreUploadedAttachedOnceAndOversizedOnesSkipped() = runBlocking {
        service = build(maxBytes = 4_096)
        val id = captured(
            "screenshots",
            listOf(
                CaptureFile("small.png", "image/png", null) { ByteArrayInputStream(ByteArray(2_000) { 1 }) },
                CaptureFile("huge.pdf", "application/pdf", null) { ByteArrayInputStream(ByteArray(10_000) { 2 }) },
            ),
        )
        service.sync(id) as NotionSyncResult.Synced
        val page = fake.pages.getValue(db.notionDao().mapping(id)!!.notionPageId)
        val files = prop(page, "Files & media")["files"]!!.jsonArray
        assertEquals(listOf("small.png"), files.map { it.jsonObject["name"]!!.jsonPrimitive.content })
        val atts = db.attachmentDao().forItem(id).associateBy { it.filename }
        assertTrue(atts["small.png"]!!.notionAttached)
        assertEquals(NotionSyncService.SKIP_TOO_LARGE, atts["huge.pdf"]!!.notionSkipReason)
        // Later edit: no re-upload and no resend of the attached upload id.
        val uploads = fake.calls.count { it.path == "/file_uploads" }
        now += 1_000; db.knowledgeDao().setPinned(id, true, now)
        assertTrue(service.sync(id) is NotionSyncResult.Synced)
        assertEquals(uploads, fake.calls.count { it.path == "/file_uploads" })
    }

    @Test fun uploadFailureRetriesBeforeCreatingAnyPage() = runBlocking {
        val id = captured("x", listOf(CaptureFile("a.png", "image/png", null) { ByteArrayInputStream(ByteArray(10)) }))
        fake.failNextSend = true
        assertTrue(service.sync(id) is NotionSyncResult.Retry)
        assertEquals(0, fake.createCount())
        assertEquals(SyncState.ERROR_RETRYABLE, item(id).syncState)
        assertTrue(service.sync(id, 1) is NotionSyncResult.Synced)
        assertEquals(1, fake.createCount())
    }

    @Test fun rateLimitAndNetworkAreRetriedWithoutFakeSuccess() = runBlocking {
        val id = captured("Python")
        fake.failNext("POST", "/pages", 429, "rate_limited", retryAfter = "7")
        val r = service.sync(id) as NotionSyncResult.Retry
        assertEquals(7_000, r.delayMs)
        assertEquals(SyncState.ERROR_RETRYABLE, item(id).syncState)
        assertNull(db.notionDao().mapping(id))
        val job = db.notionDao().job(NotionSyncService.jobId(id))!!
        assertEquals(JobState.RETRY_SCHEDULED, job.state); assertEquals("RATE_LIMITED", job.lastErrorCode)
        // A sent create is treated as "maybe applied": PKOS waits out Notion's index lag before trying again.
        now += 200_000
        fake.throwNetworkOnNext = true
        assertTrue(service.sync(id, 1) is NotionSyncResult.Retry)
        now += 200_000
        assertTrue(service.sync(id, 2) is NotionSyncResult.Synced)
        assertEquals(16, fake.pages.size) // 15 legacy + exactly one new page
    }

    @Test fun lostCreateResponseNeverDuplicates() = runBlocking {
        val id = captured("Python")
        fake.loseResponseOfNext("POST", "/pages")
        assertTrue(service.sync(id) is NotionSyncResult.Retry)
        now += 200_000 // past the index-lag grace period
        assertTrue(service.sync(id, 1) is NotionSyncResult.Synced)
        assertEquals(1, fake.createCount())
    }

    @Test fun badTokenNeedsOwnerAction() = runBlocking {
        val id = captured("Python")
        fake.failNext("GET", "/data_sources", 401, "unauthorized")
        assertTrue(service.sync(id) is NotionSyncResult.ActionRequired)
        assertEquals(SyncState.ERROR_ACTION_REQUIRED, item(id).syncState)
        token = null
        assertEquals(ConnectionReport.Failed("NO_TOKEN"), service.checkConnection())
    }

    @Test fun connectionCheckReadsLiveSchema() = runBlocking {
        val ok = service.checkConnection() as ConnectionReport.Ok
        assertEquals(12, ok.usableFields); assertTrue(ok.missing.isEmpty())
        assertTrue(fake.calls.all { it.method == "GET" })
    }

    @Test fun notionEditBecomesConflictAndKeepNotionIsRespected() = runBlocking {
        val id = captured("Python SQL tutorial")
        service.sync(id)
        val pageId = db.notionDao().mapping(id)!!.notionPageId
        fake.userEdit(pageId, "Status", buildJsonObject { put("select", buildJsonObject { put("name", "Completed") }) })
        now += 1_000; db.knowledgeDao().setPinned(id, true, now)
        assertEquals(NotionSyncResult.Conflict, service.sync(id))
        assertEquals(SyncState.CONFLICT, item(id).syncState)
        val c = db.notionDao().conflict(id)!!
        assertTrue(c.properties.contains("Status"))
        assertTrue(service.itemsNeedingSync().isEmpty()) // waits for the owner
        // Owner keeps Notion.
        assertTrue(service.resolveConflict(id, keepNotion = true) is NotionSyncResult.Synced)
        assertNull(db.notionDao().conflict(id))
        assertEquals("Completed", prop(fake.pages.getValue(pageId), "Status")["select"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals(SyncState.NOTION_SYNCED, item(id).syncState)
    }

    @Test fun overwriteChoiceWritesLocalValues() = runBlocking {
        val id = captured("Python SQL tutorial")
        service.sync(id)
        val pageId = db.notionDao().mapping(id)!!.notionPageId
        fake.userEdit(pageId, "Status", buildJsonObject { put("select", buildJsonObject { put("name", "Completed") }) })
        now += 1_000; db.knowledgeDao().setPinned(id, true, now)
        service.sync(id)
        assertTrue(service.resolveConflict(id, keepNotion = false) is NotionSyncResult.Synced)
        assertEquals("Inbox", prop(fake.pages.getValue(pageId), "Status")["select"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test fun lostLocalMappingWithNotionEditsAsksForAdoptionReview() = runBlocking {
        val id = captured("Python SQL tutorial")
        service.sync(id)
        val pageId = db.notionDao().mapping(id)!!.notionPageId
        // Simulate losing the local mapping + journal (e.g. reinstall without restore).
        db.openHelper.writableDatabase.execSQL("DELETE FROM notion_mappings")
        db.openHelper.writableDatabase.execSQL("DELETE FROM notion_write_intents")
        fake.userEdit(pageId, "Status", buildJsonObject { put("select", buildJsonObject { put("name", "Completed") }) })
        assertEquals(NotionSyncResult.Conflict, service.sync(id))
        assertEquals("ADOPTION_REVIEW", db.notionDao().conflict(id)!!.kind)
        assertEquals(1, fake.createCount()) // no duplicate page
        assertEquals("Completed", prop(fake.pages.getValue(pageId), "Status")["select"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    }

    private fun bigItem() = runBlocking {
        captured("big", listOf(CaptureFile("huge.pdf", "application/pdf", null) { ByteArrayInputStream(ByteArray(10_000) { 2 }) }))
    }

    private fun fileUrls(id: String) = runBlocking {
        val page = fake.pages.getValue(db.notionDao().mapping(id)!!.notionPageId)
        prop(page, "Files & media")["files"]!!.jsonArray.mapNotNull { it.jsonObject["external"]?.jsonObject?.get("url")?.jsonPrimitive?.content }
    }

    @Test fun largeFileGetsAnOwnerOnlyDriveLinkWhenTurnedOn() = runBlocking {
        val drive = FakeDrive()
        service = build(maxBytes = 4_096, host = DriveLargeFileHost(drive))
        val id = bigItem()
        assertTrue(service.sync(id) is NotionSyncResult.Synced)
        val stored = drive.files.values.single()
        assertEquals(10_000, stored.bytes.size)
        assertEquals("Personal Knowledge OS Files", drive.folders[stored.parent])
        val link = "https://drive.google.com/file/d/${stored.file.id}/view"
        assertEquals(listOf(link), fileUrls(id))
        val att = db.attachmentDao().forItem(id).single()
        assertEquals(link, att.driveLink); assertNull(att.notionSkipReason)
        // A later edit reuses the link: no second Drive upload, no Notion upload for the big file.
        now += 1_000; db.knowledgeDao().setPinned(id, true, now)
        assertTrue(service.sync(id) is NotionSyncResult.Synced)
        assertEquals(1, drive.files.size)
        assertEquals(0, fake.calls.count { it.path == "/file_uploads" })
    }

    @Test fun largeFileStaysSkippedWhenOffAndIsHostedOnceTurnedOn() = runBlocking {
        service = build(maxBytes = 4_096)
        val id = bigItem()
        assertTrue(service.sync(id) is NotionSyncResult.Synced)
        assertEquals(NotionSyncService.SKIP_TOO_LARGE, db.attachmentDao().forItem(id).single().notionSkipReason)
        assertEquals(emptyList<String>(), fileUrls(id))
        val drive = FakeDrive()
        service = build(maxBytes = 4_096, host = DriveLargeFileHost(drive))
        now += 1_000; db.knowledgeDao().setPinned(id, true, now)
        assertTrue(service.sync(id) is NotionSyncResult.Synced)
        assertEquals(1, fileUrls(id).size)
    }

    @Test fun driveTroubleNeverBlocksOrFakesTheLink() = runBlocking {
        val offline = FakeDrive().apply { failNext = DriveErrorKind.NETWORK }
        service = build(maxBytes = 4_096, host = DriveLargeFileHost(offline))
        val id = bigItem()
        assertTrue(service.sync(id) is NotionSyncResult.Retry)
        assertNull(db.attachmentDao().forItem(id).single().driveLink)
        val signedOut = FakeDrive().apply { authRequired = true }
        service = build(maxBytes = 4_096, host = DriveLargeFileHost(signedOut))
        assertTrue(service.sync(id, 1) is NotionSyncResult.Synced)
        assertEquals(emptyList<String>(), fileUrls(id))
        assertEquals(NotionSyncService.SKIP_TOO_LARGE, db.attachmentDao().forItem(id).single().notionSkipReason)
    }
}
