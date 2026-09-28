package com.lightclipboardsync.android

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import java.util.UUID

data class SyncConfig(
    val serverUrl: String,
    val userId: UUID,
    val clientId: UUID,
) {
    fun endpoint(path: String): String = Uri.parse(serverUrl).buildUpon()
        .appendPath(path)
        .build()
        .toString()

    companion object {
        private const val PREFS = "sync_settings"

        fun load(context: Context): SyncConfig {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val userId = prefs.getString("user_id", null)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: UUID.randomUUID()
            val clientId = prefs.getString("client_id", null)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: UUID.randomUUID()
            val config = SyncConfig(prefs.getString("server_url", "") ?: "", userId, clientId)
            config.save(context)
            return config
        }

        fun parse(server: String, user: String, clientId: UUID): SyncConfig? {
            val value = server.trim().trimEnd('/')
            val uri = Uri.parse(value)
            if (uri.scheme !in listOf("http", "https") || uri.host.isNullOrBlank() ||
                uri.userInfo != null || uri.query != null || uri.fragment != null) return null
            val userId = runCatching { UUID.fromString(user.trim()) }.getOrNull() ?: return null
            return SyncConfig(value, userId, clientId)
        }

        fun fromRemote(prefs: SharedPreferences): SyncConfig? {
            val clientId = runCatching { UUID.fromString(prefs.getString("client_id", null)) }.getOrNull()
                ?: return null
            return parse(prefs.getString("server_url", "") ?: "", prefs.getString("user_id", "") ?: "", clientId)
        }

        fun notificationEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean("persistent_notification", false)

        fun setNotificationEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean("persistent_notification", enabled).apply()
        }
    }

    fun save(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("server_url", serverUrl)
            .putString("user_id", userId.toString())
            .putString("client_id", clientId.toString())
            .apply()
    }
}
