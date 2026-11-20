package com.pkos.app.capture

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.pkos.app.PkosApplication
import com.pkos.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Optional floating capture bubble (R-02). Runs only after the owner turns it on and grants "display over other
 * apps"; a persistent notification shows it is active and offers Stop. It never reads screen content or the
 * clipboard by itself — tapping the bubble just opens [QuickCaptureActivity].
 */
class FloatingCaptureService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var bubble: ImageView? = null
    private val wm by lazy { getSystemService(WindowManager::class.java) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            scope.launch { (application as PkosApplication).container.settings.setFloatingCaptureEnabled(false) }
            stopSelf(); return START_NOT_STICKY
        }
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY }
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification(),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        if (bubble == null) addBubble()
        return START_STICKY
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addBubble() {
        val size = (56 * resources.displayMetrics.density).toInt()
        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = resources.displayMetrics.heightPixels / 3 }
        val view = ImageView(this).apply {
            setImageResource(R.drawable.ic_floating_bubble)
            contentDescription = getString(R.string.floating_bubble_cd)
            elevation = 8f
        }
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        view.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = params.x; startY = params.y; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (abs(dx) > 12 || abs(dy) > 12) moved = true
                    params.x = startX + dx.toInt(); params.y = startY + dy.toInt()
                    wm.updateViewLayout(view, params); true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) openQuickCapture() else {
                        // snap to the nearest edge so the bubble never covers the middle of the screen
                        val w = resources.displayMetrics.widthPixels
                        params.x = if (params.x + size / 2 < w / 2) 0 else w - size
                        wm.updateViewLayout(view, params)
                    }
                    true
                }
                else -> false
            }
        }
        wm.addView(view, params)
        bubble = view
    }

    private fun openQuickCapture() {
        startActivity(Intent(this, QuickCaptureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK))
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.floating_notification_channel), NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 1, Intent(this, FloatingCaptureService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 2, Intent(this, QuickCaptureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_capture)
            .setContentTitle(getString(R.string.floating_notification_text))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(0, getString(R.string.floating_notification_stop), stop)
            .build()
    }

    override fun onDestroy() {
        bubble?.let { runCatching { wm.removeView(it) } }
        bubble = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "floating_capture"
        private const val NOTIFICATION_ID = 41
        const val ACTION_STOP = "com.pkos.app.floating.STOP"

        fun canRun(context: Context) = Settings.canDrawOverlays(context)
        fun start(context: Context) = ContextCompat.startForegroundService(context, Intent(context, FloatingCaptureService::class.java))
        fun stop(context: Context) { context.stopService(Intent(context, FloatingCaptureService::class.java)) }
    }
}
