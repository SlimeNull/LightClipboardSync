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
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

class ClipboardHook : XposedModule() {
    private val handles = mutableListOf<XposedInterface.HookHandle>()

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        try {
            val clipboardImpl = Class.forName(
                "com.android.server.clipboard.ClipboardService\$ClipboardImpl",
                false,
                param.classLoader
            )
            val methods = clipboardImpl.declaredMethods.filter { method ->
                method.name == "setPrimaryClip" && method.parameterTypes.contains(ClipData::class.java)
            }
            if (methods.isEmpty()) {
                log(Log.ERROR, TAG, "ClipboardService.setPrimaryClip not found")
                return
            }
            methods.forEach { method ->
                handles += hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val callerUid = Binder.getCallingUid()
                        val clip = chain.args.firstOrNull { it is ClipData } as? ClipData
                        val fromThisApp = chain.args.any { it is String && it == PACKAGE_NAME }
                        val result = chain.proceed()
                        if (clip != null && !fromThisApp) {
                            try {
                                broadcastCopy(chain.thisObject, clip, callerUid)
                            } catch (error: Throwable) {
                                log(Log.ERROR, TAG, "Clipboard broadcast failed", error)
                            }
                        }
                        result
                    }
            }
            log(Log.INFO, TAG, "Hooked ${handles.size} clipboard write method(s)")
        } catch (error: Throwable) {
            log(Log.ERROR, TAG, "Clipboard hook setup failed", error)
        }
    }

    @SuppressLint("MissingPermission") // Runs in system_server with the framework's context.
    private fun broadcastCopy(clipboardImpl: Any, clip: ClipData, callerUid: Int) {
        val service = clipboardImpl.javaClass.getDeclaredField("this\$0").apply {
            isAccessible = true
        }.get(clipboardImpl)
        val context = service.javaClass.getMethod("getContext").invoke(service) as Context
        val intent = Intent(ACTION_HOOK_COPY).apply {
            setClassName(PACKAGE_NAME, "$PACKAGE_NAME.HookReceiver")
            clipData = clip
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.sendBroadcastAsUser(intent, UserHandle.getUserHandleForUid(callerUid))
    }

    companion object {
        private const val TAG = "LightClipboardSync"
        const val PACKAGE_NAME = "com.lightclipboardsync.android"
        const val ACTION_HOOK_COPY = "$PACKAGE_NAME.HOOK_COPY"
    }
}
