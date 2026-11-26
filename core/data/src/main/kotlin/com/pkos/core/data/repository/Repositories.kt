package com.pkos.core.data.repository

import androidx.room.withTransaction
import android.content.Context
import com.pkos.core.data.db.PkosDatabase
import com.pkos.core.data.db.dao.CategoryWithCounts
import com.pkos.core.data.db.dao.KnowledgeCounts
import com.pkos.core.data.db.dao.SyncQueueCounts
import com.pkos.core.data.db.entity.BackupSnapshotEntity
import com.pkos.core.data.db.entity.CaptureSourceEntity
import com.pkos.core.data.db.entity.CategoryEntity
import com.pkos.core.data.db.entity.CollectionEntity
import com.pkos.core.data.db.entity.ItemVersionEntity
import com.pkos.core.data.db.entity.KnowledgeItemEntity
import com.pkos.core.data.db.entity.TagEntity
import com.pkos.core.data.db.entity.TopicEntity
import com.pkos.core.data.model.InboxFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import java.io.File

/** Everything the item detail screen shows, read from the local database only. */
data class KnowledgeItemDetail(
    val item: KnowledgeItemEntity,
    val category: CategoryEntity?,
    val topic: TopicEntity?,
    val tags: List<TagEntity>,
    val sources: List<CaptureSourceEntity>,
    val versions: List<ItemVersionEntity>,
    val attachments: List<com.pkos.core.data.db.entity.AttachmentEntity> = emptyList(),
    val lastAiRun: com.pkos.core.data.db.entity.AiRunEntity? = null,
    val subtopic: TopicEntity? = null,
)

class KnowledgeRepository(private val db: PkosDatabase, private val clock: () -> Long = System::currentTimeMillis) {
    private val dao = db.knowledgeDao()

    fun counts(): Flow<KnowledgeCounts> = dao.observeCounts()
    fun recent(limit: Int = 8): Flow<List<KnowledgeItemEntity>> = dao.observeRecent(limit)

    fun inbox(filter: InboxFilter): Flow<List<KnowledgeItemEntity>> = when (filter) {
        InboxFilter.ALL -> dao.observeInboxAll()
        InboxFilter.PROCESSING -> dao.observeProcessing()
        InboxFilter.NEEDS_REVIEW -> dao.observeNeedsReview()
        InboxFilter.FAILED -> dao.observeFailed()
        InboxFilter.SYNC_PENDING -> dao.observeSyncPending()
    }

