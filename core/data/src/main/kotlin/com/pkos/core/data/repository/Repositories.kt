package com.pkos.core.data.repository

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
            item.topicId?.let { db.topicDao().observe(it) } ?: flowOf(null),
            db.tagDao().observeForItem(id),
            db.captureSourceDao().observeForItem(id),
            db.itemVersionDao().observeForItem(id),
        ) { category, topic, tags, sources, versions -> KnowledgeItemDetail(item, category, topic, tags, sources, versions) }
    }

    suspend fun markOpened(id: String) = dao.markOpened(id, clock())

    companion object {
        /** Escapes LIKE wildcards so user text is matched literally. */
        fun likePattern(query: String): String =
            "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
    }
}

class CategoryRepository(private val db: PkosDatabase, private val clock: () -> Long = System::currentTimeMillis) {
    fun categories(): Flow<List<CategoryWithCounts>> = db.categoryDao().observeWithCounts()
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
