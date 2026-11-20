package com.pkos.core.data.capture

import androidx.room.withTransaction
import com.pkos.core.data.db.PkosDatabase
import com.pkos.core.data.db.entity.AttachmentEntity
import com.pkos.core.data.db.entity.CaptureSourceEntity
import com.pkos.core.data.db.entity.KnowledgeItemEntity
import com.pkos.core.data.db.entity.ProcessingJobEntity
import com.pkos.core.data.model.CaptureMethod
import com.pkos.core.data.model.ExtractionStatus
import com.pkos.core.data.model.JobState
import com.pkos.core.data.model.PrivacyScope
import com.pkos.core.data.model.ProcessingJobType
import com.pkos.core.data.model.ProcessingState
import com.pkos.core.data.model.SyncState
import com.pkos.core.notion.url.UrlCanonicalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

/** One file in a capture. `open` is called once, on a background thread, while the source grant is valid. */
class CaptureFile(
    val displayName: String?,
    val mimeType: String?,
    val sourceUri: String?,
    val open: () -> InputStream,
)

data class CaptureRequest(
    val method: CaptureMethod,
    val text: String? = null,
    val subject: String? = null,
    val files: List<CaptureFile> = emptyList(),
    /** Package name of the sharing app, when Android tells us. */
    val sourceApp: String? = null,
    val note: String? = null,
)

enum class CaptureFailure { EMPTY, UNREADABLE, TOO_LARGE, LOW_STORAGE, DATABASE }

sealed interface CaptureResult {
    data class Saved(val itemId: String, val title: String, val duplicateOfId: String?, val fileCount: Int) : CaptureResult
    data class Failed(val reason: CaptureFailure, val detail: String = "") : CaptureResult
}

/**
 * "Save first, process later" (spec 5.2): files are copied into app storage, then ONE database transaction
 * writes the item, its capture provenance, attachments and queued processing jobs. Nothing needs the network.
 * If anything fails, no partial row is left and newly copied files are removed again.
 */
