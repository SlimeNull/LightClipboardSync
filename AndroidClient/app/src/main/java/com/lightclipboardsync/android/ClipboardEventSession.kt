package com.lightclipboardsync.android

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection

enum class ConnectionState(val label: String) {
    NOT_CONFIGURED("服务器未设置"),
    DISCONNECTED("未连接"),
    CONNECTING("连接中"),
    CONNECTED("已连接"),
    RECONNECTING("重新连接中"),
    FAILED("连接失败"),
}

object ClipboardEventSession {
    val status = MutableStateFlow(ConnectionState.DISCONNECTED)

    private val lock = Any()
    private var visible = false
    private var persistent = false
    @Volatile private var active = false
    @Volatile private var revision = 0
    private var worker: Thread? = null
    private var connection: HttpURLConnection? = null

    fun setVisible(context: Context, enabled: Boolean) = synchronized(lock) {
        visible = enabled
        reconcile(context.applicationContext)
    }

    fun setPersistent(context: Context, enabled: Boolean) = synchronized(lock) {
        persistent = enabled
        reconcile(context.applicationContext)
    }

    fun reconnect(context: Context) = synchronized(lock) {
        revision++
        connection?.disconnect()
        worker?.interrupt()
        if (active) {
            status.value = ConnectionState.CONNECTING
            if (worker == null) startWorker(context.applicationContext)
        }
    }

    private fun reconcile(context: Context) {
        active = visible || persistent
        if (active) {
            if (worker == null) {
                status.value = ConnectionState.CONNECTING
                startWorker(context)
            }
        } else {
            revision++
            connection?.disconnect()
            worker?.interrupt()
            status.value = ConnectionState.DISCONNECTED
        }
    }

    private fun startWorker(context: Context) {
        worker = Thread({ readEvents(context) }, "clipboard-events").apply { start() }
    }

    private fun readEvents(context: Context) {
        try {
            while (active) {
                Thread.interrupted()
                val currentRevision = revision
                val config = SyncConfig.load(context)
                if (config.serverUrl.isBlank()) {
                    publishIfCurrent(currentRevision, ConnectionState.NOT_CONFIGURED)
                    pauseBeforeRetry()
                    continue
                }

                var failed = false
                try {
                    val api = ClipboardApi(config)
                    val stream = api.openEvents { candidate ->
                        synchronized(lock) {
                            if (active && revision == currentRevision) connection = candidate
                            else candidate.disconnect()
                        }
                    }
                    if (!active || revision != currentRevision) continue
                    if (!publishIfCurrent(currentRevision, ConnectionState.CONNECTED)) continue
                    BufferedReader(InputStreamReader(stream.inputStream, Charsets.UTF_8)).use { reader ->
                        val data = mutableListOf<String>()
                        while (active && revision == currentRevision) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) {
                                if (data.isNotEmpty()) {
                                    runCatching {
                                        applyEvent(context, JSONObject(data.joinToString("\n")), api, currentRevision)
                                    }
                                    data.clear()
                                }
                            } else if (line.startsWith("data:")) {
                                data.add(line.removePrefix("data:").trimStart())
                            }
                        }
                    }
                } catch (_: Exception) {
                    if (publishIfCurrent(currentRevision, ConnectionState.FAILED)) {
                        failed = true
                    }
                } finally {
                    synchronized(lock) {
                        connection?.disconnect()
                        connection = null
                    }
                }

                if (active && revision == currentRevision) {
                    if (!failed) publishIfCurrent(currentRevision, ConnectionState.RECONNECTING)
                    pauseBeforeRetry()
                    if (failed) publishIfCurrent(currentRevision, ConnectionState.RECONNECTING)
                }
            }
        } finally {
            synchronized(lock) {
                worker = null
                if (active) startWorker(context) else status.value = ConnectionState.DISCONNECTED
            }
        }
    }

    private fun publishIfCurrent(expectedRevision: Int, state: ConnectionState): Boolean =
        synchronized(lock) {
            if (!active || revision != expectedRevision) false
            else {
                status.value = state
                true
            }
        }

    private fun applyEvent(context: Context, event: JSONObject, api: ClipboardApi, expectedRevision: Int) {
        val id = event.getLong("id")
        val type = event.getString("type")
        if (type !in listOf("text", "image")) return
        when (type) {
            "text" -> {
                val value = if (!event.isNull("content")) event.getString("content")
                    else api.pull(id).bytes.toString(Charsets.UTF_8)
                if (!active || revision != expectedRevision) return
                RemoteClipboardWriter.writeText(context, value)
            }
            "image" -> {
                val download = api.pull(id)
                if (!active || revision != expectedRevision) return
                RemoteClipboardWriter.writeImage(context, download.bytes, download.mimeType, id)
            }
        }
        SyncLog.add(context, "接收", type)
    }

    private fun pauseBeforeRetry() {
        try { Thread.sleep(2000) } catch (_: InterruptedException) { }
    }
}
