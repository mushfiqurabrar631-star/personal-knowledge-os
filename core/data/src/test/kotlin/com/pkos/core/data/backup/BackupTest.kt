package com.pkos.core.data.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pkos.core.data.capture.AttachmentStore
import com.pkos.core.data.capture.CaptureFile
import com.pkos.core.data.capture.CaptureRepository
import com.pkos.core.data.capture.CaptureRequest
import com.pkos.core.data.capture.CaptureResult
import com.pkos.core.data.db.PkosDatabase
import com.pkos.core.data.db.entity.NotionMappingEntity
import com.pkos.core.data.model.BackupStatus
import com.pkos.core.data.model.CaptureMethod
import com.pkos.core.data.model.SyncState
import com.pkos.core.data.notion.NotionSyncService
import com.pkos.core.notion.FakeNotion
import com.pkos.core.notion.api.NotionClient
import com.pkos.core.notion.api.RateLimiter
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.time.ZoneOffset
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BackupCryptoTest {
    @get:Rule val tmp = TemporaryFolder()
    private val params = KdfParams(iterations = 1_000, salt = BackupCrypto.randomBytes(16))

    private fun sealed(plain: ByteArray, key: String = "ABCD-EFGH"): Pair<File, ByteArray> {
        val kek = BackupCrypto.deriveKek(key, params)
        val dek = BackupCrypto.randomBytes(32)
        val p = tmp.newFile().apply { writeBytes(plain) }
        val out = tmp.newFile()
        BackupCrypto.encrypt(p, out, org.json.JSONObject().put("key_id", BackupCrypto.keyId(kek)), dek)
        return out to dek
    }

    @Test fun roundTripMultiChunkAndEmpty() {
        for (size in listOf(0, 17, BackupCrypto.CHUNK, BackupCrypto.CHUNK * 2 + 12345)) {
            val plain = ByteArray(size) { (it * 31).toByte() }
            val (enc, dek) = sealed(plain)
            val back = tmp.newFile(); BackupCrypto.decrypt(enc, back, dek)
            assertArrayEquals("size $size", plain, back.readBytes())
        }
    }

    @Test fun noPlaintextInArchiveAndNoncesAreUnique() {
        val marker = "SECRET-PLAINTEXT-MARKER".toByteArray()
        val plain = ByteArray(BackupCrypto.CHUNK * 3) { marker[it % marker.size] }
        val (enc, _) = sealed(plain)
        val bytes = enc.readBytes()
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains("SECRET-PLAINTEXT"))
        // collect chunk nonces
        val headerLen = java.nio.ByteBuffer.wrap(bytes, 8, 4).int
        var pos = 12 + headerLen
        val nonces = mutableSetOf<String>()
        while (pos < bytes.size) { val len = java.nio.ByteBuffer.wrap(bytes, pos, 4).int; nonces += bytes.copyOfRange(pos + 4, pos + 16).toList().toString(); pos += 4 + 12 + len + 16 }
        assertEquals(3, nonces.size) // 3 chunks, every nonce distinct
    }

    @Test fun wrongRecoveryKeyIsRejected() {
        val kek = BackupCrypto.deriveKek("RIGHT-KEY1", params)
        val (nonce, wrapped) = BackupCrypto.wrap(kek, BackupCrypto.randomBytes(32), "id")
        try { BackupCrypto.unwrap(BackupCrypto.deriveKek("WRONG-KEY1", params), nonce, wrapped, "id"); fail() } catch (e: WrongRecoveryKeyException) { }
        // typing variations of the right key still work
        BackupCrypto.unwrap(BackupCrypto.deriveKek("right key1", params), nonce, wrapped, "id")
    }

    @Test fun tamperingAndTruncationAreDetected() {
        val (enc, dek) = sealed(ByteArray(BackupCrypto.CHUNK + 500) { 5 })
        val bytes = enc.readBytes()
        val flipped = tmp.newFile().apply { writeBytes(bytes.copyOf().also { it[it.size - 40] = (it[it.size - 40] + 1).toByte() }) }
        val truncated = tmp.newFile().apply { writeBytes(bytes.copyOf(bytes.size - (500 + 16 + 16))) }
        for (f in listOf(flipped, truncated)) {
            try { BackupCrypto.decrypt(f, tmp.newFile(), dek); fail("${f.name} accepted") } catch (e: CorruptBackupException) { }
        }
        try { BackupCrypto.readHeader(tmp.newFile().apply { writeText("hello") }); fail() } catch (e: CorruptBackupException) { }
    }

    @Test fun retentionKeepsDailyWeeklyMonthly() {
        val day = 86_400_000L
        val start = 1_767_225_600_000L // 2026-01-01T00:00Z
        val backups = (0 until 120).map { "b$it" to start + it * day }
        val keep = RetentionPolicy(7, 4, 6).keep(backups, ZoneOffset.UTC)
        assertTrue("b119" in keep)
        assertTrue((113..119).all { "b$it" in keep })
        assertTrue(keep.size in 7..17)
        assertEquals(setOf("b119", "b118"), RetentionPolicy(2, 0, 0).keep(backups, ZoneOffset.UTC))
    }
}

