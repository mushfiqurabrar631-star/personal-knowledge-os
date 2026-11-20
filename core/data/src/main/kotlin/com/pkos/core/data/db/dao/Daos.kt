package com.pkos.core.data.db.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.pkos.core.data.db.entity.BackupSnapshotEntity
import com.pkos.core.data.db.entity.CaptureSourceEntity
import com.pkos.core.data.db.entity.CategoryEntity
import com.pkos.core.data.db.entity.CollectionEntity
import com.pkos.core.data.db.entity.ItemVersionEntity
import com.pkos.core.data.db.entity.KnowledgeItemEntity
import com.pkos.core.data.db.entity.SyncJobEntity
import com.pkos.core.data.db.entity.TagEntity
import com.pkos.core.data.db.entity.TopicEntity
import kotlinx.coroutines.flow.Flow

/** Counts for the Home status summary and Inbox filter chips. One query, always consistent. */
data class KnowledgeCounts(
    val saved: Int,
    val processing: Int,
    @ColumnInfo(name = "needs_review") val needsReview: Int,
    @ColumnInfo(name = "action_required") val actionRequired: Int,
    @ColumnInfo(name = "pending_sync") val pendingSync: Int,
    val inbox: Int,
)

private const val ALIVE = "deleted_at IS NULL"
private const val IN_PROGRESS = "processing_state IN ('CAPTURED','LOCAL_SAVED','EXTRACT_PENDING','EXTRACTED','AI_PENDING')"
private const val NEEDS_ACTION = "(processing_state = 'FAILED' OR sync_state IN ('ERROR_ACTION_REQUIRED','CONFLICT'))"
private const val PENDING_SYNC = "sync_state IN ('BACKUP_QUEUED','NOTION_QUEUED','ERROR_RETRYABLE')"
private const val IN_INBOX = "(status = 'INBOX' OR $IN_PROGRESS OR processing_state = 'NEEDS_REVIEW' OR $NEEDS_ACTION)"

@Dao
interface KnowledgeDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(item: KnowledgeItemEntity)

    @Upsert
    suspend fun upsert(item: KnowledgeItemEntity)

    @Query("SELECT * FROM knowledge_items WHERE id = :id")
    fun observe(id: String): Flow<KnowledgeItemEntity?>

    @Query("UPDATE knowledge_items SET last_opened_at = :at WHERE id = :id")
    suspend fun markOpened(id: String, at: Long)

    @Query("UPDATE knowledge_items SET deleted_at = :at, updated_at = :at WHERE id = :id")
    suspend fun softDelete(id: String, at: Long)

    @Query(
        """
        SELECT
          (SELECT COUNT(*) FROM knowledge_items WHERE $ALIVE) AS saved,
          (SELECT COUNT(*) FROM knowledge_items WHERE $ALIVE AND $IN_PROGRESS) AS processing,
          (SELECT COUNT(*) FROM knowledge_items WHERE $ALIVE AND processing_state = 'NEEDS_REVIEW') AS needs_review,
          (SELECT COUNT(*) FROM knowledge_items WHERE $ALIVE AND $NEEDS_ACTION) AS action_required,
          (SELECT COUNT(*) FROM knowledge_items WHERE $ALIVE AND $PENDING_SYNC) AS pending_sync,
          (SELECT COUNT(*) FROM knowledge_items WHERE $ALIVE AND $IN_INBOX) AS inbox
        """,
    )
    fun observeCounts(): Flow<KnowledgeCounts>

    @Query("SELECT * FROM knowledge_items WHERE $ALIVE ORDER BY COALESCE(last_opened_at, updated_at) DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<KnowledgeItemEntity>>

    @Query("SELECT * FROM knowledge_items WHERE $ALIVE AND $IN_INBOX ORDER BY created_at DESC")
    fun observeInboxAll(): Flow<List<KnowledgeItemEntity>>

    @Query("SELECT * FROM knowledge_items WHERE $ALIVE AND $IN_PROGRESS ORDER BY created_at DESC")
    fun observeProcessing(): Flow<List<KnowledgeItemEntity>>

    @Query("SELECT * FROM knowledge_items WHERE $ALIVE AND processing_state = 'NEEDS_REVIEW' ORDER BY created_at DESC")
    fun observeNeedsReview(): Flow<List<KnowledgeItemEntity>>

    @Query("SELECT * FROM knowledge_items WHERE $ALIVE AND $NEEDS_ACTION ORDER BY updated_at DESC")
    fun observeFailed(): Flow<List<KnowledgeItemEntity>>

    @Query("SELECT * FROM knowledge_items WHERE $ALIVE AND $PENDING_SYNC ORDER BY updated_at DESC")
    fun observeSyncPending(): Flow<List<KnowledgeItemEntity>>

    /** Offline keyword search (spec R-08). `pattern` must already be a LIKE pattern with escaped wildcards. */
    @Query(
        """
        SELECT * FROM knowledge_items
        WHERE $ALIVE AND (
          title LIKE :pattern ESCAPE '\' OR raw_text LIKE :pattern ESCAPE '\' OR
          summary LIKE :pattern ESCAPE '\' OR source_url LIKE :pattern ESCAPE '\' OR user_note LIKE :pattern ESCAPE '\')
        ORDER BY updated_at DESC LIMIT :limit
        """,
    )
    fun search(pattern: String, limit: Int): Flow<List<KnowledgeItemEntity>>
}

