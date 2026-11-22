package com.pkos.core.data.db.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
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
private const val IN_INBOX = "(status != 'ARCHIVED' AND (status = 'INBOX' OR $IN_PROGRESS OR processing_state = 'NEEDS_REVIEW' OR $NEEDS_ACTION))"

@Dao
interface KnowledgeDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(item: KnowledgeItemEntity)

    @Upsert
    suspend fun upsert(item: KnowledgeItemEntity)

    @Update
    suspend fun update(item: KnowledgeItemEntity)

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

    @Query("SELECT * FROM knowledge_items WHERE id = :id")
    suspend fun get(id: String): KnowledgeItemEntity?

    /** Oldest live item with the same content hash or the same canonical URL (duplicate suggestion). */
    @Query(
        """
        SELECT id FROM knowledge_items WHERE $ALIVE AND id != :excludeId AND (
          (:hash IS NOT NULL AND content_hash = :hash) OR (:url IS NOT NULL AND source_url = :url))
        ORDER BY created_at ASC LIMIT 1
        """,
    )
    suspend fun findDuplicate(hash: String?, url: String?, excludeId: String): String?

    /** Recent live items with enough text for a fuzzy-similarity check (bounded, so capture stays fast). */
    @Query("SELECT id, raw_text FROM knowledge_items WHERE $ALIVE AND raw_text IS NOT NULL AND LENGTH(raw_text) >= :minLength ORDER BY created_at DESC LIMIT :limit")
    suspend fun recentTexts(minLength: Int, limit: Int): List<IdText>

    @Query("UPDATE knowledge_items SET processing_state = :state, updated_at = :at WHERE id = :id")
    suspend fun setProcessingState(id: String, state: com.pkos.core.data.model.ProcessingState, at: Long)

    @Query("UPDATE knowledge_items SET pinned = :pinned, updated_at = :at WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean, at: Long)

    @Query("UPDATE knowledge_items SET user_note = :note, updated_at = :at WHERE id = :id")
    suspend fun setNote(id: String, note: String?, at: Long)

    @Query("SELECT COUNT(*) FROM knowledge_items")
    suspend fun countAll(): Int

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

    @Query("SELECT * FROM categories WHERE parent_id IS NULL ORDER BY sort_order, name")
    suspend fun all(): List<CategoryEntity>

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun get(id: String): CategoryEntity?

    @Query("SELECT * FROM categories WHERE parent_id IS NULL ORDER BY sort_order, name")
    fun observeAll(): Flow<List<CategoryEntity>>
}

@Dao
interface TopicDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(topic: TopicEntity)

    @Query("SELECT * FROM topics WHERE last_opened_at IS NOT NULL ORDER BY last_opened_at DESC LIMIT :limit")
    fun observeRecentlyOpened(limit: Int): Flow<List<TopicEntity>>

    @Query("SELECT * FROM topics WHERE id = :id")
    fun observe(id: String): Flow<TopicEntity?>

    /** Existing topic with this name in the category (and under the parent topic, for subtopics). */
    @Query(
        """
        SELECT * FROM topics WHERE category_id = :categoryId AND name = :name COLLATE NOCASE
          AND ((:parentId IS NULL AND parent_topic_id IS NULL) OR parent_topic_id = :parentId)
        LIMIT 1
        """,
    )
    suspend fun find(categoryId: String, parentId: String?, name: String): TopicEntity?
}

