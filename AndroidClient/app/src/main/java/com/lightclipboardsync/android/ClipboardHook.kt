package com.lightclipboardsync.android

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.UserHandle
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import java.lang.reflect.Method

class ClipboardHook : XposedModule() {
    private val handles = mutableListOf<XposedInterface.HookHandle>()

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        log(Log.INFO, TAG, "Clipboard bridge v2 loaded in ${param.processName}; systemServer=${param.isSystemServer}")
        if (param.isSystemServer) {
            Thread.currentThread().contextClassLoader?.let { installHooks(it, "module loaded") }
        }
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        installHooks(param.classLoader, "system server starting")
    }

    @Synchronized
    private fun installHooks(classLoader: ClassLoader, phase: String) {
        if (handles.isNotEmpty()) return
        try {
            val serviceClass = Class.forName("com.android.server.clipboard.ClipboardService", false, classLoader)
            val methods = ClipboardHookTargets.commitMethods(serviceClass)
            if (methods.isEmpty()) {
                log(Log.ERROR, TAG, "Clipboard commit method not found ($phase)")
                return
            }
            val grantItem = ClipboardHookTargets.grantMethod(serviceClass)?.apply { isAccessible = true }
            deoptimizeCallers(serviceClass, classLoader)
            methods.forEach { method ->
                handles += hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val result = chain.proceed()
                        val clip = chain.getArg(0) as? ClipData ?: return@intercept result
                        val sourceUid = chain.getArg(1) as Int
                        try {
                            dispatchCopy(chain.thisObject, clip, sourceUid, grantItem)
                        } catch (error: Throwable) {
                            log(Log.ERROR, TAG, "Clipboard bridge failed", error)
                        }
                        result
                    }
                log(Log.INFO, TAG, "Installed clipboard hook: $method ($phase)")
            }
        } catch (error: ClassNotFoundException) {
            log(Log.WARN, TAG, "ClipboardService not available yet ($phase)")
        } catch (error: Throwable) {
            log(Log.ERROR, TAG, "Clipboard hook setup failed ($phase)", error)
        }
    }

    private fun deoptimizeCallers(serviceClass: Class<*>, classLoader: ClassLoader) {
        val classes = listOf(serviceClass) + listOf(
            "com.android.server.clipboard.ClipboardService\$ClipboardImpl",
            "android.content.IClipboard\$Stub"
        ).mapNotNull { name -> runCatching { Class.forName(name, false, classLoader) }.getOrNull() }
        classes.flatMap { it.declaredMethods.toList() }.filter { method ->
            method.name.startsWith("setPrimaryClip") || method.name == "checkAndSetPrimaryClip" || method.name == "onTransact"
        }.forEach { method ->
            runCatching { deoptimize(method) }.onFailure {
                log(Log.WARN, TAG, "Could not deoptimize ${method.name}", it)
            }
        }
    }

    @SuppressLint("MissingPermission") // The signature-protected receiver accepts the system sender.
    private fun dispatchCopy(service: Any, clip: ClipData, sourceUid: Int, grantItem: Method?) {
        val context = service.javaClass.getMethod("getContext").invoke(service) as Context
        val identity = Binder.clearCallingIdentity()
        try {
            if (context.packageManager.getPackagesForUid(sourceUid)?.contains(PACKAGE_NAME) == true) return
            val user = UserHandle.getUserHandleForUid(sourceUid)
            val userId = UserHandle::class.java.getDeclaredMethod("getUserId", Int::class.javaPrimitiveType)
                .invoke(null, sourceUid) as Int
            for (index in 0 until clip.itemCount) {
                val item = clip.getItemAt(index)
                if (item.uri != null || item.intent?.data != null) {
                    checkNotNull(grantItem) { "Clipboard URI grant method unavailable" }
                    grantItem.invoke(service, item, sourceUid, PACKAGE_NAME, userId)
                }
            }
            val intent = Intent(ACTION_HOOK_COPY).apply {
                setClassName(PACKAGE_NAME, "$PACKAGE_NAME.HookReceiver")
                clipData = ClipData(clip)
            }
            // The commit hook can run under the clipboard lock; send after leaving that call stack.
            context.mainExecutor.execute {
                val broadcastIdentity = Binder.clearCallingIdentity()
                try {
                    context.sendBroadcastAsUser(intent, user)
                    log(Log.INFO, TAG, "Clipboard copy forwarded to app")
                } catch (error: Throwable) {
                    log(Log.ERROR, TAG, "Clipboard broadcast failed", error)
                } finally {
                    Binder.restoreCallingIdentity(broadcastIdentity)
                }
            }
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    companion object {
        private const val TAG = "LightClipboardSync"
        const val PACKAGE_NAME = "com.lightclipboardsync.android"
        const val ACTION_HOOK_COPY = "$PACKAGE_NAME.HOOK_COPY"
    }
}
