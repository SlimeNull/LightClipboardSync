package com.lightclipboardsync.android

import android.net.Uri
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

class ClipboardApi(private val config: SyncConfig) {
    data class Download(val bytes: ByteArray, val mimeType: String, val id: Long,
                        val timestamp: Long, val type: String)
    data class Receipt(val id: Long, val timestamp: Long)
    class HttpError(val code: Int, message: String) : IOException(message)

    private fun connection(url: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        val credential = "${config.userId.toString().lowercase()}:"
        connection.setRequestProperty("Authorization", "Basic " +
            Base64.getEncoder().encodeToString(credential.toByteArray(Charsets.UTF_8)))
        connection.setRequestProperty("X-Client-ID", config.clientId.toString())
        connection.connectTimeout = 5000
        connection.readTimeout = 25000
        return connection
    }

    fun push(content: ClipboardContent): Receipt {
        val url = Uri.parse(config.endpoint("push")).buildUpon()
            .appendQueryParameter("type", content.type).build().toString()
        val request = connection(url)
        request.setRequestProperty("Content-Type", content.mimeType)
        request.readTimeout = 10000
        request.requestMethod = "POST"
        request.doOutput = true
        request.setFixedLengthStreamingMode(content.bytes.size)
        try {
            request.outputStream.use { it.write(content.bytes) }
            if (request.responseCode != 201) throw HttpError(request.responseCode, "上传失败：HTTP ${request.responseCode}")
            val event = JSONObject(request.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
            return Receipt(event.getLong("id"), event.optLong("timestamp", 0))
        } finally {
            request.disconnect()
        }
    }

    fun pull(id: Long): Download {
        val url = Uri.parse(config.endpoint("pull")).buildUpon()
            .appendQueryParameter("id", id.toString()).build().toString()
        val request = connection(url)
        request.readTimeout = 10000
        try {
            if (request.responseCode != 200) throw HttpError(request.responseCode, "下载失败：HTTP ${request.responseCode}")
            val mime = request.contentType ?: "application/octet-stream"
            return Download(request.inputStream.use { it.readBytes() }, mime,
                request.getHeaderField("X-Clipboard-ID")?.toLongOrNull() ?: id,
                request.getHeaderField("X-Clipboard-Timestamp")?.toLongOrNull() ?: 0,
                request.getHeaderField("X-Clipboard-Type") ?: if (mime.startsWith("image/")) "image" else "text")
        } finally {
            request.disconnect()
        }
    }

    fun openEvents(onConnecting: (HttpURLConnection) -> Unit = {},
                   onHeaders: (Int) -> Unit = {}): HttpURLConnection =
        connection(config.endpoint("events")).apply {
            setRequestProperty("Accept", "text/event-stream")
            setRequestProperty("Accept-Encoding", "identity")
            readTimeout = 30000
            onConnecting(this)
            try {
                val code = responseCode
                onHeaders(code)
                if (code != 200) {
                    throw HttpError(code, "事件连接失败：HTTP $code")
                }
            } catch (error: Exception) {
                disconnect()
                throw error
            }
        }
}
