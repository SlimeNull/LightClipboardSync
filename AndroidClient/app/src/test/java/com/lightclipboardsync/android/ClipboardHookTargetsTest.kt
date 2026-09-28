package com.lightclipboardsync.android

import android.content.ClipData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClipboardHookTargetsTest {
    @Test
    fun android10SelectsTheOuterCommitWithoutProfileDuplicates() {
        val methods = ClipboardHookTargets.commitMethods(LegacyClipboard::class.java)
        assertEquals(1, methods.size)
        assertEquals("setPrimaryClipInternal", methods.single().name)
        assertEquals(ClipData::class.java, methods.single().parameterTypes.first())
    }

    @Test
    fun android16PrefersTheLockedCommitOverItsWrapper() {
        val methods = ClipboardHookTargets.commitMethods(ModernClipboard::class.java)
        assertEquals(1, methods.size)
        assertEquals("setPrimaryClipInternalLocked", methods.single().name)
        assertEquals(4, methods.single().parameterCount)
    }

    @Test
    fun uriGrantsSupportBothAndroidMethodNames() {
        assertEquals("grantItemLocked", ClipboardHookTargets.grantMethod(LegacyClipboard::class.java)?.name)
        assertEquals("grantItemPermission", ClipboardHookTargets.grantMethod(ModernClipboard::class.java)?.name)
        assertNull(ClipboardHookTargets.grantMethod(String::class.java))
    }

    @Suppress("UNUSED_PARAMETER")
    private class LegacyClipboard {
        fun setPrimaryClipInternal(clip: ClipData?, uid: Int) {}
        fun setPrimaryClipInternal(profile: Any, clip: ClipData?, uid: Int) {}
        fun grantItemLocked(item: ClipData.Item, sourceUid: Int, packageName: String, userId: Int) {}
    }

    @Suppress("UNUSED_PARAMETER")
    private class ModernClipboard {
        fun setPrimaryClipInternal(clip: ClipData?, uid: Int) {}
        fun setPrimaryClipInternalLocked(clip: ClipData?, uid: Int, deviceId: Int, source: String?) {}
        fun setPrimaryClipInternalLocked(profile: Any, clip: ClipData?, uid: Int, source: String?) {}
        fun grantItemPermission(item: ClipData.Item, sourceUid: Int, packageName: String, userId: Int) {}
    }
}
