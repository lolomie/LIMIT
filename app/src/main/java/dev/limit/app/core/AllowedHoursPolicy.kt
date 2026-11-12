package dev.limit.app.core

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Local wall-clock restrictions. A missing rule means ANYTIME (schema-v1 compatibility). */
enum class AllowedHoursMode { ANYTIME, FROM, BETWEEN }

data class AllowedHours(
    val mode: AllowedHoursMode = AllowedHoursMode.ANYTIME,
    val fromMinute: Int = 0,
    val untilMinute: Int = 0,
) {
    init {
        require(fromMinute in 0..1439 && untilMinute in 0..1439) { "Hours must be between 00:00 and 23:59" }
        require(mode != AllowedHoursMode.BETWEEN || fromMinute != untilMinute) {
            "Start and end must differ; use Anytime for unrestricted hours"
        }
    }

    val label: String get() = when (mode) {
        AllowedHoursMode.ANYTIME -> "Jederzeit"
        AllowedHoursMode.FROM -> "Ab ${formatMinute(fromMinute)}"
        AllowedHoursMode.BETWEEN -> "${formatMinute(fromMinute)}–${formatMinute(untilMinute)}"
    }

    companion object {
        fun formatMinute(minute: Int): String =
            "${(minute / 60).toString().padStart(2, '0')}:${(minute % 60).toString().padStart(2, '0')}"
    }
}

enum class BlockReason { NONE, OUTSIDE_HOURS, DAILY_BUDGET }

object AllowedHoursPolicy {
    /** Evaluating by the local wall clock works even for skipped/repeated DST minutes. */
    fun isAllowed(hours: AllowedHours, clock: Clock = Clock.systemDefaultZone()): Boolean =
        isAllowedAt(hours, clock.instant(), clock.zone)

    fun isAllowedAt(hours: AllowedHours, instant: Instant, zone: ZoneId): Boolean {
        val local = instant.atZone(zone).toLocalTime()
        val minute = local.hour * 60 + local.minute
        return when (hours.mode) {
            AllowedHoursMode.ANYTIME -> true
            AllowedHoursMode.FROM -> minute >= hours.fromMinute
            AllowedHoursMode.BETWEEN -> if (hours.fromMinute < hours.untilMinute) {
                minute >= hours.fromMinute && minute < hours.untilMinute
            } else {
                minute >= hours.fromMinute || minute < hours.untilMinute
            }
        }
    }

    fun reason(rule: AppRule?, budget: Budget, clock: Clock = Clock.systemDefaultZone()): BlockReason =
        when {
            rule?.enabled != true -> BlockReason.NONE
            !isAllowed(rule.hours, clock) -> BlockReason.OUTSIDE_HOURS
            budget.remainingMs <= 0 -> BlockReason.DAILY_BUDGET
            else -> BlockReason.NONE
        }

    /**
     * Find the first actual change on the instant timeline, not a naive 24-hour offset.
     * Include both overlap offsets and the transition instant for DST gaps/clock rollback.
     */
    fun nextTransition(hours: AllowedHours, clock: Clock = Clock.systemDefaultZone()): Instant? =
        transitions(hours, clock).firstOrNull()

    fun nextOpening(hours: AllowedHours, clock: Clock = Clock.systemDefaultZone()): Instant? =
        transitions(hours, clock).firstOrNull {
            isAllowedAt(hours, it, clock.zone)
        }

    private fun transitions(hours: AllowedHours, clock: Clock): List<Instant> {
        if (hours.mode == AllowedHoursMode.ANYTIME) return emptyList()
        val zone = clock.zone
        val instant = clock.instant()
        val startDay = instant.atZone(zone).toLocalDate().minusDays(1)
        val endDay = startDay.plusDays(5)
        val candidateInstants = mutableSetOf<Instant>()
        val boundaries = when (hours.mode) {
            AllowedHoursMode.FROM -> listOf(hours.fromMinute, 0)
            AllowedHoursMode.BETWEEN -> listOf(hours.fromMinute, hours.untilMinute)
            AllowedHoursMode.ANYTIME -> emptyList()
        }
        for (dayIndex in 0L..5L) {
            val date: LocalDate = startDay.plusDays(dayIndex)
            for (minute in boundaries) {
                val local: LocalDateTime = date.atStartOfDay().plusMinutes(minute.toLong())
                val offsets = zone.rules.getValidOffsets(local)
                if (offsets.isEmpty()) {
                    // A spring-forward gap may skip the exact local boundary.
                    zone.rules.getTransition(local)?.instant?.let(candidateInstants::add)
                } else offsets.forEach { offset ->
                    candidateInstants += local.toInstant(offset)
                }
            }
        }
        var zoneChange = zone.rules.nextTransition(instant.minusNanos(1))
        val endInstant = endDay.plusDays(1).atStartOfDay(zone).toInstant()
        while (zoneChange != null && zoneChange.instant < endInstant) {
            candidateInstants += zoneChange.instant
            zoneChange = zone.rules.nextTransition(zoneChange.instant)
        }
        return candidateInstants.asSequence()
            .filter { it > instant }
            .filter {
                isAllowedAt(hours, it.minusNanos(1), zone) != isAllowedAt(hours, it, zone)
            }
            .sorted().toList()
    }
}
