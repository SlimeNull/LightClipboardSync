package com.lightclipboardsync.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

class BackgroundSyncService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1001, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1001, notification())
        }
        ClipboardEventSession.setPersistent(this, true)
        if (intent?.action == ACTION_SYNC_CLIPBOARD) {
            ManualSync.start(this)
        }
        return START_STICKY
    }

    private fun createChannel() {
        val channel = NotificationChannel("clipboard_sync", getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notification(): Notification {
        val contentIntent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val contentPending = PendingIntent.getActivity(this, 1001, contentIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val syncIntent = Intent(this, BackgroundSyncService::class.java)
            .setAction(ACTION_SYNC_CLIPBOARD)
        val syncPending = PendingIntent.getService(this, 1002, syncIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, "clipboard_sync")
            .setSmallIcon(R.drawable.ic_clipboard)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(contentPending)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(R.drawable.ic_clipboard,
                getString(R.string.sync_clipboard), syncPending).build())
            .build()
    }

    override fun onDestroy() {
        ClipboardEventSession.setPersistent(this, false)
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Keep the sync service independent from the launcher task. Some
        // vendors stop the process after a task swipe, so ask the system to
        // recreate the foreground service as well.
        runCatching {
            startForegroundService(Intent(this, BackgroundSyncService::class.java))
        }
    }
}
