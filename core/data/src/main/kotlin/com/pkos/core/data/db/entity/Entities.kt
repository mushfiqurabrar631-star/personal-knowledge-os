package com.pkos.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.pkos.core.data.model.BackupStatus
import com.pkos.core.data.model.CaptureMethod
import com.pkos.core.data.model.ContentType
import com.pkos.core.data.model.ExtractionStatus
import com.pkos.core.data.model.FieldOrigin
import com.pkos.core.data.model.ItemPriority
import com.pkos.core.data.model.ItemStatus
import com.pkos.core.data.model.JobState
import com.pkos.core.data.model.PrivacyScope
import com.pkos.core.data.model.ProcessingJobType
import com.pkos.core.data.model.ProcessingState
import com.pkos.core.data.model.SourcePlatform
import com.pkos.core.data.model.SyncDestination
import com.pkos.core.data.model.SyncState

/** Canonical knowledge record. `id` is the Capture ID (UUID); row ids are never used as external identity. */
@Entity(
    tableName = "knowledge_items",
    indices = [
        Index("created_at"), Index("updated_at"), Index("processing_state"), Index("sync_state"),
        Index("category_id"), Index("topic_id"), Index("deleted_at"), Index("content_hash"), Index("source_url"),
    ],
)
data class KnowledgeItemEntity(
    @PrimaryKey val id: String,
    val title: String,
    @ColumnInfo(name = "raw_text") val rawText: String? = null,
    val summary: String? = null,
    @ColumnInfo(name = "source_url") val sourceUrl: String? = null,
    @ColumnInfo(name = "source_platform") val sourcePlatform: SourcePlatform? = null,
    @ColumnInfo(name = "content_type") val contentType: ContentType? = null,
    @ColumnInfo(name = "category_id") val categoryId: String? = null,
    @ColumnInfo(name = "topic_id") val topicId: String? = null,
    @ColumnInfo(name = "subtopic_id") val subtopicId: String? = null,
    /** Comma-separated purpose labels (learn, reference, build, decide, buy, revisit, inspiration). */
    val purpose: String? = null,
    val priority: ItemPriority = ItemPriority.NORMAL,
    val status: ItemStatus = ItemStatus.INBOX,
    @ColumnInfo(name = "processing_state") val processingState: ProcessingState = ProcessingState.LOCAL_SAVED,
    @ColumnInfo(name = "sync_state") val syncState: SyncState = SyncState.LOCAL_ONLY,
    @ColumnInfo(name = "privacy_scope") val privacyScope: PrivacyScope = PrivacyScope.NORMAL,
    val confidence: Double? = null,
    @ColumnInfo(name = "review_reason") val reviewReason: String? = null,
    val pinned: Boolean = false,
    @ColumnInfo(name = "user_note") val userNote: String? = null,
    /** JSON object field → FieldOrigin name, e.g. {"title":"USER"} (spec 6.2). */
    @ColumnInfo(name = "field_origins") val fieldOrigins: String = "{}",
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
    @ColumnInfo(name = "user_edited") val userEdited: Boolean = false,
    @ColumnInfo(name = "last_opened_at") val lastOpenedAt: Long? = null,
    /** SHA-256 over normalized text + attachment hashes (v2). Used for duplicate suggestions only. */
    @ColumnInfo(name = "content_hash") val contentHash: String? = null,
    /** Set when an earlier item looks identical. Both are kept (spec 11: never discard a capture). */
    @ColumnInfo(name = "duplicate_of_id") val duplicateOfId: String? = null,
)

@Entity(tableName = "categories", indices = [Index("parent_id"), Index(value = ["name"])])
data class CategoryEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "parent_id") val parentId: String? = null,
    val color: String? = null,
    val icon: String? = null,
    @ColumnInfo(name = "is_system") val isSystem: Boolean = false,
    @ColumnInfo(name = "sort_order") val sortOrder: Int = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(
    tableName = "topics",
    indices = [Index("category_id"), Index("parent_topic_id")],
    foreignKeys = [ForeignKey(entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["category_id"], onDelete = ForeignKey.CASCADE)],
)
data class TopicEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "category_id") val categoryId: String,
    @ColumnInfo(name = "parent_topic_id") val parentTopicId: String? = null,
    val name: String,
    val description: String? = null,
    @ColumnInfo(name = "is_user_created") val isUserCreated: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "last_opened_at") val lastOpenedAt: Long? = null,
)

@Entity(tableName = "tags", indices = [Index(value = ["normalized_name"], unique = true)])
data class TagEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "normalized_name") val normalizedName: String,
)

