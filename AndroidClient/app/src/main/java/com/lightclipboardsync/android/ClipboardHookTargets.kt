package com.lightclipboardsync.android

import android.content.ClipData
import java.lang.reflect.Method

internal object ClipboardHookTargets {
    fun accessMethods(service: Class<*>): List<Method> = service.declaredMethods.filter { method ->
        method.name == "clipboardAccessAllowed" && method.returnType == Boolean::class.javaPrimitiveType
    }

    fun commitMethods(service: Class<*>): List<Method> {
        val candidates = service.declaredMethods.filter { method ->
            method.parameterTypes.firstOrNull() == ClipData::class.java &&
                method.parameterTypes.getOrNull(1) == Int::class.javaPrimitiveType
        }
        val locked = candidates.filter { it.name == "setPrimaryClipInternalLocked" }
        return locked.ifEmpty { candidates.filter { it.name == "setPrimaryClipInternal" } }
    }

    fun grantMethod(service: Class<*>): Method? = service.declaredMethods.firstOrNull { method ->
        method.name in setOf("grantItemPermission", "grantItemLocked") &&
            method.parameterTypes.contentEquals(arrayOf(
                ClipData.Item::class.java, Int::class.javaPrimitiveType,
                String::class.java, Int::class.javaPrimitiveType
            ))
    }
}
