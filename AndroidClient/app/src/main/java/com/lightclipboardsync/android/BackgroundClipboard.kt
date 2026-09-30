package com.lightclipboardsync.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.app.ActivityManager
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Android 10+ restricts clipboard reads from a background process. When the
 * app has overlay permission, a 1px invisible focusable window gives the app
 * a short foreground window for the read. Direct access remains the first
 * choice and is enough while the app is already visible.
 */
object BackgroundClipboard {
    private const val FOCUS_DELAY_MS = 20L
    private const val READ_TIMEOUT_MS = 500L
    private val mainHandler = Handler(Looper.getMainLooper())

    fun hasOverlayPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            Settings.canDrawOverlays(context.applicationContext)

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val power = context.getSystemService(PowerManager::class.java) ?: return false
        return power.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun requestIgnoreBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || isIgnoringBatteryOptimizations(context)) {
            return true
        }
        return runCatching {
            context.startActivity(android.content.Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                android.net.Uri.parse("package:${context.packageName}")
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.getOrDefault(false)
    }

    fun readPrimaryClip(context: Context): ClipData? {
        val app = context.applicationContext
        val clipboard = app.getSystemService(ClipboardManager::class.java) ?: return null
        val direct = runCatching { clipboard.primaryClip }.getOrNull()
        if (direct?.itemCount ?: 0 > 0) return direct
        if (!hasOverlayPermission(app)) return direct
        return readWithFocusedOverlay(app, clipboard) ?: direct
    }

    /**
     * ColorOS uses the WRITE_CLIPBOARD AppOp and silently ignores writes from a
     * background UID. A foreground service does not satisfy that check. Do not
     * report success unless the app is actually allowed to write right now.
     */
    fun writePrimaryClip(context: Context, clip: ClipData): Boolean {
        val app = context.applicationContext
        val clipboard = app.getSystemService(ClipboardManager::class.java) ?: return false
        if (!canWritePrimaryClip(app)) {
            throw SecurityException("系统拒绝后台写入剪贴板（WRITE_CLIPBOARD 仅限前台）")
        }
        try {
            clipboard.setPrimaryClip(clip)
            return true
        } catch (error: SecurityException) {
            throw error
        } catch (_: RuntimeException) {
            return false
        }
    }

    private fun canWritePrimaryClip(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        val appOps = context.getSystemService(android.app.AppOpsManager::class.java) ?: return true
        val mode = appOps.unsafeCheckOpNoThrow(
            "android:write_clipboard", Process.myUid(), context.packageName
        )
        return when (mode) {
            android.app.AppOpsManager.MODE_ALLOWED,
            android.app.AppOpsManager.MODE_DEFAULT -> true
            android.app.AppOpsManager.MODE_FOREGROUND -> isAppForeground(context)
            else -> false
        }
    }

    private fun isAppForeground(context: Context): Boolean {
        val processes = context.getSystemService(ActivityManager::class.java)
            ?.runningAppProcesses ?: return false
        return processes.any { process ->
            process.uid == Process.myUid() &&
                process.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        }
    }

    private fun readWithFocusedOverlay(context: Context, clipboard: ClipboardManager): ClipData? {
        var result: ClipData? = null
        runWithFocusedOverlay(context) {
            result = runCatching { clipboard.primaryClip }.getOrNull()
        }
        return result
    }

    private fun runWithFocusedOverlay(context: Context, action: () -> Unit): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return runWithOverlayImmediate(context, action)
        }
        val latch = CountDownLatch(1)
        var success = false
        mainHandler.post {
            if (!hasOverlayPermission(context)) {
                latch.countDown()
                return@post
            }
            val windowManager = context.getSystemService(WindowManager::class.java)
            if (windowManager == null) {
                latch.countDown()
                return@post
            }
            val view = View(context).apply {
                isFocusable = true
                isFocusableInTouchMode = true
            }
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            val params = WindowManager.LayoutParams(
                1,
                1,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT,
            ).apply {
                alpha = 0f
                gravity = Gravity.START or Gravity.TOP
            }
            try {
                windowManager.addView(view, params)
                params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                windowManager.updateViewLayout(view, params)
                view.requestFocus()
                mainHandler.postDelayed({
                    try {
                        action()
                        success = true
                    } catch (_: Throwable) {
                        success = false
                    } finally {
                        runCatching { windowManager.removeView(view) }
                        latch.countDown()
                    }
                }, FOCUS_DELAY_MS)
            } catch (_: Throwable) {
                runCatching { windowManager.removeView(view) }
                latch.countDown()
            }
        }
        return try {
            latch.await(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS) && success
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun runWithOverlayImmediate(context: Context, action: () -> Unit): Boolean {
        if (!hasOverlayPermission(context)) return false
        val windowManager = context.getSystemService(WindowManager::class.java) ?: return false
        val view = View(context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            1,
            1,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            alpha = 0f
            gravity = Gravity.START or Gravity.TOP
        }

        return try {
            windowManager.addView(view, params)
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            windowManager.updateViewLayout(view, params)
            view.requestFocus()
            action()
            true
        } catch (_: Throwable) {
            false
        } finally {
            runCatching { windowManager.removeView(view) }
        }
    }
}
