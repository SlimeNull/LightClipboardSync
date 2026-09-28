package com.lightclipboardsync.android

import org.json.JSONObject
import java.net.HttpURLConnection
import java.util.concurrent.atomic.AtomicLong

enum class ConnectionState(val label: String) {
    NOT_CONFIGURED("服务器未设置"), DISCONNECTED("未连接"), CONNECTING("连接中"),
    CONNECTED("已连接"), RECONNECTING("重新连接中"), FAILED("连接失败"),
    PAUSED("息屏暂停"), MODULE_WAITING("模块待启动"),
}

class ClipboardEventClient(
    private val config: () -> SyncConfig?,
    private val write: (ClipboardApi.Download, () -> Boolean) -> Boolean,
    private val onStatus: (ConnectionState) -> Unit,
    private val onLog: (String, String) -> Unit,
    initiallyInteractive: Boolean,
    initialLockedAt: Long = 0,
) {
    private val gate = Object()
    @Volatile private var enabled = false
    @Volatile private var interactive = initiallyInteractive
    @Volatile private var revision = 0
    @Volatile private var state = ConnectionState.DISCONNECTED
    @Volatile private var localCopyAt = 0L
    private val localVersion = AtomicLong()
    private var lockedAt = initialLockedAt
    private var recoveryAfter = if (initiallyInteractive && initialLockedAt > 0) initialLockedAt else 0L
    private var newest: ClipboardPosition? = null
    private var worker: Thread? = null
    private var connection: HttpURLConnection? = null

    fun setEnabled(value: Boolean) {
        val old = synchronized(gate) {
            enabled = value
            if (value) {
                if (worker == null) startWorker()
                gate.notifyAll()
                null
            } else {
                revision++
                gate.notifyAll()
                worker?.interrupt()
                publish(ConnectionState.DISCONNECTED)
                connection
            }
        }
        old?.disconnect()
    }

    fun reconnect(configurationChanged: Boolean = false) {
        val old = synchronized(gate) {
            revision++
            if (configurationChanged) newest = null
            gate.notifyAll()
            worker?.interrupt()
            if (enabled) publish(if (interactive) ConnectionState.CONNECTING else ConnectionState.PAUSED)
            connection
        }
        old?.disconnect()
    }

    fun screenChanged(isInteractive: Boolean, time: Long = System.currentTimeMillis()) {
        val old = synchronized(gate) {
            interactive = isInteractive
            if (!isInteractive) lockedAt = time
            val needsConnection = state != ConnectionState.CONNECTED
            if (needsConnection) {
                revision++
                if (isInteractive && lockedAt > 0) recoveryAfter = lockedAt
                publish(if (isInteractive) ConnectionState.CONNECTING else ConnectionState.PAUSED)
                worker?.interrupt()
            }
            gate.notifyAll()
            if (needsConnection) connection else null
        }
        old?.disconnect()
    }

    fun noteLocalCopy(time: Long = System.currentTimeMillis()) {
        localCopyAt = time
        localVersion.incrementAndGet()
    }

    fun noteUploaded(receipt: ClipboardApi.Receipt) = synchronized(gate) {
        val position = ClipboardPosition(receipt.timestamp, receipt.id)
        if (newest == null || position > newest!!) newest = position
    }

    private fun startWorker() {
        worker = Thread(::readEvents, "clipboard-events").apply { start() }
    }

    private fun readEvents() {
        try {
            while (awaitInteractive()) {
                Thread.interrupted()
                val attempt = revision
                val currentConfig = config()
                if (currentConfig == null || currentConfig.serverUrl.isBlank()) {
                    publishIfCurrent(attempt, ConnectionState.NOT_CONFIGURED)
                    pause()
                    continue
                }
                try {
                    val api = ClipboardApi(currentConfig)
                    val stream = api.openEvents(
                        onConnecting = { candidate ->
                            val accept = synchronized(gate) {
                                if (current(attempt) && interactive) {
                                    connection = candidate
                                    true
                                } else false
                            }
                            if (!accept) candidate.disconnect()
                        },
                        onHeaders = { code ->
                            if (code == 200) publishIfCurrent(attempt, ConnectionState.CONNECTED)
                        }
                    )
                    if (!current(attempt)) continue
                    recoverLatest(api, attempt)
                    stream.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                        val data = mutableListOf<String>()
                        while (current(attempt)) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) {
                                if (data.isNotEmpty()) {
                                    runCatching { applyEvent(JSONObject(data.joinToString("\n")), api, attempt) }
                                        .onFailure { onLog("接收失败", "text") }
                                    data.clear()
                                }
                            } else if (line.startsWith("data:")) {
                                data += line.removePrefix("data:").trimStart()
                            }
                        }
                    }
                    publishIfCurrent(attempt, if (interactive) ConnectionState.RECONNECTING else ConnectionState.PAUSED)
                } catch (_: Exception) {
                    publishIfCurrent(attempt, if (interactive) ConnectionState.FAILED else ConnectionState.PAUSED)
                } finally {
                    val old = synchronized(gate) { connection.also { connection = null } }
                    old?.disconnect()
                }
                if (current(attempt) && interactive) {
                    pause()
                    publishIfCurrent(attempt, ConnectionState.RECONNECTING)
                }
            }
        } finally {
            synchronized(gate) {
                worker = null
                if (enabled) startWorker() else publish(ConnectionState.DISCONNECTED)
            }
        }
    }

    private fun awaitInteractive(): Boolean = synchronized(gate) {
        while (enabled && !interactive) {
            publish(ConnectionState.PAUSED)
            try { gate.wait() } catch (_: InterruptedException) { }
        }
        enabled
    }

    private fun recoverLatest(api: ClipboardApi, attempt: Int) {
        val after = synchronized(gate) { recoveryAfter }
        if (after <= 0 || !interactive) return
        val before = localVersion.get()
        val latest = try { api.pull(-1) } catch (error: ClipboardApi.HttpError) {
            if (error.code != 404) throw error
            null
        }
        if (!current(attempt)) return
        if (latest != null) {
            val position = ClipboardPosition(latest.timestamp, latest.id)
            val accepted = synchronized(gate) {
                RecoveryPolicy.shouldApply(position, after, localCopyAt,
                    before == localVersion.get(), newest)
            }
            if (accepted) applyRecord(latest, attempt, before, "开屏补取")
        }
        synchronized(gate) { if (recoveryAfter == after) recoveryAfter = 0 }
    }

    private fun applyEvent(event: JSONObject, api: ClipboardApi, attempt: Int) {
        val type = event.getString("type")
        if (type !in listOf("text", "image")) return
        val before = localVersion.get()
        val record = if (type == "text" && !event.isNull("content")) {
            ClipboardApi.Download(event.getString("content").toByteArray(Charsets.UTF_8),
                "text/plain; charset=utf-8", event.getLong("id"), event.optLong("timestamp", 0), type)
        } else api.pull(event.getLong("id"))
        applyRecord(record, attempt, before, "接收")
    }

    private fun applyRecord(record: ClipboardApi.Download, attempt: Int, before: Long, action: String) {
        val position = ClipboardPosition(record.timestamp, record.id)
        val isCurrent = {
            current(attempt) && localVersion.get() == before &&
                (record.timestamp == 0L || record.timestamp >= localCopyAt)
        }
        val fresh = synchronized(gate) { newest == null || position > newest!! }
        if (!fresh || !isCurrent()) return
        if (write(record, isCurrent)) {
            synchronized(gate) { if (newest == null || position > newest!!) newest = position }
            onLog(action, record.type)
        }
    }

    private fun current(attempt: Int): Boolean = enabled && revision == attempt

    private fun publishIfCurrent(attempt: Int, value: ConnectionState) = synchronized(gate) {
        if (current(attempt)) publish(value)
    }

    private fun publish(value: ConnectionState) {
        if (state != value) {
            state = value
            onStatus(value)
        }
    }

    private fun pause() {
        try { Thread.sleep(2000) } catch (_: InterruptedException) { }
    }
}
