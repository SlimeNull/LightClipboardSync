package com.lightclipboardsync.android

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Binder
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.UserHandle
import android.util.Log
import android.annotation.SuppressLint
import java.lang.reflect.Method
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SuppressLint("UnspecifiedRegisterReceiverFlag")
class ModuleClipboardRuntime(
    private val module: ClipboardHook,
    private val context: Context,
    private val service: Any,
    private val writeMethod: Method,
    private val clipboardLock: Any,
) {
    private val settingsWorker = Executors.newSingleThreadScheduledExecutor()
    private val uploadWorker = Executors.newSingleThreadExecutor()
    private val logGate = Any()
    private val lines = mutableListOf<String>()
    private val appUid = module.moduleApplicationInfo.uid
    @Volatile private var currentConfig: SyncConfig? = null
    @Volatile private var state = ConnectionState.NOT_CONFIGURED
    @Volatile private var observing = false
    private var preferences: SharedPreferences? = null
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        settingsWorker.execute { refreshConfiguration() }
    }
    private val client = ClipboardEventClient(
        config = { currentConfig },
        write = ::writeRemote,
        onStatus = { state = it; publishSnapshot() },
        onLog = ::addLog,
        initiallyInteractive = context.getSystemService(PowerManager::class.java).isInteractive
    )

    init {
        val screens = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val on = intent.action == Intent.ACTION_SCREEN_ON
                val time = System.currentTimeMillis()
                settingsWorker.execute {
                    client.screenChanged(on, time)
                    if (on) refreshConfiguration()
                }
            }
        }
        val control = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                observing = intent.getBooleanExtra("observing", false)
                val reconnect = intent.getBooleanExtra("reconnect", false)
                settingsWorker.execute { refreshConfiguration(reconnect); publishSnapshot() }
            }
        }
        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(screens, screenFilter, Context.RECEIVER_NOT_EXPORTED)
            context.registerReceiver(control, IntentFilter(ModuleProtocol.CONTROL),
                ModuleProtocol.PERMISSION, null, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(screens, screenFilter)
            context.registerReceiver(control, IntentFilter(ModuleProtocol.CONTROL), ModuleProtocol.PERMISSION, null)
        }
        settingsWorker.execute { refreshConfiguration() }
    }

    private fun refreshConfiguration(forceReconnect: Boolean = false) {
        try {
            val prefs = preferences ?: module.getRemotePreferences(ModuleProtocol.REMOTE_PREFS).also {
                it.registerOnSharedPreferenceChangeListener(preferenceListener)
                preferences = it
            }
            val updated = SyncConfig.fromRemote(prefs)
            val changed = updated != currentConfig
            currentConfig = updated
            if (changed || forceReconnect) client.reconnect(configurationChanged = changed)
            client.setEnabled(updated != null)
            if (updated == null) { state = ConnectionState.NOT_CONFIGURED; publishSnapshot() }
        } catch (error: Throwable) {
            module.log(Log.ERROR, TAG, "Module settings unavailable", error)
            state = ConnectionState.CONNECTING
            publishSnapshot()
            if (context.getSystemService(PowerManager::class.java).isInteractive) {
                settingsWorker.schedule({ refreshConfiguration() }, 5, TimeUnit.SECONDS)
            }
        }
    }

    fun copied(clip: ClipData, sourceUid: Int, grantItem: Method?) {
        if (sourceUid == appUid || clip.description.extras?.getBoolean(ModuleProtocol.REMOTE_CLIP) == true) return
        client.noteLocalCopy()
        val identity = Binder.clearCallingIdentity()
        try {
            val targetUser = UserHandle::class.java.getDeclaredMethod("getUserId", Int::class.javaPrimitiveType)
                .invoke(null, Process.myUid()) as Int
            for (index in 0 until clip.itemCount) {
                val item = clip.getItemAt(index)
                if (item.uri != null || item.intent?.data != null) {
                    checkNotNull(grantItem) { "Clipboard URI grant method unavailable" }
                    grantItem.invoke(service, item, sourceUid, context.packageName, targetUser)
                }
            }
            val snapshot = ClipData(clip)
            val config = currentConfig
            uploadWorker.execute {
                var type = if (snapshot.getItemAt(0).uri != null) "image" else "text"
                try {
                    if (config == null) { addLog("自动发送未配置", type); return@execute }
                    val content = ClipboardContentReader.read(context, snapshot)
                        ?: throw IllegalStateException("Unsupported clipboard type")
                    type = content.type
                    addLog("自动发送", type)
                    client.noteUploaded(ClipboardApi(config).push(content))
                    module.log(Log.INFO, TAG, "Direct clipboard upload succeeded ($type)")
                } catch (error: Exception) {
                    addLog("自动发送失败", type)
                    module.log(Log.ERROR, TAG, "Direct clipboard upload failed ($type)", error)
                }
            }
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    private fun writeRemote(record: ClipboardApi.Download, isCurrent: () -> Boolean): Boolean {
        val clip = RemoteClipboardWriter.prepare(context, record)
        return synchronized(clipboardLock) {
            if (!isCurrent()) false
            else {
                writeMethod.invoke(service, clip, appUid)
                true
            }
        }
    }

    private fun addLog(action: String, type: String) {
        synchronized(logGate) {
            lines += SyncLog.formatLine(action, type)
            while (lines.size > 100) lines.removeAt(0)
        }
        publishSnapshot()
    }

    private fun publishSnapshot() {
        if (!observing) return
        context.mainExecutor.execute {
            if (!observing) return@execute
            val identity = Binder.clearCallingIdentity()
            try {
                context.sendBroadcast(Intent(ModuleProtocol.SNAPSHOT).apply {
                    setPackage(ModuleProtocol.PACKAGE_NAME)
                    putExtra("state", state.name)
                    putStringArrayListExtra("logs", synchronized(logGate) { ArrayList(lines) })
                })
            } finally {
                Binder.restoreCallingIdentity(identity)
            }
        }
    }

    companion object { private const val TAG = "LightClipboardSync" }
}
