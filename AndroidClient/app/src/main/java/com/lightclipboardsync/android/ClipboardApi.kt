package com.lightclipboardsync.android

import android.net.Uri
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

class ClipboardApi(private val config: SyncConfig) {
    data class Download(val bytes: ByteArray, val mimeType: String)

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

    fun push(content: ClipboardContent) {
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
            if (request.responseCode != 201) throw IOException("上传失败：HTTP ${request.responseCode}")
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
            if (request.responseCode != 200) throw IOException("下载失败：HTTP ${request.responseCode}")
            return Download(request.inputStream.use { it.readBytes() }, request.contentType ?: "application/octet-stream")
        } finally {
            request.disconnect()
        }
    }

    fun openEvents(onConnecting: (HttpURLConnection) -> Unit = {}): HttpURLConnection =
        connection(config.endpoint("events")).apply {
            setRequestProperty("Accept", "text/event-stream")
            readTimeout = 30000
            onConnecting(this)
            try {
                if (responseCode != 200) {
                    throw IOException("事件连接失败：HTTP $responseCode")
                }
            } catch (error: Exception) {
                disconnect()
                throw error
            }
        }
}
