package dev.limit.app.core

/** Read-only observation: projected display time is never written into the usage ledger. */
data class CapsuleObservation(
    val packageName: String,
    val date: String,
    val usedMs: Long,
    val observedElapsedMs: Long,
)

data class CapsuleFrame(val packageName: String, val minutes: Long, val nextUpdateMs: Long)

object UsageCapsuleModel {
    fun selectPackage(
        focusedPackage: String?,
        activePackages: Set<String>,
        eligiblePackages: Set<String>,
        screenUsable: Boolean,
        available: Boolean,
        blocking: Boolean,
    ): String? = focusedPackage?.takeIf {
        screenUsable && available && !blocking && it in eligiblePackages && it in activePackages
    }

    fun project(
        observation: CapsuleObservation,
        nowElapsedMs: Long,
        currentDate: String,
        foregroundPackage: String?,
        screenUsable: Boolean,
        available: Boolean,
        blocking: Boolean,
    ): CapsuleFrame? {
        if (!screenUsable || !available || blocking || currentDate != observation.date ||
            foregroundPackage != observation.packageName) return null
        val base = observation.usedMs.coerceAtLeast(0)
        val delta = (nowElapsedMs.coerceAtLeast(0) - observation.observedElapsedMs.coerceAtLeast(0))
            .coerceAtLeast(0)
        val used = base + minOf(delta, Long.MAX_VALUE - base)
        return CapsuleFrame(observation.packageName, used / 60_000, 60_000 - used % 60_000)
    }
}
