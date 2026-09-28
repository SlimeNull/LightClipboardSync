package com.lightclipboardsync.android

import android.app.Application

class LightClipboardApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ClipboardEventSession.initialize(this)
        ModuleBridge.initialize(this)
    }
}
