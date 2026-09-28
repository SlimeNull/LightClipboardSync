package com.lightclipboardsync.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.concurrent.Executors

class HookReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ClipboardHook.ACTION_HOOK_COPY) return
        val clip = intent.clipData ?: return
        if (clip.itemCount == 0) return
        Log.i(TAG, "Clipboard copy received from system bridge")
        val pending = goAsync()
        executor.execute {
            var type = if (clip.getItemAt(0).uri != null) "image" else "text"
            try {
                val config = SyncConfig.load(context)
                if (config.serverUrl.isBlank()) {
                    SyncLog.add(context, "自动发送未配置", type)
                    return@execute
                }
                val content = ClipboardContentReader.read(context, clip)
                    ?: throw IllegalStateException("Unsupported clipboard type")
                type = content.type
                SyncLog.add(context, "自动发送", type)
                ClipboardApi(config).push(content)
                Log.i(TAG, "Automatic clipboard upload succeeded ($type)")
            } catch (error: Exception) {
                SyncLog.add(context, "自动发送失败", type)
                Log.e(TAG, "Automatic clipboard upload failed ($type, ${error.javaClass.simpleName})")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "LightClipboardSync"
        private val executor = Executors.newSingleThreadExecutor()
    }
}