@Dao
interface TagDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(tag: TagEntity): Long

    @Query("DELETE FROM knowledge_tags WHERE knowledge_id = :knowledgeId")
    suspend fun unlinkAll(knowledgeId: String)

    @Query("INSERT OR IGNORE INTO knowledge_tags (knowledge_id, tag_id) VALUES (:knowledgeId, :tagId)")
    suspend fun link(knowledgeId: String, tagId: String)

    @Query("INSERT OR IGNORE INTO knowledge_tags (knowledge_id, tag_id) SELECT :knowledgeId, id FROM tags WHERE normalized_name = :normalized")
    suspend fun linkByName(knowledgeId: String, normalized: String)

    @Query("SELECT t.* FROM tags t JOIN knowledge_tags kt ON kt.tag_id = t.id WHERE kt.knowledge_id = :knowledgeId ORDER BY t.name")
    fun observeForItem(knowledgeId: String): Flow<List<TagEntity>>

    @Query("SELECT t.* FROM tags t JOIN knowledge_tags kt ON kt.tag_id = t.id WHERE kt.knowledge_id = :knowledgeId ORDER BY t.name")
    suspend fun forItem(knowledgeId: String): List<TagEntity>
}

@Dao
interface CaptureSourceDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(source: CaptureSourceEntity)

    @Query("SELECT * FROM capture_sources WHERE knowledge_id = :knowledgeId ORDER BY captured_at")
    fun observeForItem(knowledgeId: String): Flow<List<CaptureSourceEntity>>

    @Query("SELECT * FROM capture_sources WHERE knowledge_id = :knowledgeId ORDER BY captured_at")
    suspend fun forItemOnce(knowledgeId: String): List<CaptureSourceEntity>
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

    @Query("SELECT COALESCE(MAX(version), 0) FROM item_versions WHERE knowledge_id = :knowledgeId")
    suspend fun latestVersion(knowledgeId: String): Int

    @Query("SELECT * FROM item_versions WHERE knowledge_id = :knowledgeId ORDER BY version DESC")
    fun observeForItem(knowledgeId: String): Flow<List<ItemVersionEntity>>
}

@Dao
interface AttachmentDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(attachment: com.pkos.core.data.db.entity.AttachmentEntity)

    @Query("SELECT * FROM attachments ORDER BY created_at")
    suspend fun all(): List<com.pkos.core.data.db.entity.AttachmentEntity>

    @Query("SELECT * FROM attachments WHERE knowledge_id = :knowledgeId ORDER BY created_at")
    fun observeForItem(knowledgeId: String): Flow<List<com.pkos.core.data.db.entity.AttachmentEntity>>

    @Query("SELECT * FROM attachments WHERE knowledge_id = :knowledgeId ORDER BY created_at")
    suspend fun forItem(knowledgeId: String): List<com.pkos.core.data.db.entity.AttachmentEntity>

    @Query("SELECT COUNT(*) FROM attachments WHERE local_path = :path")
    suspend fun countByPath(path: String): Int

    @Query("UPDATE attachments SET extraction_status = :status WHERE id = :id")
    suspend fun setExtractionStatus(id: String, status: com.pkos.core.data.model.ExtractionStatus)

    @Query("UPDATE attachments SET extraction_status = :status, extracted_text = :text WHERE id = :id")
    suspend fun setExtracted(id: String, status: com.pkos.core.data.model.ExtractionStatus, text: String?)
}

@Dao
interface ProcessingJobDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(job: com.pkos.core.data.db.entity.ProcessingJobEntity)

    @Query("SELECT * FROM processing_jobs WHERE knowledge_id = :knowledgeId ORDER BY created_at")
    suspend fun forItem(knowledgeId: String): List<com.pkos.core.data.db.entity.ProcessingJobEntity>

    @Query("SELECT * FROM processing_jobs WHERE id = :id")
    suspend fun get(id: String): com.pkos.core.data.db.entity.ProcessingJobEntity?

    @Query("SELECT DISTINCT knowledge_id FROM processing_jobs WHERE state IN ('QUEUED','RUNNING','RETRY_SCHEDULED')")
    suspend fun itemsWithPendingJobs(): List<String>

    @Query("UPDATE processing_jobs SET state = :state, attempts = :attempts, next_run_at = :nextRunAt, last_error_code = :error, updated_at = :at WHERE id = :id")
    suspend fun update(id: String, state: com.pkos.core.data.model.JobState, attempts: Int, nextRunAt: Long?, error: String?, at: Long)
}

