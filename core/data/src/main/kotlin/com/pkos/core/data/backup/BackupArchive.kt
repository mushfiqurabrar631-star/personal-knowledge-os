package com.pkos.core.data.backup

import android.database.sqlite.SQLiteDatabase
import androidx.room.withTransaction
import com.pkos.core.data.db.PkosDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** What a backup contains; written as backup/manifest.json (spec 8.2). */
data class BackupManifest(
    val backupId: String,
    val createdAt: Long,
    val appVersion: String,
    val schemaVersion: Int,
    val itemCount: Int,
    val attachmentCount: Int,
    val databaseSha256: String,
    val attachments: List<Entry>,
) {
    data class Entry(val path: String, val sha256: String, val size: Long)

    fun toJson(): JSONObject = JSONObject()
        .put("format", 1).put("backup_id", backupId).put("created_at", createdAt).put("app_version", appVersion)
        .put("schema_version", schemaVersion).put("item_count", itemCount).put("attachment_count", attachmentCount)
        .put("database", JSONObject().put("path", "database.sqlite").put("sha256", databaseSha256))
        .put("attachments", JSONArray(attachments.map { JSONObject().put("path", it.path).put("sha256", it.sha256).put("size", it.size) }))

    companion object {
        fun fromJson(o: JSONObject): BackupManifest {
            val a = o.getJSONArray("attachments")
            return BackupManifest(
                o.getString("backup_id"), o.getLong("created_at"), o.getString("app_version"), o.getInt("schema_version"),
                o.getInt("item_count"), o.getInt("attachment_count"), o.getJSONObject("database").getString("sha256"),
                (0 until a.length()).map { a.getJSONObject(it).let { e -> Entry(e.getString("path"), e.getString("sha256"), e.getLong("size")) } },
            )
        }
    }
}

/**
 * Builds and opens the plaintext backup tree (only ever inside app-private storage; it is zipped and encrypted
 * before anything leaves the phone).
 */
object BackupArchive {
    const val ROOT = "backup/"

    /**
     * Consistent DB snapshot (spec 8.4 #24): inside a Room write transaction no other writer can commit, so the
     * main file + WAL are stable while copied; the copy is then checkpointed into one self-contained file.
     */
    suspend fun snapshotDatabase(db: PkosDatabase, dbFile: File, out: File) {
        out.parentFile?.mkdirs()
        val wal = File(dbFile.path + "-wal")
        val walCopy = File(out.path + "-wal")
        db.withTransaction {
            db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM knowledge_items").use { it.moveToFirst() }
            dbFile.copyTo(out, overwrite = true)
            if (wal.exists()) wal.copyTo(walCopy, overwrite = true) else walCopy.delete()
        }
        SQLiteDatabase.openDatabase(out.path, null, SQLiteDatabase.OPEN_READWRITE).use { s ->
            s.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
            s.rawQuery("PRAGMA journal_mode=DELETE", null).use { it.moveToFirst() }
        }
        walCopy.delete(); File(out.path + "-shm").delete()
        checkDatabase(out)
    }

    /** integrity_check + user_version; returns (schemaVersion, itemCount, attachmentCount). */
    // Read-write open: since schema v6 the file holds an FTS4 table, and SQLite's integrity_check needs write access
    // to validate an FTS index (it changes nothing). Only snapshot/staging copies, or the restored file while Room has it closed, are opened here.
    fun checkDatabase(file: File): Triple<Int, Int, Int> =
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { s ->
            val ok = s.rawQuery("PRAGMA integrity_check", null).use { it.moveToFirst(); it.getString(0) }
            if (ok != "ok") throw CorruptBackupException("database integrity_check: $ok")
            val version = s.rawQuery("PRAGMA user_version", null).use { it.moveToFirst(); it.getInt(0) }
            val items = s.rawQuery("SELECT COUNT(*) FROM knowledge_items WHERE deleted_at IS NULL", null).use { it.moveToFirst(); it.getInt(0) }
            val atts = s.rawQuery("SELECT COUNT(*) FROM attachments", null).use { it.moveToFirst(); it.getInt(0) }
            Triple(version, items, atts)
        }