    /** Keyword search over the local database; works offline. Blank query → empty list. */
    fun search(query: String, limit: Int = 100): Flow<List<KnowledgeItemEntity>> {
        val q = query.trim()
        if (q.isEmpty()) return flowOf(emptyList())
        return dao.search(likePattern(q), limit)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun detail(id: String): Flow<KnowledgeItemDetail?> = dao.observe(id).flatMapLatest { item ->
        if (item == null) return@flatMapLatest flowOf(null)
        combine(
            item.categoryId?.let { db.categoryDao().observe(it) } ?: flowOf(null),
            combine(
                item.topicId?.let { db.topicDao().observe(it) } ?: flowOf(null),
                item.subtopicId?.let { db.topicDao().observe(it) } ?: flowOf(null),
            ) { t, st -> t to st },
            db.tagDao().observeForItem(id),
            db.captureSourceDao().observeForItem(id),
            combine(db.itemVersionDao().observeForItem(id), db.attachmentDao().observeForItem(id), db.aiRunDao().observeLatest(id)) { v, a, r -> Triple(v, a, r) },
        ) { category, (topic, subtopic), tags, sources, (versions, attachments, run) ->
            KnowledgeItemDetail(item, category, topic, tags, sources, versions, attachments, run, subtopic)
        }
    }

    /** Owner edits always win over later AI processing (spec 6.2). */
    suspend fun setPinned(id: String, pinned: Boolean) = dao.setPinned(id, pinned, clock())
    suspend fun setNote(id: String, note: String?) = dao.setNote(id, note?.trim()?.takeIf { it.isNotEmpty() }, clock())

    suspend fun markOpened(id: String) = dao.markOpened(id, clock())

    /**
     * Owner edits (spec 6.2 "user edits outrank AI"). Only fields that actually changed are marked USER, so later AI
     * runs never overwrite them. The original capture (raw text, files) is untouched; a version record is written.
     */
    suspend fun editItem(id: String, title: String, summary: String?, tags: List<String>, status: com.pkos.core.data.model.ItemStatus): Boolean {
        val item = dao.get(id) ?: return false
        val cleanTitle = title.trim().ifEmpty { item.title }.take(300)
        val cleanSummary = summary?.trim()?.ifEmpty { null }
        val cleanTags = tags.map { it.trim().removePrefix("#").trim() }.filter { it.isNotEmpty() }.map { it.take(40) }.distinctBy { it.lowercase() }.take(20)
        val oldTags = db.tagDao().forItem(id).map { it.name }
        val origins = runCatching { org.json.JSONObject(item.fieldOrigins) }.getOrElse { org.json.JSONObject() }
        val user = com.pkos.core.data.model.FieldOrigin.USER.name
        if (cleanTitle != item.title) origins.put("title", user)
        if (cleanSummary != item.summary) origins.put("summary", user)
        if (cleanTags.map { it.lowercase() }.toSet() != oldTags.map { it.lowercase() }.toSet()) origins.put("tags", user)
        if (status != item.status) origins.put("status", user)
        val now = clock()
        val updated = item.copy(title = cleanTitle, summary = cleanSummary, status = status, fieldOrigins = origins.toString(), userEdited = true, updatedAt = now)
        db.withTransaction {
            dao.update(updated)
            if (origins.optString("tags") == user) {
                db.tagDao().unlinkAll(id)
                cleanTags.forEach { name ->
                    val normalized = name.lowercase(java.util.Locale.ROOT)
                    db.tagDao().insertIgnore(com.pkos.core.data.db.entity.TagEntity(java.util.UUID.nameUUIDFromBytes(normalized.toByteArray()).toString(), name, normalized))
                    db.tagDao().linkByName(id, normalized)
                }
            }
            recordVersion(updated)
        }
        return true
    }

    /** Archive keeps the item (search, Notion, backup) but takes it out of the Inbox. Unarchive returns it there. */
    suspend fun setArchived(id: String, archived: Boolean) {
        val item = dao.get(id) ?: return
        val status = if (archived) com.pkos.core.data.model.ItemStatus.ARCHIVED else com.pkos.core.data.model.ItemStatus.INBOX
        if (item.status == status) return
        val origins = runCatching { org.json.JSONObject(item.fieldOrigins) }.getOrElse { org.json.JSONObject() }.put("status", "USER")
        val updated = item.copy(status = status, fieldOrigins = origins.toString(), userEdited = true, updatedAt = clock())
        db.withTransaction { dao.update(updated); recordVersion(updated) }
    }

    /**
     * Per-item privacy (spec §10 "local-only per item"). LOCAL_ONLY items are never sent to cloud AI, embeddings or
     * Notion; any cloud-made embedding of the item is dropped. Pages already in Notion are left as they are.
     */
    suspend fun setLocalOnly(id: String, localOnly: Boolean) {
        val item = dao.get(id) ?: return
        val scope = if (localOnly) com.pkos.core.data.model.PrivacyScope.LOCAL_ONLY else com.pkos.core.data.model.PrivacyScope.NORMAL
        if (item.privacyScope == scope) return
        db.withTransaction {
            // The owner's choice wins over automatic sensitive-data detection from now on.
            val origins = runCatching { org.json.JSONObject(item.fieldOrigins) }.getOrElse { org.json.JSONObject() }.put("privacy", com.pkos.core.data.model.FieldOrigin.USER.name)
            dao.update(item.copy(privacyScope = scope, updatedAt = clock(), fieldOrigins = origins.toString()))
            if (localOnly) db.featureDao().deleteEmbeddings(id)
        }
    }

    /**
     * Soft delete: hidden everywhere in the app, kept in the database (and in backups) so nothing is destroyed;
     * the Notion page is never deleted (spec: never delete remote data automatically).
     */
    suspend fun delete(id: String) = dao.softDelete(id, clock())

    private suspend fun recordVersion(i: com.pkos.core.data.db.entity.KnowledgeItemEntity) {
        val v = db.itemVersionDao().latestVersion(i.id) + 1
        val snap = org.json.JSONObject().put("title", i.title).put("summary", i.summary ?: org.json.JSONObject.NULL)
            .put("status", i.status.name).put("origins", org.json.JSONObject(i.fieldOrigins))
        db.itemVersionDao().insert(com.pkos.core.data.db.entity.ItemVersionEntity(java.util.UUID.randomUUID().toString(), i.id, v, snap.toString(), i.updatedAt, com.pkos.core.data.model.FieldOrigin.USER))
    }

    /** Owner confirms the AI organisation: the category becomes theirs (origin USER) and the review flag clears. */
    suspend fun acceptOrganization(id: String) = setCategoryInternal(id, null)

    /** Owner picks a category. Marked USER so no later AI run changes it (spec 4.5). */
    suspend fun setCategory(id: String, categoryId: String) = setCategoryInternal(id, categoryId)

    private suspend fun setCategoryInternal(id: String, categoryId: String?) {
        val item = dao.get(id) ?: return
        val origins = runCatching { org.json.JSONObject(item.fieldOrigins) }.getOrElse { org.json.JSONObject() }.put("category", "USER")
        dao.update(
            item.copy(
                categoryId = categoryId ?: item.categoryId,
                processingState = com.pkos.core.data.model.ProcessingState.ORGANIZED,
                reviewReason = null,
                userEdited = true,
                fieldOrigins = origins.toString(),
                updatedAt = clock(),
            ),
        )
    }

    companion object {
        /** Escapes LIKE wildcards so user text is matched literally. */
        fun likePattern(query: String): String =
            "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
    }
}

class CategoryRepository(private val db: PkosDatabase, private val clock: () -> Long = System::currentTimeMillis) {
    fun categories(): Flow<List<CategoryWithCounts>> = db.categoryDao().observeWithCounts()
    fun all(): Flow<List<CategoryEntity>> = db.categoryDao().observeAll()
    fun recentlyOpenedTopics(limit: Int = 4): Flow<List<TopicEntity>> = db.topicDao().observeRecentlyOpened(limit)

