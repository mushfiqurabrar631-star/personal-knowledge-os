package com.pkos.app.backup

import android.content.Context
import android.content.Intent
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.pkos.app.MainActivity
import com.pkos.app.PkosApplication
import com.pkos.core.data.backup.BackupRunResult
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/** One backup run (resumable). Retries come from the service's own back-off with jitter (spec 11.1). */
class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as PkosApplication).container
        val s = c.settings.settings.first()
        if (s.backupPaused || !s.driveConnected) return Result.success()
        val result = c.backupService.run()
        c.lastBackupResult.value = result
        if (result is BackupRunResult.Retry) BackupScheduler(applicationContext).runNow(result.delayMs, s.backupOnlyWhileCharging, ExistingWorkPolicy.APPEND_OR_REPLACE)
        return Result.success()
    }
}

class BackupScheduler(private val context: Context) {
    private fun constraints(charging: Boolean) =
        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresCharging(charging).build()

    fun runNow(delayMs: Long = 0, onlyCharging: Boolean = false, policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP) {
        val req = OneTimeWorkRequestBuilder<BackupWorker>().setConstraints(constraints(onlyCharging))
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG).build()
        WorkManager.getInstance(context).enqueueUniqueWork(RUN, policy, req)
    }

    /** Daily automatic backup while enabled and not paused. */
    fun setDaily(enabled: Boolean, onlyCharging: Boolean) {
        val wm = WorkManager.getInstance(context)
        if (!enabled) { wm.cancelUniqueWork(DAILY); return }
        val req = PeriodicWorkRequestBuilder<BackupWorker>(1, TimeUnit.DAYS).setConstraints(constraints(onlyCharging)).addTag(TAG).build()
        wm.enqueueUniquePeriodicWork(DAILY, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    fun cancelAll() { WorkManager.getInstance(context).apply { cancelUniqueWork(RUN); cancelUniqueWork(DAILY) } }

    companion object {
        const val TAG = "pkos-backup"
        const val RUN = "pkos-backup-run"
        const val DAILY = "pkos-backup-daily"
    }
}

/** After a restore the open database instance is gone; relaunch the app process cleanly. */
object AppRestart {
    fun restart(context: Context) {
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
        Runtime.getRuntime().exit(0)
    }
}