@Entity(
    tableName = "knowledge_tags",
    primaryKeys = ["knowledge_id", "tag_id"],
    indices = [Index("tag_id")],
    foreignKeys = [
        ForeignKey(entity = KnowledgeItemEntity::class, parentColumns = ["id"], childColumns = ["knowledge_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = TagEntity::class, parentColumns = ["id"], childColumns = ["tag_id"], onDelete = ForeignKey.CASCADE),
    ],
)
data class KnowledgeTagEntity(
    @ColumnInfo(name = "knowledge_id") val knowledgeId: String,
    @ColumnInfo(name = "tag_id") val tagId: String,
)

@Entity(
    tableName = "attachments",
    indices = [Index("knowledge_id"), Index("sha256")],
    foreignKeys = [ForeignKey(entity = KnowledgeItemEntity::class, parentColumns = ["id"], childColumns = ["knowledge_id"], onDelete = ForeignKey.CASCADE)],
)
data class AttachmentEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "knowledge_id") val knowledgeId: String,
    @ColumnInfo(name = "mime_type") val mimeType: String?,
    val filename: String?,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    val sha256: String,
    @ColumnInfo(name = "local_path") val localPath: String,
    @ColumnInfo(name = "cloud_file_id") val cloudFileId: String? = null,
    @ColumnInfo(name = "extraction_status") val extractionStatus: ExtractionStatus = ExtractionStatus.PENDING,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    /** Text read from the file (document text or OCR), v3. The original file is never changed. */
    @ColumnInfo(name = "extracted_text") val extractedText: String? = null,
    /** Notion File Upload id (v4). Valid for attaching for ~1 h; after attaching it is never re-sent. */
    @ColumnInfo(name = "notion_file_upload_id") val notionFileUploadId: String? = null,
    @ColumnInfo(name = "notion_uploaded_at") val notionUploadedAt: Long? = null,
    @ColumnInfo(name = "notion_attached", defaultValue = "0") val notionAttached: Boolean = false,
    /** Why the file is not in Notion (e.g. TOO_LARGE); null when attached or pending. */
    @ColumnInfo(name = "notion_skip_reason") val notionSkipReason: String? = null,
)

/** Capture provenance (spec 3.2). */
@Entity(
    tableName = "capture_sources",
    indices = [Index("knowledge_id"), Index("content_hash")],
    foreignKeys = [ForeignKey(entity = KnowledgeItemEntity::class, parentColumns = ["id"], childColumns = ["knowledge_id"], onDelete = ForeignKey.CASCADE)],
)
data class CaptureSourceEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "knowledge_id") val knowledgeId: String,
    @ColumnInfo(name = "capture_method") val captureMethod: CaptureMethod,
    @ColumnInfo(name = "source_app") val sourceApp: String? = null,
    @ColumnInfo(name = "source_uri") val sourceUri: String? = null,
    @ColumnInfo(name = "source_url") val sourceUrl: String? = null,
    @ColumnInfo(name = "mime_type") val mimeType: String? = null,
    @ColumnInfo(name = "original_filename") val originalFilename: String? = null,
    @ColumnInfo(name = "content_hash") val contentHash: String? = null,
    @ColumnInfo(name = "captured_at") val capturedAt: Long,
)

@Entity(tableName = "processing_jobs", indices = [Index("knowledge_id"), Index("state")])
data class ProcessingJobEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "knowledge_id") val knowledgeId: String,
    @ColumnInfo(name = "job_type") val jobType: ProcessingJobType,
    val state: JobState,
    val attempts: Int = 0,
    @ColumnInfo(name = "next_run_at") val nextRunAt: Long? = null,
    @ColumnInfo(name = "last_error_code") val lastErrorCode: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(tableName = "sync_jobs", indices = [Index("knowledge_id"), Index("destination", "state")])
data class SyncJobEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "knowledge_id") val knowledgeId: String,
    val destination: SyncDestination,
    val state: JobState,
    val attempts: Int = 0,
    @ColumnInfo(name = "next_run_at") val nextRunAt: Long? = null,
    @ColumnInfo(name = "last_error_code") val lastErrorCode: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(tableName = "notion_mappings")
data class NotionMappingEntity(
    @PrimaryKey @ColumnInfo(name = "knowledge_id") val knowledgeId: String,
    @ColumnInfo(name = "notion_page_id") val notionPageId: String,
    @ColumnInfo(name = "notion_url") val notionUrl: String?,
    @ColumnInfo(name = "database_id") val databaseId: String,
    @ColumnInfo(name = "data_source_id") val dataSourceId: String,
    @ColumnInfo(name = "remote_hash") val remoteHash: String,
    @ColumnInfo(name = "body_written") val bodyWritten: Boolean,
    @ColumnInfo(name = "last_synced_at") val lastSyncedAt: Long,
)

