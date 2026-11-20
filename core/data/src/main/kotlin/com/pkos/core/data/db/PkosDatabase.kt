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
@Database(
    entities = [
        KnowledgeItemEntity::class, CategoryEntity::class, TopicEntity::class, TagEntity::class,
        KnowledgeTagEntity::class, AttachmentEntity::class, CaptureSourceEntity::class,
        ProcessingJobEntity::class, SyncJobEntity::class, NotionMappingEntity::class,
        NotionWriteIntentEntity::class, BackupSnapshotEntity::class, CollectionEntity::class,
        CollectionItemEntity::class, ItemVersionEntity::class, com.pkos.core.data.db.entity.AiRunEntity::class,
    ],
    version = 3,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)],
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
    abstract fun aiRunDao(): com.pkos.core.data.db.dao.AiRunDao
    abstract fun processingJobDao(): ProcessingJobDao

    companion object {
        const val FILE_NAME = "pkos.db"

        fun create(context: Context, name: String = FILE_NAME): PkosDatabase =
            Room.databaseBuilder(context.applicationContext, PkosDatabase::class.java, name)
                // No destructive fallback: losing data is a release blocker (spec 13.3).
                .build()

        fun inMemory(context: Context): PkosDatabase =
            Room.inMemoryDatabaseBuilder(context.applicationContext, PkosDatabase::class.java).build()
    }
}
