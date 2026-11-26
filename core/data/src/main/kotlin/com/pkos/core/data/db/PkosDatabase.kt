package com.pkos.core.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.pkos.core.data.db.dao.AttachmentDao
import com.pkos.core.data.db.dao.CaptureSourceDao
import com.pkos.core.data.db.dao.ProcessingJobDao
import com.pkos.core.data.db.dao.CategoryDao
import com.pkos.core.data.db.dao.CollectionDao
import com.pkos.core.data.db.dao.ItemVersionDao
import com.pkos.core.data.db.dao.KnowledgeDao
import com.pkos.core.data.db.dao.SyncDao
import com.pkos.core.data.db.dao.TagDao
import com.pkos.core.data.db.dao.TopicDao
import com.pkos.core.data.db.entity.AttachmentEntity
import com.pkos.core.data.db.entity.BackupSnapshotEntity
import com.pkos.core.data.db.entity.CaptureSourceEntity
import com.pkos.core.data.db.entity.CategoryEntity
import com.pkos.core.data.db.entity.CollectionEntity
import com.pkos.core.data.db.entity.CollectionItemEntity
import com.pkos.core.data.db.entity.ItemVersionEntity
import com.pkos.core.data.db.entity.KnowledgeItemEntity
import com.pkos.core.data.db.entity.KnowledgeTagEntity
import com.pkos.core.data.db.entity.NotionMappingEntity
import com.pkos.core.data.db.entity.NotionWriteIntentEntity
import com.pkos.core.data.db.entity.ProcessingJobEntity
import com.pkos.core.data.db.entity.SyncJobEntity
import com.pkos.core.data.db.entity.TagEntity
import com.pkos.core.data.db.entity.TopicEntity

/**
 * Canonical local source of truth (spec 6). Schema is exported to core/data/schemas for migration review.
 * Tables for later milestones (ai_runs, relationships, saved_searches, recovery_metadata) are added with
 * explicit migrations when their features are built.
 */
/** Current Room schema version; restores of newer backups are refused. */
const val DB_VERSION = 7

@Database(
    entities = [
        KnowledgeItemEntity::class, CategoryEntity::class, TopicEntity::class, TagEntity::class,
        KnowledgeTagEntity::class, AttachmentEntity::class, CaptureSourceEntity::class,
        ProcessingJobEntity::class, SyncJobEntity::class, NotionMappingEntity::class,
        NotionWriteIntentEntity::class, BackupSnapshotEntity::class, CollectionEntity::class,
        CollectionItemEntity::class, ItemVersionEntity::class, com.pkos.core.data.db.entity.AiRunEntity::class,
        com.pkos.core.data.db.entity.NotionKeptPropertiesEntity::class, com.pkos.core.data.db.entity.NotionConflictEntity::class,
        com.pkos.core.data.db.entity.RecoveryMetadataEntity::class,
        com.pkos.core.data.db.entity.KnowledgeFtsEntity::class, com.pkos.core.data.db.entity.EmbeddingEntity::class,
        com.pkos.core.data.db.entity.KnowledgeLinkEntity::class, com.pkos.core.data.db.entity.ActionItemEntity::class,
        com.pkos.core.data.db.entity.DismissalEntity::class,
    ],
    version = DB_VERSION,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4), AutoMigration(from = 4, to = 5), AutoMigration(from = 5, to = 6), AutoMigration(from = 6, to = 7)],
)
abstract class PkosDatabase : RoomDatabase() {
    abstract fun knowledgeDao(): KnowledgeDao
    abstract fun categoryDao(): CategoryDao
    abstract fun topicDao(): TopicDao
    abstract fun tagDao(): TagDao
    abstract fun captureSourceDao(): CaptureSourceDao
    abstract fun syncDao(): SyncDao
    abstract fun collectionDao(): CollectionDao
    abstract fun itemVersionDao(): ItemVersionDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun featureDao(): com.pkos.core.data.db.dao.FeatureDao
    abstract fun backupDao(): com.pkos.core.data.db.dao.BackupDao
    abstract fun notionDao(): com.pkos.core.data.db.dao.NotionDao
    abstract fun aiRunDao(): com.pkos.core.data.db.dao.AiRunDao
    abstract fun processingJobDao(): ProcessingJobDao

    companion object {
        const val FILE_NAME = "pkos.db"

        fun create(context: Context, name: String = FILE_NAME): PkosDatabase =
            Room.databaseBuilder(context.applicationContext, PkosDatabase::class.java, name)
                // No destructive fallback: losing data is a release blocker (spec 13.3).
                .addCallback(Triggers)
                .build()

        fun inMemory(context: Context): PkosDatabase =
            Room.inMemoryDatabaseBuilder(context.applicationContext, PkosDatabase::class.java).addCallback(Triggers).build()

        /**
         * Spec 8.5 sync state: an edit after a verified backup means the new version is not backed up any more.
         * BACKED_UP → LOCAL_ONLY, SYNCED → NOTION_SYNCED (Notion re-sync sets its own queued state). Created on every
         * open with IF NOT EXISTS, so no schema version change is needed and restored databases get it too.
         */
        const val STALE_BACKUP_TRIGGER = """CREATE TRIGGER IF NOT EXISTS pkos_backup_stale AFTER UPDATE OF updated_at ON knowledge_items
            WHEN NEW.updated_at > OLD.updated_at AND NEW.sync_state IN ('BACKED_UP','SYNCED')
            BEGIN UPDATE knowledge_items SET sync_state = CASE NEW.sync_state WHEN 'BACKED_UP' THEN 'LOCAL_ONLY' ELSE 'NOTION_SYNCED' END WHERE id = NEW.id; END"""

        private object Triggers : RoomDatabase.Callback() {
            override fun onOpen(db: androidx.sqlite.db.SupportSQLiteDatabase) { db.execSQL(STALE_BACKUP_TRIGGER) }
        }
    }
}
