package com.lightclipboardsync.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED ||
            !SyncConfig.notificationEnabled(context)) return
        runCatching {
            context.startForegroundService(Intent(context, BackgroundSyncService::class.java))
        }
    }
}
