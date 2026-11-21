package com.pkos.app.features

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.pkos.app.MainActivity
import com.pkos.app.PkosApplication
import com.pkos.app.R
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Forgotten-knowledge reminder (spec 9 #8). Opt-in only: the worker is scheduled only when the owner switches reminders
 * on, re-checks the switch on every run, and posts at most one quiet notification (one item) per run.
 */
class ReminderWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as PkosApplication).container
        val s = c.settings.settings.first()
        if (!s.remindersEnabled) return Result.success()
        val item = c.reminders.candidates(s.reminderIntervalDays, limit = 1).firstOrNull() ?: return Result.success()
        val nm = NotificationManagerCompat.from(applicationContext)
        if (!nm.areNotificationsEnabled()) return Result.success() // nothing shown → not marked as shown
        if (android.os.Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(applicationContext, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return Result.success()
        applicationContext.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, applicationContext.getString(R.string.reminder_channel), NotificationManager.IMPORTANCE_LOW))
        val open = Intent(applicationContext, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_ITEM, item.id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(applicationContext, item.id.hashCode(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_capture)
            .setContentTitle(applicationContext.getString(R.string.reminder_title))
            .setContentText(item.title)
            .setContentIntent(pi).setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_LOW).build()
        val shown = try { nm.notify(NOTIFICATION_ID, n); true } catch (_: SecurityException) { false }
        if (shown) c.reminders.markShown(item.id) // only a reminder actually shown is snoozed
        return Result.success()
    }

    companion object { const val CHANNEL = "pkos-reminders"; const val NOTIFICATION_ID = 6106 }
}

class ReminderScheduler(private val context: Context) {
    fun set(enabled: Boolean) {
        val wm = WorkManager.getInstance(context)
        if (!enabled) { wm.cancelUniqueWork(NAME); return }
        wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<ReminderWorker>(1, TimeUnit.DAYS).build())
    }
    companion object { const val NAME = "pkos-reminders-daily" }
}