/** Notion properties the owner chose to keep as they are in Notion for one item (v4, JSON array of names). */
@Entity(tableName = "notion_kept_properties")
data class NotionKeptPropertiesEntity(
    @PrimaryKey @ColumnInfo(name = "knowledge_id") val knowledgeId: String,
    @ColumnInfo(name = "property_names") val propertyNames: String,
)

/** An unresolved Notion conflict shown to the owner (v4). Nothing is written until the owner decides. */
@Entity(tableName = "notion_conflicts")
data class NotionConflictEntity(
    @PrimaryKey @ColumnInfo(name = "knowledge_id") val knowledgeId: String,
    /** REMOTE_EDITED or ADOPTION_REVIEW */
    val kind: String,
    @ColumnInfo(name = "page_id") val pageId: String,
    @ColumnInfo(name = "page_url") val pageUrl: String?,
    /** JSON array of differing property names. */
    val properties: String,
    /** JSON object name → value in Notion. */
    @ColumnInfo(name = "remote_values") val remoteValues: String,
    /** JSON object name → value PKOS would write. */
    @ColumnInfo(name = "local_values") val localValues: String,
    @ColumnInfo(name = "detected_at") val detectedAt: Long,
)

/** Write-ahead journal for the Notion sync engine (ADR 0002). One row per capture: the latest intent. */
@Entity(tableName = "notion_write_intents")
data class NotionWriteIntentEntity(
    @PrimaryKey @ColumnInfo(name = "capture_id") val captureId: String,
    val kind: String,
    @ColumnInfo(name = "properties_hash") val propertiesHash: String,
    @ColumnInfo(name = "page_id") val pageId: String?,
    @ColumnInfo(name = "sent_at") val sentAt: Long,
)

@Entity(tableName = "backup_snapshots", indices = [Index("status", "created_at")])
data class BackupSnapshotEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "file_id") val fileId: String? = null,
    val checksum: String? = null,
    @ColumnInfo(name = "schema_version") val schemaVersion: Int,
    val status: BackupStatus,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long = 0,
)

@Entity(tableName = "collections")
data class CollectionEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String? = null,
    @ColumnInfo(name = "rule_json") val ruleJson: String? = null,
    @ColumnInfo(name = "is_smart") val isSmart: Boolean = false,
    /** Proposed by AI; shown on Home until accepted or dismissed (spec 2.3 #6). */
    @ColumnInfo(name = "is_suggested") val isSuggested: Boolean = false,
    val dismissed: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(
    tableName = "collection_items",
    primaryKeys = ["collection_id", "knowledge_id"],
    indices = [Index("knowledge_id")],
    foreignKeys = [
        ForeignKey(entity = CollectionEntity::class, parentColumns = ["id"], childColumns = ["collection_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = KnowledgeItemEntity::class, parentColumns = ["id"], childColumns = ["knowledge_id"], onDelete = ForeignKey.CASCADE),
    ],
)
data class CollectionItemEntity(
    @ColumnInfo(name = "collection_id") val collectionId: String,
    @ColumnInfo(name = "knowledge_id") val knowledgeId: String,
)

@Entity(
    tableName = "item_versions",
    indices = [Index(value = ["knowledge_id", "version"], unique = true)],
    foreignKeys = [ForeignKey(entity = KnowledgeItemEntity::class, parentColumns = ["id"], childColumns = ["knowledge_id"], onDelete = ForeignKey.CASCADE)],
)
data class ItemVersionEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "knowledge_id") val knowledgeId: String,
    val version: Int,
    @ColumnInfo(name = "snapshot_json") val snapshotJson: String,
    @ColumnInfo(name = "changed_at") val changedAt: Long,
    @ColumnInfo(name = "change_source") val changeSource: FieldOrigin,
)

/** AI audit trail (spec 6: ai_runs). One row per classification attempt, local or cloud. */
@Entity(
    tableName = "ai_runs",
    indices = [Index("knowledge_id"), Index("created_at")],
    foreignKeys = [ForeignKey(entity = KnowledgeItemEntity::class, parentColumns = ["id"], childColumns = ["knowledge_id"], onDelete = ForeignKey.CASCADE)],
)
data class AiRunEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "knowledge_id") val knowledgeId: String,
    val provider: String,
    val model: String,
    @ColumnInfo(name = "prompt_version") val promptVersion: String,
    @ColumnInfo(name = "output_json") val outputJson: String?,
    val confidence: Double?,
    /** OK, INVALID_OUTPUT, RATE_LIMITED, QUOTA, AUTH, NETWORK, ERROR, SKIPPED */
    val outcome: String,
    @ColumnInfo(name = "error_detail") val errorDetail: String? = null,
    @ColumnInfo(name = "input_chars") val inputChars: Int = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)
