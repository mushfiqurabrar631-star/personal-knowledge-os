package com.pkos.core.data.backup

import com.pkos.core.data.db.DB_VERSION
import com.pkos.core.data.db.PkosDatabase
import com.pkos.core.data.db.entity.BackupSnapshotEntity
import com.pkos.core.data.db.entity.RecoveryMetadataEntity
import com.pkos.core.data.model.BackupStatus
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.random.Random

sealed interface BackupRunResult {
    data class Verified(val snapshot: BackupSnapshotEntity, val quotaWarning: Boolean) : BackupRunResult
    data class Retry(val delayMs: Long, val code: String) : BackupRunResult
    /** Owner must act (connect Drive, set recovery key, free Drive space). Nothing local is touched. */
    data class ActionRequired(val code: String) : BackupRunResult
    data class Failed(val code: String) : BackupRunResult
}

/** Result of setting up a recovery key: the key is returned once for the owner to write down, never stored. */
data class NewRecoveryKey(val recoveryKey: String, val kek: ByteArray, val keyId: String)

/**
 * Encrypted, versioned, verified Google Drive backups (spec 8). Resumable: each step is persisted in
 * backup_snapshots, so a killed worker continues where it stopped. "VERIFIED" is set only after Drive reports the
 * same size and MD5 as the local encrypted archive. A Drive failure never deletes or changes local data.
 */
