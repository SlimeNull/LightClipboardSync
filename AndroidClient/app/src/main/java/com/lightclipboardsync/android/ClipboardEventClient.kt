package com.lightclipboardsync.android

import org.json.JSONObject
import java.net.HttpURLConnection
import java.util.concurrent.atomic.AtomicLong

enum class ConnectionState(val label: String) {
    NOT_CONFIGURED("正在连接"),
    DISCONNECTED("正在连接"),
    CONNECTING("正在连接"),
    CONNECTED("已连接"),
    RECONNECTING("正在连接"),
    FAILED("正在连接"),
    PAUSED("正在连接"),
}

class ClipboardEventClient(
    private val config: () -> SyncConfig?,
    private val write: (ClipboardApi.Download, () -> Boolean) -> Boolean,
    private val onStatus: (ConnectionState) -> Unit,
    private val onLog: (String, String) -> Unit,
    initiallyInteractive: Boolean,
    initialLockedAt: Long = 0,
) {
    companion object {
        private const val SCREEN_OFF_GRACE_MS = 5 * 60 * 1000L
        private const val WAIT_SLICE_MS = 1_000L
    }

    private val gate = Object()
    @Volatile private var enabled = false
    @Volatile private var interactive = initiallyInteractive
    @Volatile private var revision = 0
    @Volatile private var state = ConnectionState.DISCONNECTED
    @Volatile private var localCopyAt = 0L
    private val localVersion = AtomicLong()
    private var screenOffAt = if (!initiallyInteractive) {
        initialLockedAt.takeIf { it > 0 } ?: System.currentTimeMillis()
    } else 0L
    private var idleSuspended = false
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
            idleSuspended = false
            gate.notifyAll()
            worker?.interrupt()
            if (enabled) publish(if (canConnectLocked()) ConnectionState.CONNECTING else ConnectionState.PAUSED)
            connection
        }
        old?.disconnect()
    }

    fun screenChanged(isInteractive: Boolean, time: Long = System.currentTimeMillis()) {
        val old = synchronized(gate) {
            interactive = isInteractive
            if (!isInteractive) {
                screenOffAt = time
                idleSuspended = false
                gate.notifyAll()
                null
            } else {
                screenOffAt = 0L
                idleSuspended = false
                revision++
                gate.notifyAll()
                worker?.interrupt()
                if (enabled) publish(ConnectionState.CONNECTING)
                connection
            }
        }
        old?.disconnect()
    }

    fun noteLocalCopy(time: Long = System.currentTimeMillis()) {
        localCopyAt = time
        localVersion.incrementAndGet()
        noteActivity(time)
    }

    fun noteActivity(time: Long = System.currentTimeMillis()) {
        var old: HttpURLConnection? = null
        synchronized(gate) {
            if (!interactive) {
                screenOffAt = time
                idleSuspended = false
                revision++
                gate.notifyAll()
                worker?.interrupt()
                if (enabled) publish(ConnectionState.CONNECTING)
                old = connection
            }
        }
        old?.disconnect()
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
            while (awaitConnectionWindow()) {
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
                                if (current(attempt) && canConnectLocked()) {
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
                            if (shouldSuspendForIdle(attempt)) break
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) {
                                if (data.isNotEmpty()) {
                                    runCatching { applyEvent(JSONObject(data.joinToString("\n")), api, attempt) }
                                        .onFailure { error ->
                                            val action = if (error is ClipboardApi.HttpError) {
                                                "接收失败 HTTP ${error.code}"
                                            } else "接收失败"
                                            onLog(action, "text")
                                        }
                                    data.clear()
                                }
                            } else if (line.startsWith("data:")) {
                                data += line.removePrefix("data:").trimStart()
                            }
                        }
                    }
                    publishIfCurrent(attempt, if (isIdleSuspended()) ConnectionState.PAUSED else ConnectionState.RECONNECTING)
                } catch (error: Exception) {
                    if (isIdleSuspended()) {
                        publishIfCurrent(attempt, ConnectionState.PAUSED)
                    } else {
                        val action = if (error is ClipboardApi.HttpError) {
                            "连接失败 HTTP ${error.code}"
                        } else "连接失败"
                        onLog(action, "events")
                        publishIfCurrent(attempt, ConnectionState.FAILED)
                    }
                } finally {
                    val old = synchronized(gate) { connection.also { connection = null } }
                    old?.disconnect()
                }
                if (current(attempt) && canConnect()) {
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

    private fun awaitConnectionWindow(): Boolean = synchronized(gate) {
        while (enabled) {
            if (canConnectLocked()) return true
            publish(ConnectionState.PAUSED)
            val remaining = if (interactive || screenOffAt == 0L) {
                WAIT_SLICE_MS
            } else {
                (screenOffAt + SCREEN_OFF_GRACE_MS - System.currentTimeMillis())
                    .coerceAtLeast(WAIT_SLICE_MS)
            }
            try {
                gate.wait(remaining)
            } catch (_: InterruptedException) {
            }
        }
        false
    }

    private fun canConnect(): Boolean = synchronized(gate) { canConnectLocked() }

    private fun canConnectLocked(): Boolean {
        if (!enabled) return false
        if (interactive) return true
        if (screenOffAt == 0L || System.currentTimeMillis() - screenOffAt < SCREEN_OFF_GRACE_MS) {
            return true
        }
        idleSuspended = true
        return false
    }

    private fun shouldSuspendForIdle(attempt: Int): Boolean {
        synchronized(gate) {
            if (current(attempt) && !interactive && screenOffAt > 0L &&
                System.currentTimeMillis() - screenOffAt >= SCREEN_OFF_GRACE_MS) {
                idleSuspended = true
                publish(ConnectionState.PAUSED)
                return true
            }
            return idleSuspended
        }
    }

    private fun isIdleSuspended(): Boolean = synchronized(gate) { idleSuspended }

    private fun recoverLatest(api: ClipboardApi, attempt: Int) {
        if (!interactive) return
        val before = localVersion.get()
        val latest = try {
            api.pull(-1)
        } catch (error: ClipboardApi.HttpError) {
            if (error.code != 404) {
                onLog("恢复失败 HTTP ${error.code}", "events")
                throw error
            }
            null
        }
        if (!current(attempt)) return
        if (latest != null) {
            val position = ClipboardPosition(latest.timestamp, latest.id)
            val accepted = synchronized(gate) {
                RecoveryPolicy.shouldApplyLatest(position, localCopyAt,
                    before == localVersion.get(), newest)
            }
            if (accepted) applyRecord(latest, attempt, before, "恢复")
            else onLog("恢复跳过", latest.type)
        }
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