class CaptureRepository(
    private val db: PkosDatabase,
    private val store: AttachmentStore,
    private val freeBytes: () -> Long,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun capture(request: CaptureRequest, privacy: PrivacyScope = PrivacyScope.NORMAL): CaptureResult = withContext(Dispatchers.IO) {
        val text = request.text?.trim()?.takeIf { it.isNotEmpty() }
        if (text == null && request.files.isEmpty() && request.note.isNullOrBlank()) return@withContext CaptureResult.Failed(CaptureFailure.EMPTY)
        val needed = if (request.files.isEmpty()) MIN_FREE_TEXT else MIN_FREE_FILES
        if (freeBytes() < needed) return@withContext CaptureResult.Failed(CaptureFailure.LOW_STORAGE)

        // 1. Copy files (streamed + hashed) before touching the database.
        val stored = mutableListOf<Pair<CaptureFile, AttachmentStore.Stored>>()
        try {
            for (f in request.files) stored += f to store.store(f.open(), extensionOf(f))
        } catch (e: AttachmentStore.TooLargeException) {
            cleanup(stored); return@withContext CaptureResult.Failed(CaptureFailure.TOO_LARGE, "limit ${e.limit}")
        } catch (e: IOException) {
            cleanup(stored); return@withContext CaptureResult.Failed(CaptureFailure.UNREADABLE, e.javaClass.simpleName)
        } catch (e: SecurityException) {
            cleanup(stored); return@withContext CaptureResult.Failed(CaptureFailure.UNREADABLE, "permission")
        }

        // 2. Derive metadata locally (deterministic, offline).
        val now = clock()
        val id = UUID.randomUUID().toString()
        val url = text?.let { UrlCanonicalizer.extractFirstUrl(it) }?.let { UrlCanonicalizer.canonicalize(it) }
        val mimes = stored.mapNotNull { it.first.mimeType }
        val names = stored.mapNotNull { it.first.displayName }
        val platform = SourceDetector.platform(request.sourceApp, url, mimes.any { it.startsWith("image/") }, request.method)
        val type = SourceDetector.contentType(url, mimes, names, text, request.method)
        val hash = contentHash(text, stored.map { it.second.sha256 })
        val title = deriveTitle(request.subject, text, url, names)
        val item = KnowledgeItemEntity(
            id = id, title = title, rawText = text, sourceUrl = url, sourcePlatform = platform, contentType = type,
            processingState = ProcessingState.LOCAL_SAVED, syncState = SyncState.LOCAL_ONLY, privacyScope = privacy,
            userNote = request.note?.trim()?.takeIf { it.isNotEmpty() },
            fieldOrigins = """{"title":"SYSTEM"}""", createdAt = now, updatedAt = now, contentHash = hash,
        )

        // 3. One transaction for everything the capture needs.
        try {
            val duplicateOf = db.withTransaction {
                val dup = db.knowledgeDao().findDuplicate(hash, url, id)
                db.knowledgeDao().insert(if (dup != null) item.copy(duplicateOfId = dup) else item)
                db.captureSourceDao().insert(
                    CaptureSourceEntity(
                        id = UUID.randomUUID().toString(), knowledgeId = id, captureMethod = request.method,
                        sourceApp = request.sourceApp, sourceUri = request.files.firstOrNull()?.sourceUri, sourceUrl = url,
                        mimeType = mimes.firstOrNull() ?: if (text != null) "text/plain" else null,
                        originalFilename = names.firstOrNull(), contentHash = hash, capturedAt = now,
                    ),
                )
                stored.forEach { (f, s) ->
                    db.attachmentDao().insert(
                        AttachmentEntity(
                            id = UUID.randomUUID().toString(), knowledgeId = id, mimeType = f.mimeType, filename = f.displayName,
                            sizeBytes = s.sizeBytes, sha256 = s.sha256, localPath = store.relativePath(s.file),
                            extractionStatus = if (needsExtraction(f.mimeType)) ExtractionStatus.PENDING else ExtractionStatus.NOT_NEEDED,
                            createdAt = now,
                        ),
                    )
                }
                jobsFor(id, stored.map { it.first.mimeType }, now).forEach { db.processingJobDao().insert(it) }
                dup
            }
            CaptureResult.Saved(id, title, duplicateOf, stored.size)
        } catch (e: Exception) {
            cleanup(stored)
            CaptureResult.Failed(CaptureFailure.DATABASE, e.javaClass.simpleName)
        }
    }

    private suspend fun cleanup(stored: List<Pair<CaptureFile, AttachmentStore.Stored>>) {
        stored.filter { it.second.createdNew }.forEach { (_, s) ->
            if (db.attachmentDao().countByPath(store.relativePath(s.file)) == 0) s.file.delete()
        }
    }

    private fun jobsFor(id: String, mimes: List<String?>, now: Long): List<ProcessingJobEntity> {
        fun job(type: ProcessingJobType) = ProcessingJobEntity(UUID.randomUUID().toString(), id, type, JobState.QUEUED, createdAt = now, updatedAt = now)
        return buildList {
            if (mimes.any { it?.startsWith("image/") == true }) add(job(ProcessingJobType.OCR))
            if (mimes.any { needsExtraction(it) && it?.startsWith("image/") != true }) add(job(ProcessingJobType.EXTRACT))
            add(job(ProcessingJobType.AI_CLASSIFY))
        }
    }

    companion object {
        const val MIN_FREE_TEXT = 5L * 1024 * 1024
        const val MIN_FREE_FILES = 100L * 1024 * 1024

        fun needsExtraction(mime: String?) = mime != null && (mime.startsWith("image/") || mime == "application/pdf" || mime.startsWith("text/"))

        fun extensionOf(f: CaptureFile): String? =
            f.displayName?.substringAfterLast('.', "")?.takeIf { it.isNotEmpty() && it.length <= 8 }
                ?: when (f.mimeType) {
                    "image/jpeg" -> "jpg"; "image/png" -> "png"; "image/webp" -> "webp"; "application/pdf" -> "pdf"; "text/plain" -> "txt"
                    else -> null
                }

        fun contentHash(text: String?, fileHashes: List<String>): String {
            val normalized = text?.lowercase()?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
            val md = MessageDigest.getInstance("SHA-256")
            md.update(normalized.toByteArray()); fileHashes.sorted().forEach { md.update(it.toByteArray()) }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        fun deriveTitle(subject: String?, text: String?, url: String?, fileNames: List<String>): String {
            subject?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("http") }?.let { return it.take(200) }
            text?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() && UrlCanonicalizer.extractFirstUrl(it) != it }
                ?.let { line -> return (UrlCanonicalizer.extractFirstUrl(line)?.let { line.replace(it, "").trim() }?.takeIf { it.isNotEmpty() } ?: line).take(200) }
            fileNames.firstOrNull()?.let { return it.take(200) }
            SourceDetector.host(url)?.let { return it }
            return "Untitled capture"
        }
    }
}