    /**
     * Inserts the editable starter taxonomy (spec 4.3) once. Fixed ids make this idempotent; existing rows
     * (including ones the owner renamed) are never overwritten. Notion options are imported in M4.
     */
    suspend fun ensureStarterTaxonomy() {
        val now = clock()
        db.categoryDao().insertIgnore(
            StarterTaxonomy.categories.mapIndexed { index, c ->
                CategoryEntity(id = c.id, name = c.name, icon = c.icon, isSystem = true, sortOrder = index, createdAt = now)
            },
        )
    }
}

object StarterTaxonomy {
    const val UNSORTED = "sys-unsorted"
    /** Never assigned automatically: health is only what the owner files there (spec 4.3). */
    const val HEALTH = "sys-health"

    data class Starter(val id: String, val name: String, val icon: String)

    val categories = listOf(
        Starter("sys-education", "Education & Learning", "school"),
        Starter("sys-technology", "Technology & Software", "devices"),
        Starter("sys-programming", "Programming & Data", "code"),
        Starter("sys-projects", "Projects & Work", "work"),
        Starter("sys-business", "Business & Finance", "business"),
        Starter("sys-ideas", "Ideas & Inspiration", "lightbulb"),
        Starter("sys-health", "Health & Personal Reference", "favorite"),
        Starter("sys-websites", "Websites & Tools", "language"),
        Starter("sys-personal", "Personal Notes", "edit_note"),
        Starter("sys-unsorted", "Unsorted / Needs Review", "inbox"),
    )
}

class SyncOverviewRepository(private val db: PkosDatabase) {
    fun lastVerifiedBackup(): Flow<BackupSnapshotEntity?> = db.syncDao().observeLastVerifiedBackup()
    fun queueCounts(): Flow<SyncQueueCounts> = db.syncDao().observeQueueCounts()
}

class CollectionRepository(private val db: PkosDatabase) {
    fun suggestions(): Flow<List<CollectionEntity>> = db.collectionDao().observeSuggestions()
    suspend fun dismiss(id: String) = db.collectionDao().dismiss(id)
}

data class StorageUsage(val databaseBytes: Long, val attachmentBytes: Long) {
    val totalBytes: Long get() = databaseBytes + attachmentBytes
}

/** Real on-device usage: database files + app-private attachments folder. */
class StorageRepository(private val context: Context) {
    suspend fun usage(): StorageUsage = withContext(Dispatchers.IO) {
        val dbFile = context.getDatabasePath(PkosDatabase.FILE_NAME)
        val dbBytes = listOf(dbFile, File(dbFile.path + "-wal"), File(dbFile.path + "-shm"))
            .filter { it.exists() }.sumOf { it.length() }
        StorageUsage(dbBytes, folderSize(File(context.filesDir, ATTACHMENTS_DIR)))
    }

    private fun folderSize(dir: File): Long =
        if (!dir.exists()) 0 else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    companion object {
        const val ATTACHMENTS_DIR = "attachments"
    }
}
