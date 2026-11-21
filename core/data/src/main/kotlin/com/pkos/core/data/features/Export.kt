package com.pkos.core.data.features

import android.database.sqlite.SQLiteDatabase
import com.pkos.core.data.backup.digestHex
import com.pkos.core.data.db.DB_VERSION
import com.pkos.core.data.db.PkosDatabase
import com.pkos.core.data.db.entity.KnowledgeItemEntity
import com.pkos.core.data.model.PrivacyScope
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class ExportOptions(val includeLocalOnly: Boolean = false, val includeAttachments: Boolean = true)

data class ExportResult(val items: Int, val attachments: Int, val skippedLocalOnly: Int, val files: Int)

data class IntegrityReport(
    val databaseOk: Boolean,
    val databaseMessage: String,
    val foreignKeyProblems: Int,
    val items: Int,
    val attachments: Int,
    val missingFiles: Int,
    val corruptFiles: Int,
    val schemaVersion: Int,
) { val healthy get() = databaseOk && foreignKeyProblems == 0 && missingFiles == 0 && corruptFiles == 0 }

/**
 * Data export (spec 9 #10, R-10): one zip with JSON (everything), CSV (spreadsheet view), Markdown (one file per item),
 * the attachment files and an integrity report with SHA-256 of every exported file. Secrets (Notion token, AI key,
 * recovery key) are not in the database and never exported; local-only items are left out unless the owner opts in.
 */
class ExportService(private val db: PkosDatabase, private val attachmentsRoot: File, private val appVersion: String, private val clock: () -> Long = System::currentTimeMillis) {
    private val dao get() = db.featureDao()