    /** Writes backup/ (spec 8.2 layout) as a zip at [zipOut]. Attachment files are hashed as they are added. */
    fun writeZip(
        zipOut: File,
        databaseSnapshot: File,
        attachments: List<Pair<File, String>>, // file, archive name "<sha256>.<ext>"
        header: JSONObject,
        backupId: String,
        createdAt: Long,
        appVersion: String,
    ): BackupManifest {
        val (schema, items, _) = checkDatabase(databaseSnapshot)
        val dbSha = databaseSnapshot.digestHex("SHA-256")
        val entries = mutableListOf<BackupManifest.Entry>()
        ZipOutputStream(zipOut.outputStream().buffered(1 shl 16)).use { zip ->
            fun put(name: String, file: File) {
                zip.putNextEntry(ZipEntry(ROOT + name)); file.inputStream().use { it.copyTo(zip, 1 shl 16) }; zip.closeEntry()
            }
            fun putText(name: String, text: String) { zip.putNextEntry(ZipEntry(ROOT + name)); zip.writeString(text); zip.closeEntry() }
            put("database.sqlite", databaseSnapshot)
            for ((file, name) in attachments) {
                if (!file.exists()) continue // missing local file: recorded by absence, never invented
                val sha = file.digestHex("SHA-256")
                entries += BackupManifest.Entry("attachments/$name", sha, file.length())
                put("attachments/$name", file)
            }
            val manifest = BackupManifest(backupId, createdAt, appVersion, schema, items, entries.size, dbSha, entries)
            putText("metadata/schema-version.json", JSONObject().put("schema_version", schema).toString())
            putText("metadata/app-version.json", JSONObject().put("app_version", appVersion).toString())
            putText(
                "integrity/checksums.sha256",
                (listOf("$dbSha  database.sqlite") + entries.map { "${it.sha256}  ${it.path}" }).joinToString("\n", postfix = "\n"),
            )
            putText("encryption/header.json", header.toString())
            putText("manifest.json", manifest.toJson().toString(2))
            return manifest
        }
    }

    /** Unzips into [dir] (rejecting path traversal) and verifies every checksum (spec 8.4 #28). */
    fun extractAndVerify(zip: File, dir: File): BackupManifest {
        dir.mkdirs()
        val root = dir.canonicalFile
        ZipInputStream(zip.inputStream().buffered(1 shl 16)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (!e.name.startsWith(ROOT) || e.isDirectory) continue
                val target = File(root, e.name.removePrefix(ROOT)).canonicalFile
                if (!target.path.startsWith(root.path + File.separator)) throw CorruptBackupException("unsafe path ${e.name}")
                target.parentFile?.mkdirs()
                target.outputStream().use { z.copyTo(it, 1 shl 16) }
            }
        }
        val manifestFile = File(dir, "manifest.json")
        if (!manifestFile.exists()) throw CorruptBackupException("manifest missing")
        val manifest = BackupManifest.fromJson(JSONObject(manifestFile.readText()))
        verifyChecksums(dir, manifest)
        return manifest
    }

    fun verifyChecksums(dir: File, manifest: BackupManifest) {
        val sums = File(dir, "integrity/checksums.sha256").takeIf { it.exists() }?.readLines()
            ?.filter { it.isNotBlank() }?.associate { it.substringAfter("  ") to it.substringBefore("  ") }
            ?: throw CorruptBackupException("checksums missing")
        val db = File(dir, "database.sqlite")
        if (!db.exists() || db.digestHex("SHA-256") != manifest.databaseSha256 || sums["database.sqlite"] != manifest.databaseSha256) {
            throw CorruptBackupException("database checksum mismatch")
        }
        for (e in manifest.attachments) {
            val f = File(dir, e.path)
            if (!f.exists() || f.length() != e.size || f.digestHex("SHA-256") != e.sha256 || sums[e.path] != e.sha256) {
                throw CorruptBackupException("attachment checksum mismatch: ${e.path}")
            }
        }
    }
}
