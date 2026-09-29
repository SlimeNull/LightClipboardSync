package com.lightclipboardsync.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.widget.Toast
import android.annotation.SuppressLint
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.Executors

object ModuleBridge {
    val active = MutableStateFlow(false)
    val status = MutableStateFlow(ConnectionState.CONNECTING)
    val logs = MutableStateFlow(emptyList<String>())
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var service: XposedService? = null
    private var app: Context? = null
    @Volatile private var observing = false

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Synchronized
    fun initialize(context: Context) {
        if (app != null) return
        app = context.applicationContext
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != ModuleProtocol.SNAPSHOT) return
                status.value = runCatching {
                    ConnectionState.valueOf(intent.getStringExtra("state") ?: "CONNECTING")
                }.getOrDefault(ConnectionState.CONNECTING)
                logs.value = intent.getStringArrayListExtra("logs")?.takeLast(100) ?: emptyList()
            }
        }
        val filter = IntentFilter(ModuleProtocol.SNAPSHOT)
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, ModuleProtocol.PERMISSION, null, Context.RECEIVER_EXPORTED)
        } else context.registerReceiver(receiver, filter, ModuleProtocol.PERMISSION, null)
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(bound: XposedService) {
                service = bound
                worker.execute {
                    val enabled = runCatching { "system" in bound.scope }.getOrDefault(false)
                    requireNotNull(app).mainExecutor.execute {
                        active.value = enabled
                        ClipboardEventSession.setModuleActive(requireNotNull(app), enabled)
                    }
                    if (enabled) publish(SyncConfig.load(requireNotNull(app)), reconnect = false)
                }
            }

            override fun onServiceDied(dead: XposedService) {
                if (service === dead) service = null
                requireNotNull(app).mainExecutor.execute {
                    active.value = false
                    ClipboardEventSession.setModuleActive(requireNotNull(app), false)
                }
            }
        })
    }

    fun publish(config: SyncConfig, reconnect: Boolean) {
        worker.execute {
            val current = service ?: return@execute
            try {
                val prefs = current.getRemotePreferences(ModuleProtocol.REMOTE_PREFS)
                check(prefs.edit().putString("server_url", config.serverUrl)
                    .putString("user_id", config.userId.toString())
                    .putString("client_id", config.clientId.toString()).commit())
                requestState(reconnect)
            } catch (_: Exception) {
                app?.mainExecutor?.execute {
                    Toast.makeText(app, "模块设置同步失败", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun setObserving(enabled: Boolean) {
        observing = enabled
        requestState()
    }

    private fun requestState(reconnect: Boolean = false) {
        app?.sendBroadcast(Intent(ModuleProtocol.CONTROL).apply {
            setPackage("android")
            putExtra("observing", observing)
            putExtra("reconnect", reconnect)
        })
    }
}