@RunWith(AndroidJUnit4::class)
class BackupRestoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val drive = FakeDrive()
    private var now = 1_800_000_000_000L
    private lateinit var dbName: String
    private lateinit var db: PkosDatabase
    private lateinit var attRoot: File
    private lateinit var work: File
    private var kek: ByteArray? = null
    private lateinit var backup: BackupService
    private lateinit var recoveryKey: String

    @Before fun setUp() = runBlocking {
        dbName = "bk-${UUID.randomUUID()}.db"
        db = PkosDatabase.create(ctx, dbName)
        attRoot = tmp.newFolder("att"); work = tmp.newFolder("work")
        backup = service(db)
        val k = backup.createRecoveryKey(); kek = k.kek; recoveryKey = k.recoveryKey
    }

    @After fun tearDown() { runCatching { db.close() }; ctx.deleteDatabase(dbName) }

    private fun service(d: PkosDatabase, chunk: Long = 256 * 1024) = BackupService(
        d, ctx.getDatabasePath(dbName), attRoot, work, drive, { kek }, "1.0-test", clock = { now }, kdfIterations = 1_000, uploadChunk = chunk,
    )

    private suspend fun capture(text: String, file: ByteArray? = null): String {
        val files = file?.let { listOf(CaptureFile("photo.png", "image/png", null) { ByteArrayInputStream(it) }) }.orEmpty()
        return (CaptureRepository(db, AttachmentStore(attRoot), { Long.MAX_VALUE }, clock = { now }).capture(CaptureRequest(CaptureMethod.SHARE, text = text, files = files)) as CaptureResult.Saved).itemId
    }

    private suspend fun fill(): List<String> {
        val a = capture("Kotlin coroutines guide https://example.com/k")
        val b = capture("রসিদের ছবি", kotlin.random.Random(7).nextBytes(600_000)) // incompressible, so the archive spans several upload chunks
        // Notion identity must survive a restore unchanged.
        db.notionDao().upsertMapping(NotionMappingEntity(a, "page-1", "https://notion.so/page-1", "ds", "ds", "hash", true, now + 10))
        db.notionDao().setSyncState(a, SyncState.NOTION_SYNCED)
        return listOf(a, b)
    }

    @Test fun backupIsEncryptedUploadedAndVerified() = runBlocking {
        fill()
        val r = backup.run() as BackupRunResult.Verified
        val remote = drive.archives().single()
        assertEquals(1, drive.blobs().size) // the photo went up as its own encrypted blob (spec 8.1)
        assertEquals(BACKUP_FOLDER_NAME, drive.folders[remote.parent])
        assertEquals(r.snapshot.archiveMd5, remote.file.md5)
        assertEquals(BackupStatus.VERIFIED, db.backupDao().get(r.snapshot.id)!!.status)
        assertEquals(2, r.snapshot.itemCount); assertEquals(1, r.snapshot.attachmentCount)
        val text = String(remote.bytes, Charsets.ISO_8859_1)
        assertFalse(text.contains("Kotlin coroutines")); assertFalse(text.contains("SQLite format")); assertFalse(text.contains(recoveryKey))
        val blob = drive.blobs().single()
        assertEquals(blob.parent, remote.parent)
        val photo = File(attRoot, db.attachmentDao().all().single().localPath).readBytes()
        assertFalse(blob.bytes.toList().windowed(64, 4096).any { w -> photo.toList().windowed(64, 4096).contains(w) }) // no plaintext
        assertTrue(work.listFiles()!!.isEmpty()) // no plaintext or archive left behind
    }

    @Test fun missingRecoveryKeyOrDriveAuthNeedsOwnerAndChangesNothingLocally() = runBlocking {
        val ids = fill()
        kek = null
        assertEquals(BackupRunResult.ActionRequired(BackupService.NO_RECOVERY_KEY), backup.run())
        kek = backup.unlockWith(recoveryKey)
        drive.authRequired = true
        assertEquals(BackupRunResult.ActionRequired(BackupService.DRIVE_AUTH), backup.run())
        assertEquals(2, db.knowledgeDao().countAll())
        assertTrue(ids.all { db.knowledgeDao().get(it) != null })
        assertTrue(drive.files.isEmpty())
    }

    /** The archive itself must be big enough to span chunks: an item with a lot of incompressible text. */
    private suspend fun fillBigText() { val r = kotlin.random.Random(5); capture(String(CharArray(700_000) { "abcdefghijklmnopqrstuvwxyz0123456789"[r.nextInt(36)] })) }

    @Test fun interruptedUploadResumesWithoutStartingOver() = runBlocking {
        fillBigText()
        drive.failChunkAfter = 1
        val first = backup.run()
        assertTrue(first is BackupRunResult.Retry)
        val partial = db.backupDao().unfinished()!!
        assertEquals(BackupStatus.UPLOADING, partial.status)
        assertTrue(partial.uploadedBytes > 0)
        val starts = drive.calls.count { it == "start" }
        val r = backup.run() as BackupRunResult.Verified
        assertEquals(starts, drive.calls.count { it == "start" }) // same session resumed
        assertTrue("query" in drive.calls)
        assertEquals(partial.id, r.snapshot.id)
        assertEquals(1, drive.archives().size)
    }

    @Test fun interruptedAttachmentUploadRestartsOnlyThatBlob() = runBlocking {
        fill()
        drive.failChunkAfter = 1 // fails inside the 600 KB photo blob
        assertTrue(backup.run() is BackupRunResult.Retry)
        assertEquals(BackupStatus.ENCRYPTED, db.backupDao().unfinished()!!.status) // archive not started yet
        assertTrue(drive.files.isEmpty())
        assertTrue(backup.run() is BackupRunResult.Verified)
        assertEquals(1, drive.archives().size); assertEquals(1, drive.blobs().size)
    }

    @Test fun expiredSessionRestartsUploadSafely() = runBlocking {
        fill()
        drive.expireSessions = true
        assertTrue(backup.run() is BackupRunResult.Retry)
        drive.expireSessions = false
        assertTrue(backup.run() is BackupRunResult.Verified)
        assertEquals(1, drive.archives().size); assertEquals(1, drive.blobs().size)
    }

    @Test fun mismatchAfterUploadIsNeverMarkedVerified() = runBlocking {
        fill()
        drive.corruptStoredMd5 = true; drive.corruptOnly = BackupService.EXT // the archive's check fails, the blob's passes
        assertEquals(BackupRunResult.Failed(BackupService.VERIFY_MISMATCH), backup.run())
        assertTrue(db.backupDao().verified().isEmpty())
        assertTrue(drive.archives().isEmpty()) // the unverifiable copy was removed
        assertEquals(2, db.knowledgeDao().countAll())
    }

    @Test fun attachmentBlobMismatchIsNeverAccepted() = runBlocking {
        fill()
        drive.corruptStoredMd5 = true; drive.corruptOnly = BackupBlobs.EXT
        assertTrue(backup.run() is BackupRunResult.Retry)
        assertTrue(drive.files.isEmpty()) // bad blob deleted, archive never uploaded
        assertTrue(db.backupDao().verified().isEmpty())
        drive.corruptStoredMd5 = false
        assertTrue(backup.run() is BackupRunResult.Verified)
    }

    @Test fun unchangedAttachmentsAreUploadedOnlyOnce() = runBlocking {
        fill()
        assertTrue(backup.run() is BackupRunResult.Verified)
        val firstBlobBytes = drive.blobs().sumOf { it.bytes.size }
        now += 86_400_000L; drive.clock = now
        capture("one more note")
        val chunksBefore = drive.calls.count { it == "chunk" }
        assertTrue(backup.run() is BackupRunResult.Verified)
        assertEquals(2, drive.archives().size); assertEquals(1, drive.blobs().size) // the photo was not sent again
        assertTrue(drive.calls.count { it == "chunk" } - chunksBefore <= 2) // only the small archive
        assertTrue(firstBlobBytes > 600_000)
    }

    @Test fun blobsLeaveDriveOnlyWhenNoKeptBackupNeedsThem() = runBlocking {
        fill()
        assertTrue(backup.run() is BackupRunResult.Verified)
        // The photo is deleted on the phone; later backups no longer reference its blob.
        val att = db.attachmentDao().all().single()
        db.openHelper.writableDatabase.execSQL("DELETE FROM attachments")
        repeat(3) { now += 86_400_000L; drive.clock = now; capture("day $it"); assertTrue(backup.run() is BackupRunResult.Verified) }
        backup = BackupService(db, ctx.getDatabasePath(dbName), attRoot, work, drive, { kek }, "1.0-test", retention = { RetentionPolicy(3, 0, 0) }, clock = { now }, kdfIterations = 1_000)
        backup.prune()
        assertEquals(3, drive.archives().size)
        assertTrue(drive.blobs().isEmpty()) // only the pruned backup pointed to it
        assertTrue(File(attRoot, att.localPath).exists()) // local files are never touched by pruning
    }

    @Test fun fullDriveIsReportedBeforeUploading() = runBlocking {
        fill()
        drive.quota = DriveQuota(1_000, 900)
        assertEquals(BackupRunResult.ActionRequired(BackupService.DRIVE_FULL), backup.run())
        assertFalse("start" in drive.calls)
        drive.quota = DriveQuota(Long.MAX_VALUE / 2, 0)
        assertTrue(backup.run() is BackupRunResult.Verified)
    }

    @Test fun retentionPrunesOnlyOldVerifiedBackups() = runBlocking {
        fill()
        repeat(10) { now += 86_400_000L; drive.clock = now; assertTrue(backup.run() is BackupRunResult.Verified) }
        backup = BackupService(db, ctx.getDatabasePath(dbName), attRoot, work, drive, { kek }, "1.0-test", retention = { RetentionPolicy(3, 0, 0) }, clock = { now }, kdfIterations = 1_000)
        backup.prune()
        assertEquals(3, db.backupDao().verified().size)
        assertEquals(3, drive.archives().size)
        assertEquals(1, drive.blobs().size) // the photo blob is shared by all kept backups
    }

    // ---------- restore ----------

    private suspend fun backedUp(): DriveFile {
        fill(); backup.run() as BackupRunResult.Verified
        return backup.remoteBackups().single()
    }

    private fun restoreOn(name: String, att: File, w: File) = RestoreService(ctx.getDatabasePath(name), att, w, drive, clock = { now })

    @Test fun cleanInstallRestoreBringsBackEverythingWithoutNotionResend() = runBlocking {
        val remote = backedUp()
        val originalAtt = db.attachmentDao().all().single()
        // A brand-new phone: no database, no attachments, no stored key.
        val freshDb = "fresh-${UUID.randomUUID()}.db"
        val freshAtt = tmp.newFolder("fresh-att"); val freshWork = tmp.newFolder("fresh-work")
        val restore = restoreOn(freshDb, freshAtt, freshWork)
        val preview = restore.prepareFromDrive(remote, recoveryKey.lowercase().replace("-", " "), storedKek = null)
        assertEquals(2, preview.manifest.itemCount); assertEquals(1, preview.manifest.attachmentCount)
        assertEquals(0, preview.localItems)
        assertTrue(restore.apply(preview) {} is RestoreResult.Applied)
        assertEquals("APPLIED", restore.finishPending(openedOk = true))
        val restored = PkosDatabase.create(ctx, freshDb)
        try {
            assertEquals(2, restored.knowledgeDao().countAll())
            val att = restored.attachmentDao().all().single()
            assertEquals(originalAtt.sha256, File(freshAtt, att.localPath).digestHex("SHA-256"))
            val a = restored.notionDao().mapping(restored.notionDao().itemsNeedingSync().firstOrNull() ?: "x")
            assertEquals(null, a)
            // Notion identity and states preserved; nothing needs re-sending.
            val mapped = restored.knowledgeDao().get(db.notionDao().let { runBlocking { db.knowledgeDao().countAll() }; fillIdWithMapping(restored) })!!
            assertEquals(SyncState.NOTION_SYNCED, mapped.syncState)
            val svc = NotionSyncService(restored, NotionClient(FakeNotion(), { "t" }, RateLimiter.none()), { File(freshAtt, it) })
            assertFalse(svc.itemsNeedingSync().contains(mapped.id))
            // The backup that produced this restore is recorded as verified, not resumed.
            assertEquals(BackupStatus.VERIFIED, restored.backupDao().verified().single().status)
            assertEquals(null, restored.backupDao().unfinished())
            assertEquals(preview.keyId, restored.backupDao().recovery()!!.keyId)
        } finally { restored.close(); ctx.deleteDatabase(freshDb) }
    }

    private suspend fun fillIdWithMapping(d: PkosDatabase): String =
        d.knowledgeDao().let { dao -> d.openHelper.readableDatabase.query("SELECT knowledge_id FROM notion_mappings").use { it.moveToFirst(); it.getString(0) } }

    @Test fun wrongRecoveryKeyCannotRestore() = runBlocking {
        val remote = backedUp()
        val r = restoreOn("w-${UUID.randomUUID()}.db", tmp.newFolder(), tmp.newFolder())
        try { r.prepareFromDrive(remote, "AAAA-BBBB-CCCC-DDDD-EEEE-FFFF-GGGG", null); fail() } catch (e: WrongRecoveryKeyException) { }
    }

    @Test fun corruptedBackupIsRejected() = runBlocking {
        val remote = backedUp()
        drive.tamper(remote.id, drive.files.getValue(remote.id).bytes.size / 2)
        val r = restoreOn("c-${UUID.randomUUID()}.db", tmp.newFolder(), tmp.newFolder())
        try { r.prepareFromDrive(remote, recoveryKey, null); fail() } catch (e: CorruptBackupException) { }
    }

    @Test fun checksumMismatchInsideArchiveIsRejected() = runBlocking {
        val remote = backedUp()
        val w = tmp.newFolder()
        val r = restoreOn("s-${UUID.randomUUID()}.db", tmp.newFolder(), w)
        val p = r.prepareFromDrive(remote, recoveryKey, null)
        val att = File(p.stagingDir, p.manifest.attachments.single().path)
        att.writeBytes(att.readBytes().also { it[10] = (it[10] + 1).toByte() })
        try { BackupArchive.verifyChecksums(p.stagingDir, p.manifest); fail() } catch (e: CorruptBackupException) { }
        val db2 = File(p.stagingDir, "database.sqlite"); db2.appendBytes(byteArrayOf(1))
        try { BackupArchive.verifyChecksums(p.stagingDir, p.manifest.copy(attachments = emptyList())); fail() } catch (e: CorruptBackupException) { }
    }

    @Test fun tamperedOrMissingAttachmentBlobStopsTheRestore() = runBlocking {
        val remote = backedUp()
        val blob = drive.blobs().single()
        drive.tamper(blob.file.id, blob.bytes.size / 2)
        try { restoreOn("t-${UUID.randomUUID()}.db", tmp.newFolder(), tmp.newFolder()).prepareFromDrive(remote, recoveryKey, null); fail() } catch (e: CorruptBackupException) { }
        drive.files.remove(blob.file.id)
        try { restoreOn("m-${UUID.randomUUID()}.db", tmp.newFolder(), tmp.newFolder()).prepareFromDrive(remote, recoveryKey, null); fail() } catch (e: CorruptBackupException) { }
        assertEquals(2, db.knowledgeDao().countAll()) // nothing local changed
    }

    @Test fun newerBackupSchemaIsRefused() {
        val f = tmp.newFile()
        val out = tmp.newFile()
        BackupCrypto.encrypt(f, out, org.json.JSONObject().put("schema_version", 999).put("key_id", "x"), BackupCrypto.randomBytes(32))
        try { restoreOn("n.db", tmp.newFolder(), tmp.newFolder()).prepareFromArchive(out, recoveryKey, null); fail() } catch (e: IncompatibleBackupException) { }
    }

    @Test fun previewShowsWhatOnlyExistsOnThisPhoneAndFailedRestoreRollsBack() = runBlocking {
        val remote = backedUp()
        now += 60_000
        capture("written after the backup")
        val before = db.knowledgeDao().countAll()
        val restore = RestoreService(ctx.getDatabasePath(dbName), attRoot, work, drive, clock = { now })
        val preview = restore.prepareFromDrive(remote, null, storedKek = kek)
        assertEquals(1, preview.onlyOnPhone)
        // Break the staged database after verification → apply must fail and put the original back.
        File(preview.stagingDir, "database.sqlite").writeBytes(ByteArray(4096) { 7 })
        val result = restore.apply(preview) { db.close() }
        assertTrue(result is RestoreResult.RolledBack)
        assertFalse(restore.markerFile.exists())
        val reopened = PkosDatabase.create(ctx, dbName)
        try { assertEquals(before, reopened.knowledgeDao().countAll()) } finally { reopened.close() }
    }

    @Test fun restoreThatTheAppCannotOpenIsRolledBackOnNextStart() = runBlocking {
        val remote = backedUp()
        capture("only local")
        val before = db.knowledgeDao().countAll()
        val restore = RestoreService(ctx.getDatabasePath(dbName), attRoot, work, drive, clock = { now })
        val preview = restore.prepareFromDrive(remote, recoveryKey, null)
        assertTrue(restore.apply(preview) { db.close() } is RestoreResult.Applied)
        assertEquals("ROLLED_BACK", restore.finishPending(openedOk = false))
        val reopened = PkosDatabase.create(ctx, dbName)
        try { assertEquals(before, reopened.knowledgeDao().countAll()) } finally { reopened.close() }
        assertNotEquals(0, before)
    }
}