    suspend fun export(out: OutputStream, options: ExportOptions = ExportOptions()): ExportResult {
        val all = dao.liveItems().sortedBy { it.createdAt }
        val items = all.filter { options.includeLocalOnly || it.privacyScope != PrivacyScope.LOCAL_ONLY }
        val ids = items.map { it.id }.toSet()
        val tags = dao.itemTags().groupBy({ it.itemId }, { it.name })
        val topics = dao.topics().associateBy { it.id }
        val cats = db.categoryDao().all().associate { it.id to it.name }
        val atts = db.attachmentDao().all().filter { it.knowledgeId in ids }
        val notion = items.associate { it.id to db.notionDao().mapping(it.id)?.notionUrl }
        val actions = items.associate { it.id to db.openHelper.readableDatabase.query("SELECT text, kind, status, due_at FROM action_items WHERE knowledge_id = ?", arrayOf(it.id)).use { c ->
            buildList { while (c.moveToNext()) add(JSONObject().put("text", c.getString(0)).put("kind", c.getString(1)).put("status", c.getString(2)).put("due_at", if (c.isNull(3)) JSONObject.NULL else c.getLong(3))) }
        } }
        val sums = linkedMapOf<String, String>()
        var files = 0
        ZipOutputStream(out.buffered(1 shl 16)).use { zip ->
            fun put(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
                sums[name] = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }; files++
            }
            // Attachments are streamed (64 KiB buffer) and hashed on the way: a large video never has to fit in memory.
            fun putFile(name: String, file: File) {
                val md = MessageDigest.getInstance("SHA-256")
                zip.putNextEntry(ZipEntry(name))
                file.inputStream().buffered(1 shl 16).use { input ->
                    val buf = ByteArray(1 shl 16)
                    while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n); zip.write(buf, 0, n) }
                }
                zip.closeEntry()
                sums[name] = md.digest().joinToString("") { "%02x".format(it) }; files++
            }
            fun topicPath(i: KnowledgeItemEntity) = listOfNotNull(i.categoryId?.let(cats::get), i.topicId?.let { topics[it]?.name }, i.subtopicId?.let { topics[it]?.name })

            val json = JSONArray(items.map { i ->
                JSONObject().put("id", i.id).put("title", i.title).put("summary", i.summary ?: JSONObject.NULL).put("original_text", i.rawText ?: JSONObject.NULL)
                    .put("note", i.userNote ?: JSONObject.NULL).put("url", i.sourceUrl ?: JSONObject.NULL).put("platform", i.sourcePlatform?.name ?: JSONObject.NULL)
                    .put("type", i.contentType?.name ?: JSONObject.NULL).put("category", i.categoryId?.let(cats::get) ?: JSONObject.NULL)
                    .put("topic_path", JSONArray(topicPath(i))).put("tags", JSONArray(tags[i.id].orEmpty())).put("purpose", i.purpose ?: JSONObject.NULL)
                    .put("status", i.status.name).put("priority", i.priority.name).put("pinned", i.pinned).put("privacy", i.privacyScope.name)
                    .put("confidence", i.confidence ?: JSONObject.NULL).put("field_origins", JSONObject(i.fieldOrigins)).put("created_at", iso(i.createdAt))
                    .put("updated_at", iso(i.updatedAt)).put("notion_url", notion[i.id] ?: JSONObject.NULL).put("actions", JSONArray(actions[i.id].orEmpty()))
                    .put("attachments", JSONArray(atts.filter { it.knowledgeId == i.id }.map { a -> JSONObject().put("file", "attachments/" + File(a.localPath).name).put("name", a.filename ?: JSONObject.NULL).put("sha256", a.sha256).put("size", a.sizeBytes) }))
            })
            put("pkos-export.json", JSONObject().put("format", 1).put("app_version", appVersion).put("schema_version", DB_VERSION)
                .put("exported_at", iso(clock())).put("items", json).toString(2).toByteArray())

            val csv = StringBuilder("\uFEFFid,created_at,title,category,topic,tags,status,priority,type,platform,url,summary,note\n")
            items.forEach { i ->
                csv.append(listOf(i.id, iso(i.createdAt), i.title, i.categoryId?.let(cats::get).orEmpty(), topicPath(i).drop(1).joinToString(" › "),
                    tags[i.id].orEmpty().joinToString("; "), i.status.name, i.priority.name, i.contentType?.name.orEmpty(), i.sourcePlatform?.name.orEmpty(),
                    i.sourceUrl.orEmpty(), i.summary.orEmpty(), i.userNote.orEmpty()).joinToString(",") { csvCell(it) }).append('\n')
            }
            put("items.csv", csv.toString().toByteArray())

            items.forEach { i ->
                val md = buildString {
                    appendLine("# ${i.title}"); appendLine()
                    appendLine("- Saved: ${iso(i.createdAt)}")
                    topicPath(i).takeIf { it.isNotEmpty() }?.let { appendLine("- Topic: ${it.joinToString(" › ")}") }
                    tags[i.id]?.takeIf { it.isNotEmpty() }?.let { appendLine("- Tags: ${it.joinToString(", ") { t -> "#$t" }}") }
                    appendLine("- Status: ${i.status.name} · Priority: ${i.priority.name}")
                    i.sourceUrl?.let { appendLine("- Source: $it") }
                    notion[i.id]?.let { appendLine("- Notion: $it") }
                    i.summary?.let { appendLine(); appendLine("## Summary"); appendLine(it) }
                    i.userNote?.let { appendLine(); appendLine("## My note"); appendLine(it) }
                    i.rawText?.let { appendLine(); appendLine("## Original"); appendLine(it) }
                    actions[i.id]?.takeIf { it.isNotEmpty() }?.let { a -> appendLine(); appendLine("## Actions"); a.forEach { appendLine("- [${if (it.getString("status") == "DONE") "x" else " "}] ${it.getString("text")}") } }
                    atts.filter { it.knowledgeId == i.id }.takeIf { it.isNotEmpty() }?.let { a -> appendLine(); appendLine("## Files"); a.forEach { appendLine("- [${it.filename ?: File(it.localPath).name}](../attachments/${File(it.localPath).name})") } }
                }
                put("markdown/${slug(i.title)}-${i.id.take(8)}.md", md.toByteArray())
            }

            var attCount = 0
            if (options.includeAttachments) atts.distinctBy { it.localPath }.forEach { a ->
                val f = File(attachmentsRoot, a.localPath)
                if (f.exists()) { putFile("attachments/" + f.name, f); attCount++ }
            }
            val report = JSONObject().put("generated_at", iso(clock())).put("items", items.size).put("attachments", attCount)
                .put("skipped_local_only", all.size - items.size).put("secrets_included", false)
                .put("sha256", JSONObject(sums as Map<*, *>))
            zip.putNextEntry(ZipEntry("integrity-report.json")); zip.write(report.toString(2).toByteArray()); zip.closeEntry(); files++
            return ExportResult(items.size, attCount, all.size - items.size, files)
        }
    }

    /** Local integrity report (spec 9 #10): SQLite checks + every attachment present with the recorded SHA-256. */
    suspend fun integrity(dbFile: File?): IntegrityReport {
        val sql = db.openHelper.readableDatabase
        val msg = sql.query("PRAGMA integrity_check").use { it.moveToFirst(); it.getString(0) }
        val fk = sql.query("PRAGMA foreign_key_check").use { it.count }
        val atts = db.attachmentDao().all()
        var missing = 0; var corrupt = 0
        for (a in atts) {
            val f = File(attachmentsRoot, a.localPath)
            if (!f.exists()) missing++ else if (f.digestHex("SHA-256") != a.sha256) corrupt++
        }
        return IntegrityReport(msg == "ok", msg, fk, dao.liveItems().size, atts.size, missing, corrupt, DB_VERSION)
    }

    companion object {
        fun iso(ms: Long): String = Instant.ofEpochMilli(ms).toString()
        fun csvCell(s: String): String {
            val safe = if (s.startsWith("=") || s.startsWith("+") || s.startsWith("-") || s.startsWith("@")) "'$s" else s // no spreadsheet formulas
            return "\"" + safe.replace("\"", "\"\"") + "\""
        }
        fun slug(t: String) = t.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "-").trim('-').take(40).ifEmpty { "item" }
    }
}
