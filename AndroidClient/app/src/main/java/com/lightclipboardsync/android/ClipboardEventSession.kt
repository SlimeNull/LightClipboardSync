package com.lightclipboardsync.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import kotlinx.coroutines.flow.MutableStateFlow

object ClipboardEventSession {
    val status = MutableStateFlow(ConnectionState.DISCONNECTED)
    private var client: ClipboardEventClient? = null
    private var visible = false
    private var persistent = false

    @Synchronized
    fun initialize(context: Context) {
        if (client != null) return
        val app = context.applicationContext
        val prefs = app.getSharedPreferences("screen_sync", Context.MODE_PRIVATE)
        client = ClipboardEventClient(
            config = { SyncConfig.load(app) },
            write = { record, current -> RemoteClipboardWriter.write(app, record, current) },
            onStatus = { status.value = it },
            onLog = { action, type -> SyncLog.add(app, action, type) },
            initiallyInteractive = app.getSystemService(PowerManager::class.java).isInteractive,
            initialLockedAt = prefs.getLong("locked_at", 0)
        )
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val on = intent.action == Intent.ACTION_SCREEN_ON
                val now = System.currentTimeMillis()
                if (!on) prefs.edit().putLong("locked_at", now).apply()
                client?.screenChanged(on, now)
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else app.registerReceiver(receiver, filter)
    }

    @Synchronized
    fun setVisible(context: Context, enabled: Boolean) {
        initialize(context)
        visible = enabled
        reconcile()
    }

    @Synchronized
    fun setPersistent(context: Context, enabled: Boolean) {
        initialize(context)
        persistent = enabled
        reconcile()
    }

    @Synchronized
    fun setModuleActive(context: Context, enabled: Boolean) {
        initialize(context)
        // The framework module is optional. It must never disable the app's own
        // SSE session or make basic remote clipboard sync depend on LSPosed.
        reconcile()
    }

    fun reconnect(context: Context) {
        initialize(context)
        client?.reconnect(configurationChanged = true)
    }

    fun noteLocalCopy() { client?.noteLocalCopy() }

    private fun reconcile() { client?.setEnabled(visible || persistent) }
}
