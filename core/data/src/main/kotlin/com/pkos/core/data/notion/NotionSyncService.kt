package com.pkos.core.data.notion

import androidx.room.withTransaction
import com.pkos.core.data.db.PkosDatabase
import com.pkos.core.data.db.entity.AttachmentEntity
import com.pkos.core.data.db.entity.KnowledgeItemEntity
import com.pkos.core.data.db.entity.NotionConflictEntity
import com.pkos.core.data.db.entity.NotionKeptPropertiesEntity
import com.pkos.core.data.db.entity.NotionMappingEntity
import com.pkos.core.data.db.entity.NotionWriteIntentEntity
import com.pkos.core.data.db.entity.SyncJobEntity
import com.pkos.core.data.model.JobState
import com.pkos.core.data.model.SyncDestination
import com.pkos.core.data.model.SyncState
import com.pkos.core.data.processing.ReviewReason
import com.pkos.core.notion.api.NotionClient
import com.pkos.core.notion.api.NotionErrorKind
import com.pkos.core.notion.api.NotionResult
import com.pkos.core.notion.model.KnowledgeBody
import com.pkos.core.notion.model.NotionFileRef
import com.pkos.core.notion.model.NotionPageLink
import com.pkos.core.notion.model.SyncItem
import com.pkos.core.notion.schema.DataSourceSchemaParser
import com.pkos.core.notion.schema.SchemaGuard
import com.pkos.core.notion.schema.SchemaProblem
import com.pkos.core.notion.sync.ConflictResolution
import com.pkos.core.notion.sync.NotionFileUploader
import com.pkos.core.notion.sync.NotionSyncEngine
import com.pkos.core.notion.sync.SyncJournal
import com.pkos.core.notion.sync.SyncOutcome
import com.pkos.core.notion.sync.WriteIntent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import com.pkos.core.notion.model.ContentType as NContentType
import com.pkos.core.notion.model.ItemPriority as NPriority
import com.pkos.core.notion.model.ItemStatus as NStatus
import com.pkos.core.notion.model.PrivacyScope as NPrivacy
import com.pkos.core.notion.model.SourcePlatform as NPlatform

/** Approved target: My knowledge Hub → Master Database (ADR 0001). */
const val MASTER_DATA_SOURCE_ID = "3d290cde-650c-8001-9cab-000bf4ce921c"

/** Write-ahead journal in Room (ADR 0002). Durable when [recordIntent] returns. */
class RoomSyncJournal(private val db: PkosDatabase) : SyncJournal {
    override suspend fun lastIntent(captureId: String): WriteIntent? = db.notionDao().intent(captureId)?.let {
        WriteIntent(it.captureId, WriteIntent.Kind.valueOf(it.kind), it.propertiesHash, it.pageId, Instant.ofEpochMilli(it.sentAt))
    }

    override suspend fun recordIntent(intent: WriteIntent) = db.notionDao().upsertIntent(
        NotionWriteIntentEntity(intent.captureId, intent.kind.name, intent.propertiesHash, intent.pageId, intent.sentAt.toEpochMilli()),
    )
}

sealed interface NotionSyncResult {
    data class Synced(val pageUrl: String?, val created: Boolean) : NotionSyncResult
    data class Retry(val delayMs: Long, val reason: String) : NotionSyncResult
    data class ActionRequired(val reason: String) : NotionSyncResult
    data object Conflict : NotionSyncResult
    data object Skipped : NotionSyncResult
}

sealed interface ConnectionReport {
    /** Live schema read with the owner's token; [missing] are optional fields PKOS will skip. */
    data class Ok(val title: String, val usableFields: Int, val totalFields: Int, val missing: List<String>) : ConnectionReport
    data class Failed(val reason: String) : ConnectionReport
}

/**
 * Glue between the local library and the Notion core: builds the SyncItem from Room, uploads files, runs the
 * engine and stores every outcome honestly. "Synced" is only stored after the engine verified the page by
 * reading it back from Notion.
 */