@Dao
interface AiRunDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(run: com.pkos.core.data.db.entity.AiRunEntity)

    @Query("SELECT * FROM ai_runs WHERE knowledge_id = :knowledgeId ORDER BY created_at DESC")
    suspend fun forItem(knowledgeId: String): List<com.pkos.core.data.db.entity.AiRunEntity>

    @Query("SELECT * FROM ai_runs WHERE knowledge_id = :knowledgeId ORDER BY created_at DESC LIMIT 1")
    fun observeLatest(knowledgeId: String): Flow<com.pkos.core.data.db.entity.AiRunEntity?>

    @Query("SELECT r.id, r.knowledge_id, k.title, r.provider, r.model, r.prompt_version, r.outcome, r.input_chars, r.created_at FROM ai_runs r LEFT JOIN knowledge_items k ON k.id = r.knowledge_id WHERE r.provider != :local ORDER BY r.created_at DESC LIMIT :limit")
    suspend fun recentNotProvider(local: String, limit: Int): List<AiRunRow>

    @Query("DELETE FROM ai_runs")
    suspend fun deleteAll(): Int
}

@Dao
interface NotionDao {
    @Query("SELECT * FROM notion_mappings WHERE knowledge_id = :id")
    suspend fun mapping(id: String): com.pkos.core.data.db.entity.NotionMappingEntity?

    @Query("SELECT * FROM notion_mappings WHERE knowledge_id = :id")
    fun observeMapping(id: String): Flow<com.pkos.core.data.db.entity.NotionMappingEntity?>

    @Upsert
    suspend fun upsertMapping(m: com.pkos.core.data.db.entity.NotionMappingEntity)

    @Query("SELECT * FROM notion_write_intents WHERE capture_id = :id")
    suspend fun intent(id: String): com.pkos.core.data.db.entity.NotionWriteIntentEntity?

    @Upsert
    suspend fun upsertIntent(i: com.pkos.core.data.db.entity.NotionWriteIntentEntity)

    @Query("SELECT * FROM notion_kept_properties WHERE knowledge_id = :id")
    suspend fun kept(id: String): com.pkos.core.data.db.entity.NotionKeptPropertiesEntity?

    @Upsert
    suspend fun upsertKept(k: com.pkos.core.data.db.entity.NotionKeptPropertiesEntity)

    @Query("SELECT * FROM notion_conflicts WHERE knowledge_id = :id")
    suspend fun conflict(id: String): com.pkos.core.data.db.entity.NotionConflictEntity?

    @Query("SELECT * FROM notion_conflicts WHERE knowledge_id = :id")
    fun observeConflict(id: String): Flow<com.pkos.core.data.db.entity.NotionConflictEntity?>

    @Upsert
    suspend fun upsertConflict(c: com.pkos.core.data.db.entity.NotionConflictEntity)

    @Query("DELETE FROM notion_conflicts WHERE knowledge_id = :id")
    suspend fun clearConflict(id: String)

    @Query("SELECT * FROM sync_jobs WHERE id = :id")
    suspend fun job(id: String): SyncJobEntity?

    @Query("SELECT * FROM sync_jobs WHERE id = :id")
    fun observeJob(id: String): Flow<SyncJobEntity?>

    @Upsert
    suspend fun upsertJob(job: SyncJobEntity)

    /** Sync state only — never touches updated_at, so it cannot itself trigger another sync. */
    @Query("UPDATE knowledge_items SET sync_state = :state WHERE id = :id")
    suspend fun setSyncState(id: String, state: com.pkos.core.data.model.SyncState)

    @Query("UPDATE attachments SET notion_file_upload_id = :uploadId, notion_uploaded_at = :at, notion_skip_reason = NULL WHERE id = :id")
    suspend fun setUpload(id: String, uploadId: String, at: Long)

