package com.pkos.core.data.backup

import android.database.sqlite.SQLiteDatabase
import com.pkos.core.data.db.DB_VERSION
import org.json.JSONObject
import java.io.File

/** Thrown when a backup was made by a newer app (its schema is unknown here). */
class IncompatibleBackupException(val backupSchema: Int) : Exception("backup schema $backupSchema > app schema $DB_VERSION")

/** Restore preview (spec 8.4 #29): what the backup holds and what differs from this phone. */
data class RestorePreview(
    val stagingDir: File,
    val manifest: BackupManifest,
    val kek: ByteArray,
    val keyId: String,
    val localItems: Int,
    /** Items on this phone that are not in the backup (kept only in the pre-restore safety snapshot). */
    val onlyOnPhone: Int,
    /** Items present in both with different content (the backup version will be active). */
    val changedSinceBackup: Int,
)

sealed interface RestoreResult {
    data class Applied(val safetyDir: File) : RestoreResult
    data class RolledBack(val reason: String) : RestoreResult
}

/**
 * Restore (spec 8.4 #28–30): download into a staging area → derive key → authenticate/decrypt → unzip → verify every
 * checksum, schema version and SQLite integrity → preview → only on explicit confirmation replace the database.
 * The current database is first moved into a pre-restore safety snapshot; any failure puts it back.
 * Attachments are content-addressed and only ever added, never deleted.
 */
