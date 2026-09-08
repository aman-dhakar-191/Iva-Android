package com.jarvis.android.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.jarvis.android.JarvisApp
import com.jarvis.android.R
import com.jarvis.android.ui.MainActivity

/**
 * Keeps the Jarvis session alive while the app is not in the foreground.
 *
 * There is no second WebSocket here. The socket lives in the WebView, and a
 * WebView that belongs to a *stopped* Activity keeps its JavaScript running —
 * what actually kills such a session is the process being frozen or reclaimed
 * (Android 8+ background limits, and the cached-app freezer on 12+). Holding a
 * foreground service moves the process out of the cached bucket, so the page's
 * own socket, timers and microphone capture keep running untouched. That is
 * deliberately the whole job: duplicating the socket natively would mean
 * duplicating the gateway's auth and event protocol for no gain.
 *
 * The service is typed `microphone` because the page's `getUserMedia` capture
 * continues while it runs; on Android 14+ that type additionally requires the
 * RECORD_AUDIO permission to already be granted at startForeground() time, which
 * is why [start] refuses to launch without it.
 */
class JarvisSessionService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        // The typed overload only exists from Q onwards, and passing the type
        // without the permission is what crashes the process on 14+ — start()
        // gates on it, but the grant can be revoked while we are running.
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && hasMicPermission(this)) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }

        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
        } catch (e: Exception) {
            // e.g. ForegroundServiceStartNotAllowedException when something races
            // us into the background before the promotion lands.
            Log.w(TAG, "startForeground refused", e)
            stopSelf()
            return START_NOT_STICKY
        }

        acquireWakeLock()
        return START_STICKY
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService<PowerManager>()
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Jarvis::session")
            ?.apply { setReferenceCounted(false); acquire(WAKE_LOCK_TIMEOUT_MS) }
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, JarvisSessionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, JarvisApp.CHANNEL_SESSION)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.session_notification_title))
            .setContentText(getString(R.string.session_notification_text))
            .setContentIntent(contentIntent)
            .addAction(0, getString(R.string.stop), stopIntent)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    override fun onDestroy() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        running = false
        super.onDestroy()
    }

    companion object {
        private const val TAG = "JarvisSessionService"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_STOP = "com.jarvis.android.action.STOP_SESSION"
        private const val WAKE_LOCK_TIMEOUT_MS = 4L * 60 * 60 * 1000

        @Volatile
        private var running = false

        fun isRunning(): Boolean = running

        fun hasMicPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

        /**
         * Returns false when the microphone grant is missing, since a
         * `microphone`-typed foreground service cannot legally start without it
         * on Android 14+.
         */
        fun start(context: Context): Boolean {
            if (!hasMicPermission(context)) return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(Intent(context, JarvisSessionService::class.java))
            } else {
                context.startService(Intent(context, JarvisSessionService::class.java))
            }
            return true
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, JarvisSessionService::class.java))
        }
    }
}
