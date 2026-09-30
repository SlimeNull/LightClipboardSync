package com.lightclipboardsync.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.util.concurrent.Executors

object ManualSync {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun start(context: Context) {
        ClipboardEventSession.noteActivity(context)
        val config = SyncConfig.load(context)
        if (config.serverUrl.isBlank()) {
            toast(context, "请先设置服务器地址")
            return
        }
        val appContext = context.applicationContext
        worker.execute {
            var contentType = "text"
            try {
                val content = ClipboardContentReader.readCurrent(appContext)
                    ?: throw IllegalStateException("剪切板中没有可同步的文本或图片")
                contentType = content.type
                SyncLog.add(appContext, "发送", contentType)
                ClipboardApi(config).push(content)
                toast(appContext, "同步成功")
            } catch (error: Exception) {
                val action = when (error) {
                    is ClipboardApi.HttpError -> "发送失败 HTTP ${error.code}"
                    else -> "发送失败"
                }
                SyncLog.add(appContext, action, contentType)
                toast(appContext, error.message?.takeIf {
                    it.startsWith("剪切板") || it.startsWith("图片")
                } ?: if (!BackgroundClipboard.hasOverlayPermission(appContext)) {
                    "无法读取剪切板，请开启悬浮窗权限"
                } else "同步失败")
            }
        }
    }

    private fun toast(context: Context, message: String) {
        main.post { Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show() }
    }
}
