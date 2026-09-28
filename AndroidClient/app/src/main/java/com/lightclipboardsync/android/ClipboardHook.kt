package com.lightclipboardsync.android

import android.content.ClipData
import android.content.Context
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

class ClipboardHook : XposedModule() {
    private val handles = mutableListOf<XposedInterface.HookHandle>()
    @Volatile private var runtime: ModuleClipboardRuntime? = null

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        log(Log.INFO, TAG, "Clipboard runtime v3 loaded in ${param.processName}; systemServer=${param.isSystemServer}")
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
            serviceClass.declaredConstructors.forEach { constructor ->
                handles += hook(constructor).intercept { chain ->
                    val result = chain.proceed()
                    initializeRuntime(chain.thisObject)
                    result
                }
            }
            methods.forEach { method ->
                handles += hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val result = chain.proceed()
                        val clip = chain.getArg(0) as? ClipData ?: return@intercept result
                        val sourceUid = chain.getArg(1) as Int
                        try {
                            val activeRuntime = runtime ?: initializeRuntime(chain.thisObject)
                            activeRuntime?.copied(clip, sourceUid, grantItem)
                                ?: log(Log.WARN, TAG, "Clipboard runtime not initialized; restart system required")
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

    @Synchronized
    private fun initializeRuntime(service: Any): ModuleClipboardRuntime? {
        runtime?.let { return it }
        try {
            val context = service.javaClass.getMethod("getContext").invoke(service) as Context
            val write = service.javaClass.getDeclaredMethod("setPrimaryClipInternal",
                ClipData::class.java, Int::class.javaPrimitiveType).apply { isAccessible = true }
            val lockField = service.javaClass.declaredFields.firstOrNull { it.name == "mLock" }
                ?: service.javaClass.declaredFields.first { it.name == "mClipboards" }
            val lock = lockField.apply { isAccessible = true }.get(service) ?: return null
            runtime = ModuleClipboardRuntime(this, context, service, write, lock)
            log(Log.INFO, TAG, "System clipboard network runtime initialized")
            return runtime
        } catch (error: Throwable) {
            log(Log.ERROR, TAG, "Clipboard runtime initialization failed", error)
            return null
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

    companion object {
        private const val TAG = "LightClipboardSync"
    }
}