class BackupService(
    private val db: PkosDatabase,
    private val dbFile: File,
    private val attachmentsRoot: File,
    private val workDir: File,
    private val drive: DriveApi,
    private val kekProvider: () -> ByteArray?,
    private val appVersion: String,
    private val retention: () -> RetentionPolicy = { RetentionPolicy() },
    private val clock: () -> Long = System::currentTimeMillis,
    private val kdfIterations: Int = KdfParams.DEFAULT_ITERATIONS,
    private val uploadChunk: Long = UPLOAD_CHUNK,
) {
    private val dao get() = db.backupDao()

    /** Creates a new recovery key + metadata. The caller stores the returned KEK in the Keystore-backed store. */
    suspend fun createRecoveryKey(): NewRecoveryKey {
        val key = BackupCrypto.newRecoveryKey()
        val params = KdfParams(iterations = kdfIterations, salt = BackupCrypto.randomBytes(16))
        val kek = BackupCrypto.deriveKek(key, params)
        val id = BackupCrypto.keyId(kek)
        dao.upsertRecovery(RecoveryMetadataEntity(id, b64(params.salt), params.toJson().toString(), clock()))
        return NewRecoveryKey(key, kek, id)
    }

    /** Re-derives the KEK from a typed recovery key and checks it against the stored metadata. */
    suspend fun unlockWith(recoveryKey: String): ByteArray? {
        val meta = dao.recovery() ?: return null
        val kek = BackupCrypto.deriveKek(recoveryKey, KdfParams.fromJson(JSONObject(meta.kdfParams)))
        return kek.takeIf { BackupCrypto.keyId(it) == meta.keyId }
    }

    suspend fun quota(): DriveQuota = drive.quota()

    suspend fun run(): BackupRunResult {
        val kek = kekProvider() ?: return BackupRunResult.ActionRequired(NO_RECOVERY_KEY)
        val meta = dao.recovery() ?: return BackupRunResult.ActionRequired(NO_RECOVERY_KEY)
        if (BackupCrypto.keyId(kek) != meta.keyId) return BackupRunResult.ActionRequired(KEY_MISMATCH)
        var s = dao.unfinished() ?: BackupSnapshotEntity(
            id = UUID.randomUUID().toString(), createdAt = clock(), schemaVersion = DB_VERSION, status = BackupStatus.CREATING,
            keyId = meta.keyId, appVersion = appVersion,
        ).also { dao.upsert(it) }
        return try {
            if (s.status == BackupStatus.CREATING || (s.status in setOf(BackupStatus.ENCRYPTED, BackupStatus.UPLOADING) && s.archivePath?.let(::File)?.exists() != true)) {
                s = build(s, kek, meta)
            }
            var quotaWarning = false
            if (s.status == BackupStatus.ENCRYPTED) {
                val q = drive.quota()
                q.limitBytes?.let { limit ->
                    if (q.usageBytes + s.sizeBytes > limit) { save(s.copy(errorCode = DRIVE_FULL)); return BackupRunResult.ActionRequired(DRIVE_FULL) }
                    quotaWarning = q.usageBytes + s.sizeBytes > limit * QUOTA_WARN_RATIO
                }
                val folder = drive.ensureFolder(BACKUP_FOLDER_NAME)
                val session = drive.startResumableUpload(folder, fileName(s), s.sizeBytes, appProperties(s))
                s = save(s.copy(status = BackupStatus.UPLOADING, uploadSession = session, uploadedBytes = 0, errorCode = null))
            }
            if (s.status == BackupStatus.UPLOADING) s = upload(s)
            if (s.status == BackupStatus.UPLOADED) {
                val remote = try { drive.getFile(s.fileId!!) } catch (e: DriveException) {
                    if (e.kind == DriveErrorKind.NOT_FOUND) { save(s.copy(status = BackupStatus.ENCRYPTED, fileId = null, uploadSession = null)); return BackupRunResult.Retry(0, "REMOTE_MISSING") }
                    throw e
                }
                if (remote.size != s.sizeBytes || !remote.md5.equals(s.archiveMd5, ignoreCase = true) || remote.appProperties[PROP_ID] != s.id) {
                    runCatching { drive.delete(remote.id) } // never keep an unverifiable copy
                    s.archivePath?.let { File(it).delete() }
                    save(s.copy(status = BackupStatus.FAILED, errorCode = VERIFY_MISMATCH))
                    return BackupRunResult.Failed(VERIFY_MISMATCH)
                }
                s = save(s.copy(status = BackupStatus.VERIFIED, verifiedAt = clock(), errorCode = null, uploadSession = null))
                s.archivePath?.let { File(it).delete() }
                runCatching { prune() } // retention problems never affect the verified result
                BackupRunResult.Verified(s, quotaWarning)
            } else BackupRunResult.Retry(backoff(s.attempts), "INCOMPLETE")
        } catch (e: DriveException) {
            s = dao.get(s.id) ?: s // keep progress saved by the step that failed (e.g. uploaded bytes)
            val attempts = s.attempts + 1
            save(s.copy(attempts = attempts, errorCode = e.kind.name))
            when (e.kind) {
                DriveErrorKind.AUTH_REQUIRED -> BackupRunResult.ActionRequired(DRIVE_AUTH)
                DriveErrorKind.STORAGE_FULL -> BackupRunResult.ActionRequired(DRIVE_FULL)
                DriveErrorKind.SESSION_EXPIRED -> { save(s.copy(status = BackupStatus.ENCRYPTED, uploadSession = null, uploadedBytes = 0, attempts = attempts)); BackupRunResult.Retry(0, e.kind.name) }
                else -> if (attempts >= MAX_ATTEMPTS && e.kind == DriveErrorKind.OTHER) BackupRunResult.Failed(e.kind.name)
                else BackupRunResult.Retry(e.retryAfterMs ?: backoff(attempts), e.kind.name)
            }
        } catch (e: IOException) {
            s = dao.get(s.id) ?: s
            // Local problem (e.g. phone storage full): drop the partial archive, keep the local library untouched.
            s.archivePath?.let { File(it).delete() }
            save(s.copy(status = BackupStatus.FAILED, errorCode = LOCAL_IO, attempts = s.attempts + 1))
            BackupRunResult.Failed(LOCAL_IO)
        }
    }

    private suspend fun build(s0: BackupSnapshotEntity, kek: ByteArray, meta: RecoveryMetadataEntity): BackupSnapshotEntity {
        val dir = File(workDir, s0.id).apply { deleteRecursively(); mkdirs() }
        val archive = File(workDir, "${s0.id}.pkosbackup")
        try {
            val snap = File(dir, "database.sqlite")
            BackupArchive.snapshotDatabase(db, dbFile, snap)
            val atts = db.attachmentDao().all().map { attachmentsRoot.resolve(it.localPath) to File(it.localPath).name }.distinctBy { it.second }
            val dek = BackupCrypto.randomBytes(32)
            val (wrapNonce, wrapped) = BackupCrypto.wrap(kek, dek, meta.keyId)
            val header = JSONObject()
                .put("format", 1).put("backup_id", s0.id).put("created_at", s0.createdAt).put("cipher", "AES-256-GCM")
                .put("chunk_size", BackupCrypto.CHUNK).put("key_id", meta.keyId).put("kdf", JSONObject(meta.kdfParams))
                .put("wrapped_key", JSONObject().put("nonce", b64(wrapNonce)).put("ciphertext", b64(wrapped)))
                .put("schema_version", DB_VERSION).put("app_version", appVersion)
            val zip = File(dir, "payload.zip")
            val manifest = BackupArchive.writeZip(zip, snap, atts, header, s0.id, s0.createdAt, appVersion)
            snap.delete()
            BackupCrypto.encrypt(zip, archive, header, dek)
            dek.fill(0)
            return save(
                s0.copy(
                    status = BackupStatus.ENCRYPTED, archivePath = archive.path, sizeBytes = archive.length(),
                    archiveMd5 = archive.digestHex("MD5"), checksum = archive.digestHex("SHA-256"),
                    itemCount = manifest.itemCount, attachmentCount = manifest.attachmentCount, schemaVersion = manifest.schemaVersion,
                    errorCode = null,
                ),
            )
        } catch (e: Exception) {
            archive.delete()
            throw if (e is IOException || e is DriveException) e else IOException(e)
        } finally {
            dir.deleteRecursively() // plaintext never outlives the build
        }
    }

    private suspend fun upload(s0: BackupSnapshotEntity): BackupSnapshotEntity {
        var s = s0
        val file = File(s.archivePath!!)
        var step: UploadStep = if (s.uploadedBytes > 0) drive.queryUpload(s.uploadSession!!, s.sizeBytes) else UploadStep.Partial(0)
        while (step is UploadStep.Partial) {
            val offset = step.nextOffset
            if (offset != s.uploadedBytes) s = save(s.copy(uploadedBytes = offset))
            val len = minOf(uploadChunk, s.sizeBytes - offset)
            step = drive.uploadChunk(s.uploadSession!!, file, offset, len, s.sizeBytes)
            if (step is UploadStep.Partial) s = save(s.copy(uploadedBytes = step.nextOffset))
        }
        val done = step as UploadStep.Done
        return save(s.copy(status = BackupStatus.UPLOADED, fileId = done.file.id, uploadedBytes = s.sizeBytes, uploadSession = null))
    }

    /** Applies the retention policy to verified backups; deletes only PKOS's own backup files on Drive. */
    suspend fun prune(): Int {
        val verified = dao.verified()
        val keep = retention().keep(verified.map { it.id to it.createdAt })
        var removed = 0
        for (b in verified.filter { it.id !in keep }) {
            try { b.fileId?.let { drive.delete(it) } } catch (e: DriveException) { if (e.kind != DriveErrorKind.NOT_FOUND) continue }
            save(b.copy(status = BackupStatus.PRUNED)); removed++
        }
        return removed
    }

    /** Backups visible in the Drive folder (for restore on a clean install). */
    suspend fun remoteBackups(): List<DriveFile> {
        val folder = drive.ensureFolder(BACKUP_FOLDER_NAME)
        return drive.listFolder(folder).filter { it.name.endsWith(EXT) && it.appProperties[PROP_ID] != null }.sortedByDescending { it.createdTime }
    }

    private suspend fun save(s: BackupSnapshotEntity): BackupSnapshotEntity { dao.upsert(s); return s }

    private fun fileName(s: BackupSnapshotEntity) =
        "pkos-backup-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(s.createdAt)) + "-" + s.id.take(8) + EXT

    private fun appProperties(s: BackupSnapshotEntity) = mapOf(
        PROP_ID to s.id, "pkos_created" to s.createdAt.toString(), "pkos_schema" to s.schemaVersion.toString(),
        "pkos_key_id" to (s.keyId ?: ""), "pkos_sha256" to (s.checksum ?: ""),
    )

    private fun backoff(attempts: Int): Long {
        val base = (30_000L shl attempts.coerceIn(0, 9)).coerceAtMost(6 * 3_600_000L)
        return base + Random.nextLong(0, base / 5 + 1) // jitter (spec 11.1)
    }

    companion object {
        const val EXT = ".pkosbackup"
        const val PROP_ID = "pkos_backup_id"
        const val UPLOAD_CHUNK = 8L * 1024 * 1024 // multiple of 256 KiB, as Drive requires
        const val QUOTA_WARN_RATIO = 0.9
        const val MAX_ATTEMPTS = 8
        const val NO_RECOVERY_KEY = "NO_RECOVERY_KEY"
        const val KEY_MISMATCH = "KEY_MISMATCH"
        const val DRIVE_AUTH = "DRIVE_AUTH"
        const val DRIVE_FULL = "DRIVE_FULL"
        const val VERIFY_MISMATCH = "VERIFY_MISMATCH"
        const val LOCAL_IO = "LOCAL_IO"
    }
}
