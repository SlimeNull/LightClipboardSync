package com.lightclipboardsync.android

import android.content.Context
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SyncLog {
    const val PREFS = "sync_log"
    const val KEY = "entries"
    private val format = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

    @Synchronized
    fun formatLine(action: String, type: String): String =
        "${format.format(Date())}  $action  ${if (type == "image") "图片" else "文本"}"

    @Synchronized
    fun add(context: Context, action: String, type: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val entries = runCatching { JSONArray(prefs.getString(KEY, "[]")) }.getOrDefault(JSONArray())
        entries.put(formatLine(action, type))
        val recent = JSONArray()
        for (index in maxOf(0, entries.length() - 100) until entries.length()) {
            recent.put(entries.getString(index))
        }
        prefs.edit().putString(KEY, recent.toString()).apply()
    }

    @Synchronized
    fun lines(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val entries = runCatching { JSONArray(prefs.getString(KEY, "[]")) }.getOrDefault(JSONArray())
        return (0 until entries.length()).map { entries.getString(it) }
    }
}
