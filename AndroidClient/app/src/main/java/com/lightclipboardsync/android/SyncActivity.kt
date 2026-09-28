package com.lightclipboardsync.android

import android.app.Activity
import android.content.Intent
import android.os.Bundle

// External shortcuts need a focused window to read the clipboard on Android 10+.
// This window is transparent and closes as soon as it hands the clip to the worker.
class SyncActivity : Activity() {
    private var started = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startForegroundService(Intent(this, BackgroundSyncService::class.java))
        window.setBackgroundDrawableResource(android.R.color.transparent)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !started) {
            started = true
            ManualSync.start(this)
            finish()
        }
    }
}
