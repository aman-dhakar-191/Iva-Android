package com.jarvis.android

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.content.getSystemService

class JarvisApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val manager = getSystemService<NotificationManager>() ?: return

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SESSION,
                getString(R.string.notification_channel_session),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_session_desc)
                setShowBadge(false)
            }
        )

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_BRIDGE,
                getString(R.string.notification_channel_bridge),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = getString(R.string.notification_channel_bridge_desc)
            }
        )
    }

    companion object {
        /** Low-importance channel for the ongoing foreground-service notification. */
        const val CHANNEL_SESSION = "jarvis_session"

        /** Default-importance channel for alerts the web app raises over the bridge. */
        const val CHANNEL_BRIDGE = "jarvis_bridge"
    }
}
