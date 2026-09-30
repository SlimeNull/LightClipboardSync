package com.lightclipboardsync.android

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Binder
import android.os.Build
import android.os.Process
import android.os.UserHandle
import android.util.Log
import java.lang.reflect.Method
import java.util.concurrent.Executors

/**
 * System-server side module runtime.
 *
 * The application owns events, recovery, and remote clipboard writes. This
 * runtime only observes clipboard commits made by other applications and
 * uploads their contents, keeping the optional module independent from the
 * basic client session.
 */
@SuppressLint("PrivateApi")
class ModuleClipboardRuntime(
    private val module: ClipboardHook,
    private val context: Context,
    private val service: Any,
) {
    private val settingsWorker = Executors.newSingleThreadExecutor()
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

    init {
        val control = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                observing = intent.getBooleanExtra("observing", false)
                settingsWorker.execute { refreshConfiguration() }
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(control, IntentFilter(ModuleProtocol.CONTROL),
                ModuleProtocol.PERMISSION, null, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(control, IntentFilter(ModuleProtocol.CONTROL),
                ModuleProtocol.PERMISSION, null)
        }
        settingsWorker.execute { refreshConfiguration() }
    }

    private fun refreshConfiguration() {
        try {
            val prefs = preferences ?: module.getRemotePreferences(ModuleProtocol.REMOTE_PREFS).also {
                it.registerOnSharedPreferenceChangeListener(preferenceListener)
                preferences = it
            }
            val updated = SyncConfig.fromRemote(prefs)
            currentConfig = updated
            state = if (updated == null) ConnectionState.NOT_CONFIGURED else ConnectionState.CONNECTED
            publishSnapshot()
        } catch (error: Throwable) {
            module.log(Log.ERROR, TAG, "Module settings unavailable", error)
            state = ConnectionState.CONNECTING
            publishSnapshot()
        }
    }

    fun copied(clip: ClipData, sourceUid: Int, grantItem: Method?) {
        if (sourceUid == appUid || clip.description.extras?.getBoolean(ModuleProtocol.REMOTE_CLIP) == true) return
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
                    ClipboardApi(config).push(content)
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
