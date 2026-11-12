package dev.limit.app.core

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AllowedHoursPolicyTest {
    private val berlin = ZoneId.of("Europe/Berlin")
    private fun at(local: String, zone: ZoneId = berlin): Clock =
        Clock.fixed(java.time.LocalDateTime.parse(local).atZone(zone).toInstant(), zone)

    @Test fun exactStartAndExclusiveEnd() {
        val hours = AllowedHours(AllowedHoursMode.BETWEEN, 18 * 60, 21 * 60)
        assertFalse(AllowedHoursPolicy.isAllowed(hours, at("2026-11-12T17:59:59")))
        assertTrue(AllowedHoursPolicy.isAllowed(hours, at("2026-11-12T18:00:00")))
        assertTrue(AllowedHoursPolicy.isAllowed(hours, at("2026-11-12T20:59:59")))
        assertFalse(AllowedHoursPolicy.isAllowed(hours, at("2026-11-12T21:00:00")))
        assertEquals(at("2026-11-12T18:00:00").instant(),
            AllowedHoursPolicy.nextOpening(hours, at("2026-11-12T17:59:59")))
        assertEquals(at("2026-11-12T21:00:00").instant(),
            AllowedHoursPolicy.nextTransition(hours, at("2026-11-12T18:00:00")))
    }

    @Test fun fromTimeClosesAtMidnightAndReopensNextDay() {
        val hours = AllowedHours(AllowedHoursMode.FROM, 18 * 60)
        assertFalse(AllowedHoursPolicy.isAllowed(hours, at("2026-11-12T17:59:00")))
        assertTrue(AllowedHoursPolicy.isAllowed(hours, at("2026-11-12T23:59:59")))
        assertFalse(AllowedHoursPolicy.isAllowed(hours, at("2026-11-13T00:00:00")))
        assertEquals(at("2026-11-13T00:00:00").instant(),
            AllowedHoursPolicy.nextTransition(hours, at("2026-11-12T21:00:00")))
    }

    @Test fun overnightUsesLocalDateBudgetNotWindowDate() {
        val hours = AllowedHours(AllowedHoursMode.BETWEEN, 22 * 60, 2 * 60)
        assertFalse(AllowedHoursPolicy.isAllowed(hours, at("2026-11-12T21:59:59")))
        assertTrue(AllowedHoursPolicy.isAllowed(hours, at("2026-11-12T22:00:00")))
        assertTrue(AllowedHoursPolicy.isAllowed(hours, at("2026-11-13T01:59:59")))
        assertFalse(AllowedHoursPolicy.isAllowed(hours, at("2026-11-13T02:00:00")))
        val rule = AppRule(30, hours = hours)
        val day1 = LimitEngine.reconcile(LimitState(rules = mapOf("instagram" to rule)),
            "2026-11-12", mapOf("instagram" to 30 * 60_000L))
        assertEquals(0L, LimitEngine.budget(day1, "2026-11-12", "instagram").remainingMs)
        assertEquals(30 * 60_000L, LimitEngine.budget(day1, "2026-11-13", "instagram").remainingMs)
    }

    @Test fun exhaustedAllowanceAndActiveExceptionNeverBypassClosedWindow() {
        val hours = AllowedHours(AllowedHoursMode.BETWEEN, 18 * 60, 21 * 60)
        val rule = AppRule(1, hours = hours)
        var state = LimitState(rules = mapOf("instagram" to rule))
        state = LimitEngine.reconcile(state, "2026-11-12", mapOf("instagram" to 60_000L))
        val exhausted = LimitEngine.budget(state, "2026-11-12", "instagram")
        assertEquals(BlockReason.DAILY_BUDGET, AllowedHoursPolicy.reason(rule, exhausted, at("2026-11-12T19:00:00")))
        state = LimitEngine.grant(state, "2026-11-12", "instagram")
        val exception = LimitEngine.budget(state, "2026-11-12", "instagram")
        assertEquals(BlockReason.NONE, AllowedHoursPolicy.reason(rule, exception, at("2026-11-12T20:59:59")))
        assertEquals(BlockReason.OUTSIDE_HOURS, AllowedHoursPolicy.reason(rule, exception, at("2026-11-12T21:00:00")))
        assertEquals(300_000L, exception.bonusRemainingMs)
        assertEquals(1, exception.grantsRemaining)
    }

    @Test fun availableBudgetStillBlocksAtHoursEndAndChangesDoNotRefund() {
        val old = AppRule(30)
        val state = LimitEngine.reconcile(LimitState(rules = mapOf("instagram" to old)),
            "2026-11-12", mapOf("instagram" to 600_000L))
        val changedRule = old.copy(hours = AllowedHours(AllowedHoursMode.BETWEEN, 18 * 60, 21 * 60))
        val changed = LimitEngine.setRule(state, "2026-11-12", "instagram", changedRule)
        val budget = LimitEngine.budget(changed, "2026-11-12", "instagram")
        assertEquals(600_000L, budget.usedMs)
        assertEquals(1_200_000L, budget.remainingMs)
        assertEquals(BlockReason.OUTSIDE_HOURS, AllowedHoursPolicy.reason(changedRule, budget, at("2026-11-12T21:00:00")))
        assertEquals(BlockReason.NONE, AllowedHoursPolicy.reason(changedRule, budget, at("2026-11-12T20:59:59")))
    }

    @Test(expected = IllegalArgumentException::class)
    fun identicalTimesRejected() { AllowedHours(AllowedHoursMode.BETWEEN, 600, 600) }

    @Test fun anytimeIsBackwardCompatible() {
        assertTrue(AllowedHoursPolicy.isAllowed(AllowedHours(), at("2026-11-12T03:00:00")))
        assertEquals(null, AllowedHoursPolicy.nextTransition(AllowedHours(), at("2026-11-12T03:00:00")))
    }

    @Test fun springForwardAndAutumnOverlapUseActualInstants() {
        val spring = AllowedHours(AllowedHoursMode.BETWEEN, 150, 240) // 02:30 - 04:00
        val beforeSpring = Clock.fixed(Instant.parse("2026-03-29T00:59:59Z"), berlin)
        assertEquals(Instant.parse("2026-03-29T01:00:00Z"),
            AllowedHoursPolicy.nextOpening(spring, beforeSpring))
        val autumn = AllowedHours(AllowedHoursMode.BETWEEN, 150, 210) // 02:30 - 03:30
        val beforeAutumn = Clock.fixed(Instant.parse("2026-10-25T00:29:59Z"), berlin)
        assertEquals(Instant.parse("2026-10-25T00:30:00Z"),
            AllowedHoursPolicy.nextOpening(autumn, beforeAutumn))
        val firstOccurrence = Clock.fixed(Instant.parse("2026-10-25T00:45:00Z"), berlin)
        assertEquals(Instant.parse("2026-10-25T01:00:00Z"),
            AllowedHoursPolicy.nextTransition(autumn, firstOccurrence))
    }
}