    @Query("UPDATE attachments SET notion_skip_reason = :reason WHERE id = :id")
    suspend fun setUploadSkipped(id: String, reason: String)

    @Query("UPDATE attachments SET notion_attached = 1 WHERE id IN (:ids)")
    suspend fun markAttached(ids: List<String>)

    /**
     * Items that should be (re)sent: shareable, finished processing, not deleted, and changed since the last
     * verified sync (or never synced). Items waiting for the owner (conflict) are excluded.
     */
    @Query(
        """
        SELECT k.id FROM knowledge_items k
        LEFT JOIN notion_mappings m ON m.knowledge_id = k.id
        WHERE k.deleted_at IS NULL AND k.privacy_scope = 'NORMAL'
          AND k.processing_state IN ('ORGANIZED','NEEDS_REVIEW','EXTRACTED')
          AND k.sync_state NOT IN ('CONFLICT')
          AND (m.knowledge_id IS NULL OR k.updated_at > m.last_synced_at)
        ORDER BY k.created_at
        """,
    )
    suspend fun itemsNeedingSync(): List<String>

    @Query(
        """
        SELECT
          (SELECT COUNT(*) FROM knowledge_items WHERE sync_state = 'NOTION_SYNCED') AS synced,
          (SELECT COUNT(*) FROM knowledge_items WHERE sync_state IN ('NOTION_QUEUED','ERROR_RETRYABLE')) AS queued,
          (SELECT COUNT(*) FROM knowledge_items WHERE sync_state IN ('CONFLICT','ERROR_ACTION_REQUIRED')) AS needs_action
        """,
    )
    fun observeCounts(): Flow<NotionSyncCounts>
}

data class NotionSyncCounts(val synced: Int, val queued: Int, @ColumnInfo(name = "needs_action") val action: Int)

@Dao
interface BackupDao {
    @Upsert
    suspend fun upsert(s: BackupSnapshotEntity)

    @Query("SELECT * FROM backup_snapshots WHERE id = :id")
    suspend fun get(id: String): BackupSnapshotEntity?

    /** The single unfinished backup, if any (resumed instead of starting another). */
    @Query("SELECT * FROM backup_snapshots WHERE status IN ('CREATING','ENCRYPTED','UPLOADING','UPLOADED') ORDER BY created_at DESC LIMIT 1")
    suspend fun unfinished(): BackupSnapshotEntity?

    @Query("SELECT * FROM backup_snapshots WHERE status = 'VERIFIED' ORDER BY created_at DESC")
    suspend fun verified(): List<BackupSnapshotEntity>

    @Query("SELECT * FROM backup_snapshots WHERE status != 'PRUNED' ORDER BY created_at DESC LIMIT 30")
    fun observeRecent(): Flow<List<BackupSnapshotEntity>>

    @Query("SELECT * FROM recovery_metadata ORDER BY created_at DESC LIMIT 1")
    suspend fun recovery(): com.pkos.core.data.db.entity.RecoveryMetadataEntity?

    @Query("SELECT * FROM recovery_metadata ORDER BY created_at DESC LIMIT 1")
    fun observeRecovery(): Flow<com.pkos.core.data.db.entity.RecoveryMetadataEntity?>

    @Upsert
    suspend fun upsertRecovery(r: com.pkos.core.data.db.entity.RecoveryMetadataEntity)
}

data class IdText(val id: String, @ColumnInfo(name = "raw_text") val rawText: String)

/** M6 feature tables (search index, embeddings, links, actions, dismissals, collections). */
@Dao
interface FeatureDao {
    // --- full-text index ---
    // FTS row ↔ item are joined on rowid (the FTS row is stored with the item's own rowid). item_id is not indexed in
    // FTS4, so joining or deleting by item_id scans the whole index for every item: O(n²), ~20 s at 5 000 items (M7).
    @Query("SELECT k.id FROM knowledge_items k LEFT JOIN knowledge_fts f ON f.rowid = k.rowid WHERE k.deleted_at IS NULL AND (f.rowid IS NULL OR f.item_id != k.id OR k.updated_at > f.indexed_at)")
    suspend fun staleIndexIds(): List<String>

