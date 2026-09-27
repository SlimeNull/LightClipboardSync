package com.lightclipboardsync.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import java.util.concurrent.Executors

class HookReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ClipboardHook.ACTION_HOOK_COPY) return
        if (Build.VERSION.SDK_INT >= 34 && sentFromUid != Process.SYSTEM_UID) return
        val clip = intent.clipData ?: return
        val pending = goAsync()
        executor.execute {
            try {
                val config = SyncConfig.load(context)
                if (config.serverUrl.isNotBlank()) {
                    val content = ClipboardContentReader.read(context, clip)
                    if (content != null) {
                        SyncLog.add(context, "自动发送", content.type)
                        ClipboardApi(config).push(content)
                    }
                }
            } catch (_: Exception) {
                // Manual sync remains available if the source URI or network is unavailable.
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private val executor = Executors.newSingleThreadExecutor()
    }
}
