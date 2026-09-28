package com.lightclipboardsync.android

data class ClipboardPosition(val timestamp: Long, val id: Long) : Comparable<ClipboardPosition> {
    override fun compareTo(other: ClipboardPosition): Int =
        timestamp.compareTo(other.timestamp).takeIf { it != 0 } ?: id.compareTo(other.id)
}

internal object RecoveryPolicy {
    fun shouldApply(latest: ClipboardPosition, lockedAt: Long, localCopyAt: Long,
                    unchanged: Boolean, newest: ClipboardPosition?): Boolean =
        lockedAt > 0 && latest.timestamp > lockedAt && localCopyAt <= lockedAt &&
            unchanged && (newest == null || latest > newest)
}