    @Query("SELECT rowid FROM knowledge_items WHERE id = :id")
    suspend fun itemRowId(id: String): Long?

    @Query("DELETE FROM knowledge_fts WHERE rowid = :rowId")
    suspend fun deleteFtsRow(rowId: Long)

    /** Drops index rows of deleted items and rows written by the first v6 indexer (random rowids). */
    @Query("DELETE FROM knowledge_fts WHERE rowid NOT IN (SELECT f.rowid FROM knowledge_fts f JOIN knowledge_items k ON k.rowid = f.rowid AND k.id = f.item_id AND k.deleted_at IS NULL)")
    suspend fun pruneFts(): Int

    @Insert
    suspend fun insertFts(row: com.pkos.core.data.db.entity.KnowledgeFtsEntity)

    @Query("SELECT item_id FROM knowledge_fts WHERE knowledge_fts MATCH :match LIMIT :limit")
    suspend fun ftsMatch(match: String, limit: Int): List<String>

    // --- embeddings ---
    @Query("DELETE FROM embeddings WHERE knowledge_id = :id")
    suspend fun deleteEmbeddings(id: String)

    @Query("DELETE FROM embeddings")
    suspend fun deleteAllEmbeddings()

    @Upsert
    suspend fun upsertEmbedding(e: com.pkos.core.data.db.entity.EmbeddingEntity)

    @Query("SELECT * FROM embeddings WHERE model = :model")
    suspend fun embeddings(model: String): List<com.pkos.core.data.db.entity.EmbeddingEntity>

    @Query("SELECT text_hash FROM embeddings WHERE knowledge_id = :id AND model = :model")
    suspend fun embeddingHash(id: String, model: String): String?

    // --- links ---
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLink(l: com.pkos.core.data.db.entity.KnowledgeLinkEntity): Long

    @Query("SELECT * FROM knowledge_links WHERE from_id = :id OR to_id = :id")
    suspend fun linksFor(id: String): List<com.pkos.core.data.db.entity.KnowledgeLinkEntity>

    @Query("SELECT * FROM knowledge_links WHERE from_id = :id OR to_id = :id")
    fun observeLinks(id: String): Flow<List<com.pkos.core.data.db.entity.KnowledgeLinkEntity>>

    @Query("DELETE FROM knowledge_links WHERE id = :id AND origin = 'USER'")
    suspend fun deleteUserLink(id: String)

    // --- actions ---
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAction(a: com.pkos.core.data.db.entity.ActionItemEntity): Long

    @Query("SELECT COUNT(*) FROM action_items WHERE knowledge_id = :id")
    suspend fun actionCount(id: String): Int

    @Query("SELECT * FROM action_items WHERE knowledge_id = :id ORDER BY created_at")
    fun observeActionsFor(id: String): Flow<List<com.pkos.core.data.db.entity.ActionItemEntity>>

    @Query("SELECT a.* FROM action_items a JOIN knowledge_items k ON k.id = a.knowledge_id WHERE k.deleted_at IS NULL AND a.status IN (:statuses) ORDER BY a.status = 'PROPOSED' DESC, COALESCE(a.due_at, 9223372036854775807), a.created_at DESC")
    fun observeActions(statuses: List<String>): Flow<List<com.pkos.core.data.db.entity.ActionItemEntity>>

    @Query("UPDATE action_items SET status = :status, due_at = :dueAt, text = :text, updated_at = :at WHERE id = :id")
    suspend fun updateAction(id: String, status: String, dueAt: Long?, text: String, at: Long)

