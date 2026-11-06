package dev.limit.app.core

enum class UsageEventType { RESUME, PAUSE, STOP, SCREEN_ON, SCREEN_OFF, LOCK, UNLOCK, SHUTDOWN, STARTUP }

data class UsageEvent(
    val timestampMs: Long,
    val packageName: String? = null,
    val className: String? = null,
    val type: UsageEventType,
)

data class UsageExclusion(val packageName: String, val startMs: Long, val endMs: Long)

data class UsageSnapshot(val totals: Map<String, Long>, val activePackages: Set<String>)

/**
 * Replays canonical OS events, clipping to [startMs, endMs). Earlier events seed
 * the boundary state. Resumed classes are unioned within each package; separate
 * resumed packages accrue concurrently, as Android multi-resume permits.
 */
object UsageEventReducer {
    fun reduce(
        events: List<UsageEvent>,
        startMs: Long,
        endMs: Long,
        exclusions: List<UsageExclusion> = emptyList(),
    ): UsageSnapshot {
        require(endMs >= startMs) { "Usage window is reversed" }
        val resumed = mutableMapOf<String, MutableSet<String>>()
        val totals = mutableMapOf<String, Long>()
        val excluded = exclusions.filter { it.endMs > it.startMs }
            .groupBy { it.packageName }
        var interactive = true
        var unlocked = true
        var cursor = startMs

        fun accrue(until: Long) {
            if (until <= cursor) return
            if (interactive && unlocked) {
                resumed.forEach { (pkg, classes) ->
                    if (classes.isNotEmpty()) {
                        val duration = uncoveredDuration(cursor, until, excluded[pkg].orEmpty())
                        if (duration > 0) {
                            val old = totals[pkg] ?: 0
                            totals[pkg] = old + minOf(duration, Long.MAX_VALUE - old)
                        }
                    }
                }
            }
            cursor = until
        }

        events.asSequence().filter { it.timestampMs <= endMs }
            .sortedBy { it.timestampMs }.forEach { event ->
                accrue(event.timestampMs.coerceAtLeast(startMs))
                val pkg = event.packageName?.takeIf { it.isNotBlank() }
                when (event.type) {
                    UsageEventType.RESUME -> if (pkg != null) {
                        resumed.getOrPut(pkg) { mutableSetOf() }.add(event.className.orEmpty())
                    }
                    UsageEventType.PAUSE, UsageEventType.STOP -> if (pkg != null) {
                        if (event.className == null) resumed.remove(pkg)
                        else {
                            resumed[pkg]?.remove(event.className)
                            if (resumed[pkg].isNullOrEmpty()) resumed.remove(pkg)
                        }
                    }
                    UsageEventType.SCREEN_ON -> interactive = true
                    UsageEventType.SCREEN_OFF -> interactive = false
                    UsageEventType.LOCK -> unlocked = false
                    UsageEventType.UNLOCK -> unlocked = true
                    UsageEventType.SHUTDOWN -> { resumed.clear(); interactive = false }
                    UsageEventType.STARTUP -> { resumed.clear(); interactive = true; unlocked = true }
                }
            }
        accrue(endMs)
        val active = if (interactive && unlocked) resumed.keys.filterTo(mutableSetOf()) { pkg ->
            excluded[pkg].orEmpty().none { it.startMs <= endMs && endMs < it.endMs }
        } else emptySet()
        return UsageSnapshot(totals.toMap(), active.toSet())
    }

    /** Subtract a union of overlapping exclusions, never subtracting an interval twice. */
    private fun uncoveredDuration(start: Long, end: Long, exclusions: List<UsageExclusion>): Long {
        var cursor = start
        var uncovered = 0L
        exclusions.asSequence().filter { it.endMs > start && it.startMs < end }
            .sortedBy { it.startMs }.forEach {
                val from = it.startMs.coerceAtLeast(start)
                val to = it.endMs.coerceAtMost(end)
                if (from > cursor) uncovered += from - cursor
                cursor = maxOf(cursor, to)
            }
        return uncovered + (end - cursor).coerceAtLeast(0)
    }
}
