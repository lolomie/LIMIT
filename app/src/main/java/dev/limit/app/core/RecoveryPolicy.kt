package dev.limit.app.core

/** Three short retries per cycle; subsequent events may reopen a cycle after a cooldown. */
class RecoveryPolicy {
    private var attempts = 0
    private var reopenAt = Long.MAX_VALUE
    fun reset() { attempts = 0; reopenAt = Long.MAX_VALUE }
    fun failureDelay(now: Long): Long? {
        if (attempts < 3) return longArrayOf(1_000, 5_000, 15_000)[attempts++]
        reopenAt = now + 60_000
        return null
    }
    fun allowEvent(now: Long): Boolean {
        if (attempts < 3 || reopenAt == Long.MAX_VALUE) return true
        if (now < reopenAt) return false
        reset()
        return true
    }
}