    @Query("SELECT * FROM action_items WHERE id = :id")
    suspend fun action(id: String): com.pkos.core.data.db.entity.ActionItemEntity?

    // --- dismissals ---
    @Upsert
    suspend fun dismiss(d: com.pkos.core.data.db.entity.DismissalEntity)

    @Query("SELECT key FROM dismissals WHERE key LIKE :prefix || '%'")
    suspend fun dismissedKeys(prefix: String): List<String>

    @Query("SELECT at FROM dismissals WHERE key = :key")
    suspend fun dismissedAt(key: String): Long?

    
    // --- collections ---
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCollection(c: CollectionEntity)

    @Update
    suspend fun updateCollection(c: CollectionEntity)

    @Query("DELETE FROM collections WHERE id = :id")
    suspend fun deleteCollection(id: String)

    @Query("SELECT * FROM collections WHERE is_suggested = 0 OR dismissed = 0 ORDER BY name COLLATE NOCASE")
    fun observeCollections(): Flow<List<CollectionEntity>>

    @Query("SELECT * FROM collections WHERE id = :id")
    suspend fun collection(id: String): CollectionEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addToCollection(i: com.pkos.core.data.db.entity.CollectionItemEntity)

    @Query("DELETE FROM collection_items WHERE collection_id = :collectionId AND knowledge_id = :itemId")
    suspend fun removeFromCollection(collectionId: String, itemId: String)

    @Query("SELECT k.* FROM knowledge_items k JOIN collection_items ci ON ci.knowledge_id = k.id WHERE ci.collection_id = :id AND k.deleted_at IS NULL ORDER BY k.created_at DESC")
    suspend fun manualItems(id: String): List<KnowledgeItemEntity>

    @Query("SELECT collection_id FROM collection_items WHERE knowledge_id = :itemId")
    fun observeCollectionsOf(itemId: String): Flow<List<String>>

    // --- whole-library reads (graph, recommendations, rules, timeline, export) ---
    @Query("SELECT * FROM knowledge_items WHERE deleted_at IS NULL")
    suspend fun liveItems(): List<KnowledgeItemEntity>

    @Query("SELECT kt.knowledge_id AS item_id, t.name AS name FROM knowledge_tags kt JOIN tags t ON t.id = kt.tag_id")
    suspend fun itemTags(): List<ItemName>

    @Query("SELECT * FROM topics")
    suspend fun topics(): List<TopicEntity>

    @Query("UPDATE knowledge_items SET topic_id = :to, updated_at = :at WHERE topic_id = :from")
    suspend fun moveTopic(from: String, to: String, at: Long)

    @Query("UPDATE knowledge_items SET subtopic_id = NULL, updated_at = :at WHERE subtopic_id = :id")
    suspend fun clearSubtopic(id: String, at: Long)

    @Query("UPDATE topics SET parent_topic_id = :to WHERE parent_topic_id = :from")
    suspend fun reparentTopics(from: String, to: String)

    @Query("DELETE FROM topics WHERE id = :id")
    suspend fun deleteTopic(id: String)

    @Query("SELECT * FROM item_versions WHERE changed_at >= :from ORDER BY changed_at DESC")
    suspend fun versionsSince(from: Long): List<ItemVersionEntity>

    @Query("SELECT * FROM item_versions")
    suspend fun allVersions(): List<ItemVersionEntity>
}

data class ItemName(@ColumnInfo(name = "item_id") val itemId: String, val name: String)

data class AiRunRow(
    val id: String,
    @androidx.room.ColumnInfo(name = "knowledge_id") val knowledgeId: String,
    val title: String?,
    val provider: String,
    val model: String,
    @androidx.room.ColumnInfo(name = "prompt_version") val promptVersion: String,
    val outcome: String,
    @androidx.room.ColumnInfo(name = "input_chars") val inputChars: Int,
    @androidx.room.ColumnInfo(name = "created_at") val createdAt: Long,
)
