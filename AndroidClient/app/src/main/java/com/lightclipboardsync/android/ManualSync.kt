package com.lightclipboardsync.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.util.concurrent.Executors

object ManualSync {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun start(context: Context) {
        val config = SyncConfig.load(context)
        if (config.serverUrl.isBlank()) {
            toast(context, "请先设置服务器地址")
            return
        }
        val clip: ClipData = try {
            context.getSystemService(ClipboardManager::class.java).primaryClip
        } catch (_: Exception) {
            null
        } ?: run {
            toast(context, "无法读取剪切板")
            return
        }
        val appContext = context.applicationContext
        worker.execute {
            try {
                val content = ClipboardContentReader.read(appContext, clip)
                    ?: throw IllegalStateException("剪切板中没有可同步的文本或图片")
                SyncLog.add(appContext, "发送", content.type)
                ClipboardApi(config).push(content)
                toast(appContext, "同步成功")
            } catch (error: Exception) {
                toast(appContext, error.message?.takeIf { it.startsWith("剪切板") } ?: "同步失败")
            }
        }
    }

    private fun toast(context: Context, message: String) {
        main.post { Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show() }
    }
}