class RestoreService(
    private val dbFile: File,
    private val attachmentsRoot: File,
    private val workDir: File,
    private val drive: DriveApi?,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val staging get() = File(workDir, "restore-staging")
    private val safetyRoot get() = File(workDir, "restore-safety")
    val markerFile get() = File(workDir, "restore-pending.json")

    suspend fun prepareFromDrive(file: DriveFile, recoveryKey: String?, storedKek: ByteArray?): RestorePreview {
        val dir = staging.apply { deleteRecursively(); mkdirs() }
        val archive = File(dir, "archive$EXT")
        (drive ?: error("Drive not connected")).download(file.id, archive)
        return prepareFromArchive(archive, recoveryKey, storedKek)
    }

    fun prepareFromArchive(archive: File, recoveryKey: String?, storedKek: ByteArray?): RestorePreview {
        val dir = staging.apply { mkdirs() }
        val header = BackupCrypto.readHeader(archive)
        val headerSchema = header.optInt("schema_version", Int.MAX_VALUE)
        if (headerSchema > DB_VERSION) throw IncompatibleBackupException(headerSchema)
        val keyId = header.getString("key_id")
        val kek = when {
            storedKek != null && BackupCrypto.keyId(storedKek) == keyId -> storedKek
            recoveryKey != null -> BackupCrypto.deriveKek(recoveryKey, KdfParams.fromJson(header.getJSONObject("kdf")))
            else -> throw WrongRecoveryKeyException()
        }
        if (BackupCrypto.keyId(kek) != keyId) throw WrongRecoveryKeyException()
        val w = header.getJSONObject("wrapped_key")
        val dek = BackupCrypto.unwrap(kek, unb64(w.getString("nonce")), unb64(w.getString("ciphertext")), keyId)
        val zip = File(dir, "payload.zip")
        val tree = File(dir, "tree").apply { deleteRecursively() }
        try {
            BackupCrypto.decrypt(archive, zip, dek)
        } finally { dek.fill(0) }
        if (archive.parentFile == dir) archive.delete()
        val manifest = try { BackupArchive.extractAndVerify(zip, tree) } finally { zip.delete() }
        if (manifest.schemaVersion > DB_VERSION) throw IncompatibleBackupException(manifest.schemaVersion)
        val (schema, items, _) = BackupArchive.checkDatabase(File(tree, "database.sqlite"))
        if (schema != manifest.schemaVersion || items != manifest.itemCount) throw CorruptBackupException("manifest does not match database")
        return compare(tree, manifest, kek, keyId)
    }

    private fun compare(tree: File, manifest: BackupManifest, kek: ByteArray, keyId: String): RestorePreview {
        fun ids(f: File): Map<String, Long> = if (!f.exists()) emptyMap() else
            SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY).use { d ->
                d.rawQuery("SELECT id, updated_at FROM knowledge_items WHERE deleted_at IS NULL", null).use { c ->
                    buildMap { while (c.moveToNext()) put(c.getString(0), c.getLong(1)) }
                }
            }
        val local = runCatching { ids(dbFile) }.getOrDefault(emptyMap())
        val backup = ids(File(tree, "database.sqlite"))
        return RestorePreview(
            tree, manifest, kek, keyId, local.size,
            onlyOnPhone = local.keys.count { it !in backup },
            changedSinceBackup = local.count { (id, at) -> backup[id]?.let { it != at } == true },
        )
    }

    /**
     * Replaces the database with the staged one. Call only after the owner confirmed the preview and after the Room
     * database was closed ([closeDatabase]). On any failure the original files are put back.
     */
    suspend fun apply(preview: RestorePreview, closeDatabase: suspend () -> Unit): RestoreResult {
        val staged = File(preview.stagingDir, "database.sqlite")
        if (!staged.exists()) return RestoreResult.RolledBack("STAGING_MISSING")
        closeDatabase()
        val safety = File(safetyRoot, clock().toString()).apply { mkdirs() }
        val dbFiles = listOf(dbFile, File(dbFile.path + "-wal"), File(dbFile.path + "-shm"), File(dbFile.path + "-journal"))
        val added = mutableListOf<File>()
        markerFile.writeText(JSONObject().put("safety", safety.path).put("backup_id", preview.manifest.backupId).put("stage", "SWAPPING").toString())
        try {
            for (f in dbFiles) if (f.exists()) moveOrCopy(f, File(safety, f.name))
            dbFile.parentFile?.mkdirs()
            val tmp = File(dbFile.path + ".restore-tmp")
            staged.copyTo(tmp, overwrite = true)
            if (!tmp.renameTo(dbFile)) throw java.io.IOException("could not place restored database")
            fixRestoredState(preview)
            // Attachments: add the ones missing on this phone (content-addressed: same name = same bytes).
            SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { d ->
                d.rawQuery("SELECT local_path FROM attachments", null).use { c ->
                    while (c.moveToNext()) {
                        val rel = c.getString(0)
                        val target = File(attachmentsRoot, rel)
                        if (target.exists()) continue
                        val src = File(preview.stagingDir, "attachments/" + File(rel).name)
                        if (!src.exists()) continue
                        target.parentFile?.mkdirs(); src.copyTo(target); added += target
                    }
                }
            }
            BackupArchive.checkDatabase(dbFile)
            markerFile.writeText(JSONObject().put("safety", safety.path).put("backup_id", preview.manifest.backupId).put("stage", "APPLIED").toString())
            preview.stagingDir.parentFile?.deleteRecursively()
            pruneSafety(keep = 2)
            return RestoreResult.Applied(safety)
        } catch (e: Exception) {
            rollback(safety, added)
            return RestoreResult.RolledBack(e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""))
        }
    }

    /** Puts the pre-restore database back. Used on failure and when the restored DB cannot be opened by the app. */
    fun rollback(safety: File, added: List<File> = emptyList()) {
        for (n in listOf("", "-wal", "-shm", "-journal")) File(dbFile.path + n).delete()
        safety.listFiles()?.forEach { f -> moveOrCopy(f, File(dbFile.parentFile, f.name)) }
        added.forEach { it.delete() }
        markerFile.delete()
    }

    /** Called on the next app start after [apply]: confirm (the app opened the DB) or roll back. */
    fun finishPending(openedOk: Boolean): String? {
        if (!markerFile.exists()) return null
        val m = runCatching { JSONObject(markerFile.readText()) }.getOrNull() ?: run { markerFile.delete(); return null }
        val safety = File(m.getString("safety"))
        return if (openedOk && m.optString("stage") == "APPLIED") { markerFile.delete(); "APPLIED" }
        else { rollback(safety); "ROLLED_BACK" }
    }

    /**
     * The snapshot inside a backup was taken while that backup was being created; mark it as the verified backup it
     * became, and mark any other unfinished backup rows as interrupted so they are never resumed with stale paths.
     */
    private fun fixRestoredState(preview: RestorePreview) {
        SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE).use { d ->
            d.execSQL(
                "UPDATE backup_snapshots SET status = 'FAILED', error_code = 'INTERRUPTED_BY_RESTORE', archive_path = NULL, upload_session = NULL " +
                    "WHERE status IN ('CREATING','ENCRYPTED','UPLOADING','UPLOADED') AND id != ?",
                arrayOf<Any>(preview.manifest.backupId),
            )
            d.execSQL(
                "UPDATE backup_snapshots SET status = 'VERIFIED', archive_path = NULL, upload_session = NULL, verified_at = ? WHERE id = ?",
                arrayOf<Any>(clock(), preview.manifest.backupId),
            )
        }
    }

    private fun pruneSafety(keep: Int) {
        safetyRoot.listFiles()?.sortedByDescending { it.name }?.drop(keep)?.forEach { it.deleteRecursively() }
    }

    private fun moveOrCopy(from: File, to: File) {
        to.parentFile?.mkdirs()
        if (!from.renameTo(to)) { from.copyTo(to, overwrite = true); from.delete() }
    }

    fun discardStaging() { staging.deleteRecursively() }

    private companion object { const val EXT = BackupService.EXT }
}
