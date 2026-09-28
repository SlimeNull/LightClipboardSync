package com.lightclipboardsync.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryPolicyTest {
    @Test
    fun appliesOnlyContentCreatedAfterScreenOff() {
        assertTrue(RecoveryPolicy.shouldApply(
            ClipboardPosition(2_000, 2), 1_000, 0, true, null
        ))
        assertFalse(RecoveryPolicy.shouldApply(
            ClipboardPosition(900, 2), 1_000, 0, true, null
        ))
    }

    @Test
    fun localCopyAfterUnlockWins() {
        assertFalse(RecoveryPolicy.shouldApply(
            ClipboardPosition(2_000, 2), 1_000, 1_500, true, null
        ))
        assertFalse(RecoveryPolicy.shouldApply(
            ClipboardPosition(2_000, 2), 1_000, 0, false, null
        ))
    }

    @Test
    fun doesNotReplayAnAlreadyObservedPosition() {
        assertFalse(RecoveryPolicy.shouldApply(
            ClipboardPosition(2_000, 2), 1_000, 0, true,
            ClipboardPosition(2_000, 2)
        ))
    }
}