data class CategoryWithCounts(
    @Embedded val category: CategoryEntity,
    @ColumnInfo(name = "item_count") val itemCount: Int,
    @ColumnInfo(name = "topic_count") val topicCount: Int,
)

@Dao
interface CategoryDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(categories: List<CategoryEntity>): List<Long>

    @Query(
        """
        SELECT c.*,
          (SELECT COUNT(*) FROM knowledge_items k WHERE k.category_id = c.id AND k.deleted_at IS NULL) AS item_count,
          (SELECT COUNT(*) FROM topics t WHERE t.category_id = c.id) AS topic_count
        FROM categories c WHERE c.parent_id IS NULL ORDER BY c.sort_order, c.name
        """,
    )
    fun observeWithCounts(): Flow<List<CategoryWithCounts>>

    @Query("SELECT * FROM categories WHERE id = :id")
    fun observe(id: String): Flow<CategoryEntity?>

    @Query("SELECT COUNT(*) FROM categories")
    suspend fun count(): Int
}

@Dao
interface TopicDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(topic: TopicEntity)

    @Query("SELECT * FROM topics WHERE last_opened_at IS NOT NULL ORDER BY last_opened_at DESC LIMIT :limit")
    fun observeRecentlyOpened(limit: Int): Flow<List<TopicEntity>>

    @Query("SELECT * FROM topics WHERE id = :id")
    fun observe(id: String): Flow<TopicEntity?>
}

@Dao
interface TagDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(tag: TagEntity): Long

    @Query("INSERT OR IGNORE INTO knowledge_tags (knowledge_id, tag_id) VALUES (:knowledgeId, :tagId)")
    suspend fun link(knowledgeId: String, tagId: String)

    @Query("SELECT t.* FROM tags t JOIN knowledge_tags kt ON kt.tag_id = t.id WHERE kt.knowledge_id = :knowledgeId ORDER BY t.name")
    fun observeForItem(knowledgeId: String): Flow<List<TagEntity>>
}

@Dao
interface CaptureSourceDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(source: CaptureSourceEntity)

    @Query("SELECT * FROM capture_sources WHERE knowledge_id = :knowledgeId ORDER BY captured_at")
    fun observeForItem(knowledgeId: String): Flow<List<CaptureSourceEntity>>
}

data class SyncQueueCounts(
    @ColumnInfo(name = "backup_queued") val backupQueued: Int,
    @ColumnInfo(name = "notion_queued") val notionQueued: Int,
    @ColumnInfo(name = "action_required") val actionRequired: Int,
)

@Dao
interface SyncDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertJob(job: SyncJobEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertBackup(snapshot: BackupSnapshotEntity)

    /** Only a verified backup counts (spec 8.4 / "never pretend"). */
    @Query("SELECT * FROM backup_snapshots WHERE status = 'VERIFIED' ORDER BY created_at DESC LIMIT 1")
    fun observeLastVerifiedBackup(): Flow<BackupSnapshotEntity?>

    @Query(
        """
        SELECT
          (SELECT COUNT(*) FROM sync_jobs WHERE destination = 'DRIVE_BACKUP' AND state IN ('QUEUED','RUNNING','RETRY_SCHEDULED')) AS backup_queued,
          (SELECT COUNT(*) FROM sync_jobs WHERE destination = 'NOTION' AND state IN ('QUEUED','RUNNING','RETRY_SCHEDULED')) AS notion_queued,
          (SELECT COUNT(*) FROM sync_jobs WHERE state = 'ACTION_REQUIRED') AS action_required
        """,
    )
    fun observeQueueCounts(): Flow<SyncQueueCounts>
}

@Dao
interface CollectionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(collection: CollectionEntity)

    @Query("SELECT * FROM collections WHERE is_suggested = 1 AND dismissed = 0 ORDER BY created_at DESC")
    fun observeSuggestions(): Flow<List<CollectionEntity>>

    @Query("UPDATE collections SET dismissed = 1 WHERE id = :id")
    suspend fun dismiss(id: String)
}

@Dao
interface ItemVersionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(version: ItemVersionEntity)

    @Query("SELECT * FROM item_versions WHERE knowledge_id = :knowledgeId ORDER BY version DESC")
    fun observeForItem(knowledgeId: String): Flow<List<ItemVersionEntity>>
}
