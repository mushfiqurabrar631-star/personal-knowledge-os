package com.pkos.app.processing

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.pkos.app.PkosApplication
import com.pkos.core.data.processing.ProcessOutcome
import java.util.concurrent.TimeUnit

/** Runs [com.pkos.core.data.processing.ProcessingEngine] for one item; survives app death and reboot (WorkManager). */
class ProcessingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_ITEM) ?: return Result.failure()
        val container = (applicationContext as PkosApplication).container
        return when (val out = container.processing.process(id, container.cloudProvider())) {
            is ProcessOutcome.Retry -> { container.scheduler.enqueue(id, out.delayMs); Result.success() }
            is ProcessOutcome.Done -> {
                container.requestNotionSync(id)
                runCatching { container.search.refreshIndex() }
                container.cloudEmbedder()?.let { e -> runCatching { container.search.indexEmbeddings(e, max = 10) } }
                Result.success()
            }
            else -> Result.success()
        }
    }

    companion object { const val KEY_ITEM = "item_id" }
}

/** One unique chain per item, so the same item is never processed twice at the same time. */
class ProcessingScheduler(private val context: Context) {
    /** Called on app start: re-queues unfinished work without disturbing chains WorkManager still holds. */
    fun ensureScheduled(itemIds: List<String>) = itemIds.forEach { enqueue(it, 0, ExistingWorkPolicy.KEEP) }

    fun enqueue(itemId: String, delayMs: Long = 0, policy: ExistingWorkPolicy = ExistingWorkPolicy.APPEND_OR_REPLACE) {
        val request = OneTimeWorkRequestBuilder<ProcessingWorker>()
            .setInputData(workDataOf(ProcessingWorker.KEY_ITEM to itemId))
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("process-$itemId", policy, request)
    }

    companion object { const val TAG = "pkos-processing" }
}