class NotionSyncService(
    private val db: PkosDatabase,
    private val client: NotionClient,
    private val resolveFile: (String) -> File,
    private val dataSourceId: String = MASTER_DATA_SOURCE_ID,
    private val clock: () -> Long = System::currentTimeMillis,
    private val uploader: NotionFileUploader = NotionFileUploader(client),
    /** Null unless the owner turned on large files via Google Drive and Drive is connected. */
    private val largeFiles: suspend () -> LargeFileHost? = { null },
) {
    private val engine = NotionSyncEngine(client, dataSourceId, RoomSyncJournal(db), clock = { Instant.ofEpochMilli(clock()) })
    private val dao get() = db.notionDao()

    suspend fun checkConnection(): ConnectionReport = when (val r = client.retrieveDataSource(dataSourceId)) {
        is NotionResult.Err -> ConnectionReport.Failed(r.error.kind.name)
        is NotionResult.Ok -> {
            val schema = runCatching { DataSourceSchemaParser.parse(r.value) }.getOrNull()
            if (schema == null) ConnectionReport.Failed(NotionErrorKind.INVALID_RESPONSE.name)
            else {
                val check = SchemaGuard.check(schema)
                if (check.blocked) ConnectionReport.Failed("SCHEMA_INCOMPATIBLE")
                else {
                    val title = runCatching { JSONObject(r.value.toString()).getJSONArray("title").getJSONObject(0).getString("plain_text") }.getOrDefault("Master Database")
                    ConnectionReport.Ok(
                        title, check.usable.size, check.usable.size + check.problems.size,
                        check.problems.filter { it.kind == SchemaProblem.Kind.MISSING || it.kind == SchemaProblem.Kind.WRONG_TYPE }.map { it.propertyName.trim() },
                    )
                }
            }
        }
    }

    suspend fun itemsNeedingSync(): List<String> = dao.itemsNeedingSync()

    /** Not going to Notion: BACKED_UP if this version is in a verified backup, else LOCAL_ONLY (spec 8.5). */
    private suspend fun notInNotionState(updatedAt: Long): SyncState =
        if ((db.backupDao().latestVerifiedAt() ?: Long.MIN_VALUE) >= updatedAt) SyncState.BACKED_UP else SyncState.LOCAL_ONLY

    suspend fun markQueued(itemId: String) {
        val item = db.knowledgeDao().get(itemId) ?: return
        if (item.syncState != SyncState.CONFLICT) dao.setSyncState(itemId, SyncState.NOTION_QUEUED)
    }

    /** Owner decision on a conflict. Keep Notion: the differing properties stay Notion's for this item. */
    suspend fun resolveConflict(itemId: String, keepNotion: Boolean, attempt: Int = 0): NotionSyncResult {
        val c = dao.conflict(itemId) ?: return sync(itemId, attempt)
        if (keepNotion) {
            val kept = keptProperties(itemId) + jsonList(c.properties)
            dao.upsertKept(NotionKeptPropertiesEntity(itemId, JSONArray(kept.toList()).toString()))
        }
        dao.clearConflict(itemId)
        dao.setSyncState(itemId, SyncState.NOTION_QUEUED)
        return sync(itemId, attempt, if (keepNotion) null else ConflictResolution.OVERWRITE_REMOTE)
    }

    suspend fun sync(itemId: String, attempt: Int = 0, resolution: ConflictResolution? = null): NotionSyncResult {
        val item = db.knowledgeDao().get(itemId) ?: return NotionSyncResult.Skipped
        if (item.deletedAt != null || item.privacyScope == com.pkos.core.data.model.PrivacyScope.LOCAL_ONLY) {
            dao.setSyncState(itemId, notInNotionState(item.updatedAt)); return NotionSyncResult.Skipped
        }
        if (dao.conflict(itemId) != null && resolution == null) return NotionSyncResult.Conflict
        val mapping = dao.mapping(itemId)
        val kept = keptProperties(itemId)

        // Files first: an upload failure must not leave a page without its files.
        val attachments = db.attachmentDao().forItem(itemId)
        val refs = mutableListOf<Pair<AttachmentEntity, NotionFileRef>>()
        if ("Files & media" !in kept) for (a in attachments) {
            val name = (a.filename ?: (a.sha256.take(12) + extension(a.mimeType))).take(100)
            if (a.driveLink != null) { refs += a to NotionFileRef(name, externalUrl = a.driveLink); continue }
            if (a.notionSkipReason == SKIP_TOO_LARGE) {
                when (val r = hostLarge(itemId, attempt, a, name)) { is NotionSyncResult -> return r; is NotionFileRef -> refs += a to r }
                continue
            }
            val fresh = a.notionFileUploadId != null && (a.notionAttached || clock() - (a.notionUploadedAt ?: 0) < NotionFileUploader.UPLOAD_REUSE_WINDOW_MS)
            if (fresh) { refs += a to NotionFileRef(name, fileUploadId = a.notionFileUploadId); continue }
            when (val up = uploader.upload(resolveFile(a.localPath), name, a.mimeType ?: "application/octet-stream")) {
                is NotionFileUploader.Result.Uploaded -> {
                    dao.setUpload(a.id, up.fileUploadId, clock())
                    refs += a to NotionFileRef(name, fileUploadId = up.fileUploadId)
                }
                NotionFileUploader.Result.TooLarge -> {
                    dao.setUploadSkipped(a.id, SKIP_TOO_LARGE)
                    when (val r = hostLarge(itemId, attempt, a, name)) { is NotionSyncResult -> return r; is NotionFileRef -> refs += a to r }
                }
                is NotionFileUploader.Result.Failed -> when (up.error.kind) {
                    NotionErrorKind.NETWORK, NotionErrorKind.SERVER, NotionErrorKind.RATE_LIMITED, NotionErrorKind.CONFLICT, NotionErrorKind.INVALID_RESPONSE ->
                        return record(itemId, attempt, NotionSyncResult.Retry(backoff(attempt, up.error.retryAfterSeconds), "UPLOAD_${up.error.kind}"))
                    NotionErrorKind.NO_TOKEN, NotionErrorKind.UNAUTHORIZED, NotionErrorKind.FORBIDDEN ->
                        return record(itemId, attempt, NotionSyncResult.ActionRequired(up.error.kind.name))
                    else -> dao.setUploadSkipped(a.id, "REJECTED")
                }
            }
        }

        val syncItem = toSyncItem(item, refs.map { it.second }, kept)
        val existing = mapping?.let {
            NotionPageLink(itemId, it.notionPageId, it.notionUrl, it.dataSourceId, it.remoteHash, it.bodyWritten, Instant.ofEpochMilli(it.lastSyncedAt))
        }
        return when (val out = engine.sync(syncItem, existing, attempt, resolution)) {
            is SyncOutcome.Synced -> {
                db.withTransaction {
                    dao.upsertMapping(
                        NotionMappingEntity(
                            itemId, out.link.pageId, out.link.pageUrl, out.link.dataSourceId, out.link.dataSourceId,
                            out.link.remoteHash, out.link.bodyWritten, maxOf(clock(), item.updatedAt),
                        ),
                    )
                    if (refs.isNotEmpty()) dao.markAttached(refs.map { it.first.id })
                    dao.clearConflict(itemId)
                    // SYNCED = every configured destination has this version (spec 8.5): Notion now, and a verified backup.
                    val backedUp = (db.backupDao().latestVerifiedAt() ?: Long.MIN_VALUE) >= item.updatedAt
                    dao.setSyncState(itemId, if (backedUp) SyncState.SYNCED else SyncState.NOTION_SYNCED)
                }
                record(itemId, attempt, NotionSyncResult.Synced(out.link.pageUrl, out.created))
            }
            is SyncOutcome.Retry -> record(itemId, attempt, NotionSyncResult.Retry(out.delayMs, out.reason.name))
            is SyncOutcome.ActionRequired -> record(itemId, attempt, NotionSyncResult.ActionRequired(out.reason.name + (out.detail.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "")))
            is SyncOutcome.Conflict -> {
                dao.upsertConflict(
                    NotionConflictEntity(
                        itemId, out.kind.name, out.pageId, out.pageUrl, JSONArray(out.differingProperties).toString(),
                        JSONObject(out.remoteValues as Map<*, *>).toString(), JSONObject(out.localValues as Map<*, *>).toString(), clock(),
                    ),
                )
                record(itemId, attempt, NotionSyncResult.Conflict)
            }
            is SyncOutcome.NotEligible -> { dao.setSyncState(itemId, notInNotionState(item.updatedAt)); NotionSyncResult.Skipped }
        }
    }

    private suspend fun record(itemId: String, attempt: Int, result: NotionSyncResult): NotionSyncResult {
        val (jobState, syncState, code) = when (result) {
            is NotionSyncResult.Synced -> Triple(JobState.SUCCEEDED, null, null)
            is NotionSyncResult.Retry -> Triple(JobState.RETRY_SCHEDULED, SyncState.ERROR_RETRYABLE, result.reason)
            is NotionSyncResult.ActionRequired -> Triple(JobState.ACTION_REQUIRED, SyncState.ERROR_ACTION_REQUIRED, result.reason)
            NotionSyncResult.Conflict -> Triple(JobState.ACTION_REQUIRED, SyncState.CONFLICT, "CONFLICT")
            NotionSyncResult.Skipped -> return result
        }
        val now = clock()
        val id = jobId(itemId)
        val prev = dao.job(id)
        dao.upsertJob(
            SyncJobEntity(
                id, itemId, SyncDestination.NOTION, jobState, attempt + 1,
                (result as? NotionSyncResult.Retry)?.let { now + it.delayMs }, code, prev?.createdAt ?: now, now,
            ),
        )
        syncState?.let { dao.setSyncState(itemId, it) }
        return result
    }

    private suspend fun keptProperties(itemId: String): Set<String> = dao.kept(itemId)?.let { jsonList(it.propertyNames) }.orEmpty()

    /**
     * A file over Notion's limit goes to the owner's Drive only when that is turned on; otherwise it stays skipped (the
     * page still syncs). Returns the file reference, a result that stops this sync (retry later), or null (stay skipped).
     */
    private suspend fun hostLarge(itemId: String, attempt: Int, a: AttachmentEntity, name: String): Any? {
        val host = largeFiles() ?: return null
        return try {
            val link = host.host(resolveFile(a.localPath), name, a.mimeType ?: "application/octet-stream", a.sha256)
            dao.setDriveLink(a.id, link)
            NotionFileRef(name, externalUrl = link)
        } catch (e: com.pkos.core.data.backup.DriveException) {
            when (e.kind) {
                com.pkos.core.data.backup.DriveErrorKind.NETWORK, com.pkos.core.data.backup.DriveErrorKind.RATE_LIMITED,
                com.pkos.core.data.backup.DriveErrorKind.SERVER, com.pkos.core.data.backup.DriveErrorKind.SESSION_EXPIRED ->
                    record(itemId, attempt, NotionSyncResult.Retry(backoff(attempt, e.retryAfterMs?.div(1000)), "DRIVE_${e.kind}"))
                else -> null // auth, full, other: the file stays "too large" and the page syncs without it
            }
        }
    }

    private suspend fun toSyncItem(item: KnowledgeItemEntity, files: List<NotionFileRef>, kept: Set<String>): SyncItem {
        val category = item.categoryId?.takeIf { it != "sys-unsorted" }?.let { db.categoryDao().get(it)?.name }
        val tags = db.tagDao().forItem(item.id).map { it.name }
        val ocr = db.attachmentDao().forItem(item.id).mapNotNull { it.extractedText }.joinToString("\n\n").ifBlank { null }
        val sourceApp = db.captureSourceDao().forItemOnce(item.id).firstOrNull()?.sourceApp
        return SyncItem(
            captureId = item.id,
            title = item.title,
            sourceUrl = item.sourceUrl,
            capturedAt = Instant.ofEpochMilli(item.createdAt),
            contentType = item.contentType?.let(::contentType),
            status = NStatus.valueOf(item.status.name),
            priority = NPriority.valueOf(item.priority.name),
            pinned = item.pinned,
            mainCategory = category,
            tags = tags,
            platform = item.sourcePlatform?.let(::platform),
            privacyScope = NPrivacy.valueOf(item.privacyScope.name),
            attachments = files,
            body = KnowledgeBody(
                summary = item.summary,
                purpose = item.purpose?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
                confidence = item.confidence,
                reviewReason = item.reviewReason?.let(::reasonText),
                originalText = item.rawText,
                ocrText = ocr,
                sourceApp = sourceApp,
            ),
            remoteOwnedProperties = kept,
        )
    }

    private fun backoff(attempt: Int, retryAfterSeconds: Long?) =
        retryAfterSeconds?.let { (it * 1000).coerceIn(1_000, 6 * 3_600_000) } ?: (30_000L shl attempt.coerceAtMost(8)).coerceAtMost(6 * 3_600_000)

    companion object {
        const val SKIP_TOO_LARGE = "TOO_LARGE"
        fun jobId(itemId: String) = "notion:$itemId"

        fun jsonList(s: String): Set<String> = runCatching { JSONArray(s).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } }.getOrDefault(emptySet())

        private fun extension(mime: String?) = when {
            mime == null -> ""
            mime.endsWith("png") -> ".png"; mime.endsWith("jpeg") || mime.endsWith("jpg") -> ".jpg"
            mime.endsWith("pdf") -> ".pdf"; else -> ""
        }

        fun contentType(t: com.pkos.core.data.model.ContentType): NContentType = when (t) {
            com.pkos.core.data.model.ContentType.SCREENSHOT -> NContentType.IMAGE
            else -> NContentType.valueOf(t.name)
        }

        fun platform(p: com.pkos.core.data.model.SourcePlatform): NPlatform? = when (p) {
            com.pkos.core.data.model.SourcePlatform.CLIPBOARD, com.pkos.core.data.model.SourcePlatform.MANUAL -> null
            else -> NPlatform.valueOf(p.name)
        }

        /** PKOS's own review codes become plain English in the Notion page body. */
        fun reasonText(code: String): String = when (code) {
            ReviewReason.NO_TOPIC -> "No clear topic found"
            ReviewReason.AMBIGUOUS -> "Matches more than one category"
            ReviewReason.UNKNOWN_CATEGORY -> "AI suggested a category that is not in the list"
            ReviewReason.LOW_CONFIDENCE -> "Low confidence"
            ReviewReason.CLOUD_INVALID -> "Cloud AI answer was malformed; organized on the phone"
            ReviewReason.CLOUD_QUOTA -> "Cloud AI free limit used up; organized on the phone"
            ReviewReason.CLOUD_AUTH -> "Cloud AI key rejected; organized on the phone"
            ReviewReason.CLOUD_UNAVAILABLE -> "Cloud AI unreachable; organized on the phone"
            else -> code
        }
    }
}
