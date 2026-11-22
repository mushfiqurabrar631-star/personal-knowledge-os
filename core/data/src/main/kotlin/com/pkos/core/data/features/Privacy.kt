package com.pkos.core.data.features

import androidx.room.withTransaction
import com.pkos.core.data.db.PkosDatabase
import com.pkos.core.data.db.entity.AiRunEntity
import com.pkos.core.data.processing.LocalClassifier
import com.pkos.core.data.repository.CategoryRepository
import java.io.File
import java.util.UUID

/** Cloud sends are recorded in the existing ai_runs audit table (spec 6 + 10.1). Never the text itself — only size. */
object CloudAudit {
    const val ASK = "ask-v1"
    const val EMBED = "embed-v1"
    suspend fun record(db: PkosDatabase, itemId: String, kind: String, model: String, outcome: String, chars: Int, at: Long) {
        runCatching { db.aiRunDao().insert(AiRunEntity(UUID.randomUUID().toString(), itemId, "gemini", model, kind, null, null, outcome, null, chars, at)) }
    }
}

data class CloudActivity(val id: String, val itemId: String, val title: String?, val provider: String, val model: String, val kind: String, val outcome: String, val chars: Int, val at: Long)

data class WipeResult(val items: Int, val files: Int)

/**
 * Privacy controls (spec 10.1) that act only on this phone. Nothing here touches Notion pages or Drive backups.
 * - clearAiHistory: AI audit rows + cloud-made embeddings. Items, tags, topics and summaries stay.
 * - clearCache: temporary files and the rebuildable search index. All saved data stays.
 * - deleteAllData: permanent — every item, file and organisation on this phone (caller also wipes settings/keys).
 */
class PrivacyService(private val db: PkosDatabase, private val attachmentsRoot: File) {
    suspend fun recentCloudActivity(limit: Int = 100): List<CloudActivity> =
        db.aiRunDao().recentNotProvider(LocalClassifier.PROVIDER, limit).map {
            CloudActivity(it.id, it.knowledgeId, it.title, it.provider, it.model, it.promptVersion, it.outcome, it.inputChars, it.createdAt)
        }

    suspend fun clearAiHistory(): Int = db.withTransaction {
        val n = db.aiRunDao().deleteAll()
        db.featureDao().deleteAllEmbeddings()
        n
    }

    /** Returns bytes freed. The search index is rebuilt automatically on the next search. */
    suspend fun clearCache(cacheDirs: List<File>): Long {
        var freed = 0L
        cacheDirs.forEach { dir -> dir.listFiles()?.forEach { f -> freed += f.walkBottomUp().filter { it.isFile }.sumOf { it.length() }; f.deleteRecursively() } }
        db.openHelper.writableDatabase.execSQL("DELETE FROM knowledge_fts")
        return freed
    }

    suspend fun deleteAllData(): WipeResult {
        val items = db.knowledgeDao().countAll()
        val files = attachmentsRoot.walkBottomUp().count { it.isFile }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { db.clearAllTables() } // blocking Room call
        attachmentsRoot.listFiles()?.forEach { it.deleteRecursively() }
        CategoryRepository(db).ensureStarterTaxonomy()
        return WipeResult(items, files)
    }
}
