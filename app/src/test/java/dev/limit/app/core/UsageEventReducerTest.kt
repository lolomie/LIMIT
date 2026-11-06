package dev.limit.app.core

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageEventReducerTest {
    private val alpha = "example.alpha"
    private val beta = "example.beta"
    private fun event(at: Long, type: UsageEventType, pkg: String? = alpha, activity: String? = "Main") =
        UsageEvent(at, pkg, activity, type)

    @Test fun resumedActivityAccruesUntilPause() {
        val snapshot = UsageEventReducer.reduce(listOf(
            event(1_000, UsageEventType.RESUME), event(6_000, UsageEventType.PAUSE)
        ), 0, 10_000)
        assertEquals(5_000L, snapshot.totals[alpha])
        assertTrue(snapshot.activePackages.isEmpty())
    }

    @Test fun activeIntervalIsClippedToQueryEnd() {
        val snapshot = UsageEventReducer.reduce(listOf(event(1_000, UsageEventType.RESUME)), 0, 5_000)
        assertEquals(4_000L, snapshot.totals[alpha])
        assertEquals(setOf(alpha), snapshot.activePackages)
    }

    @Test fun preWindowEventsSeedAnIntervalAcrossMidnight() {
        val snapshot = UsageEventReducer.reduce(listOf(
            event(1_000, UsageEventType.RESUME), event(8_000, UsageEventType.PAUSE)
        ), 5_000, 10_000)
        assertEquals(3_000L, snapshot.totals[alpha])
    }

    @Test fun duplicateResumeAndPauseEventsDoNotDoubleCount() {
        val snapshot = UsageEventReducer.reduce(listOf(
            event(1_000, UsageEventType.RESUME), event(1_000, UsageEventType.RESUME),
            event(3_000, UsageEventType.RESUME), event(6_000, UsageEventType.PAUSE),
            event(6_000, UsageEventType.PAUSE)
        ), 0, 10_000)
        assertEquals(5_000L, snapshot.totals[alpha])
    }

    @Test fun overlappingActivitiesWithinPackageFormAUnion() {
        val snapshot = UsageEventReducer.reduce(listOf(
            event(1_000, UsageEventType.RESUME, activity = "First"),
            event(3_000, UsageEventType.RESUME, activity = "Second"),
            event(5_000, UsageEventType.PAUSE, activity = "First"),
            event(8_000, UsageEventType.STOP, activity = "Second")
        ), 0, 10_000)
        assertEquals(7_000L, snapshot.totals[alpha])
        assertTrue(snapshot.activePackages.isEmpty())
    }

    @Test fun pauseForUnknownActivityDoesNotStopAResumedActivity() {
        val snapshot = UsageEventReducer.reduce(listOf(
            event(1_000, UsageEventType.RESUME),
            event(2_000, UsageEventType.PAUSE, activity = "Other")
        ), 0, 5_000)
        assertEquals(4_000L, snapshot.totals[alpha])
        assertEquals(setOf(alpha), snapshot.activePackages)
    }

    @Test fun packageOnlyPauseClosesAllActivities() {
        val snapshot = UsageEventReducer.reduce(listOf(
            event(1_000, UsageEventType.RESUME),
            event(2_000, UsageEventType.RESUME, activity = "Second"),
            event(4_000, UsageEventType.PAUSE, activity = null)
        ), 0, 8_000)
        assertEquals(3_000L, snapshot.totals[alpha])
        assertTrue(snapshot.activePackages.isEmpty())
    }

    @Test fun eachPackageHasAnIndependentUsageTotal() {
        val snapshot = UsageEventReducer.reduce(listOf(
            event(1_000, UsageEventType.RESUME), event(3_000, UsageEventType.PAUSE),
            event(3_000, UsageEventType.RESUME, beta), event(8_000, UsageEventType.PAUSE, beta)
        ), 0, 10_000)
        assertEquals(2_000L, snapshot.totals[alpha])
        assertEquals(5_000L, snapshot.totals[beta])
    }

    @Test fun screenOffAndLockPauseAccrualUntilInteractiveAndUnlocked() {
        val snapshot = UsageEventReducer.reduce(listOf(
            event(1_000, UsageEventType.RESUME),
            event(3_000, UsageEventType.SCREEN_OFF, null, null),
            event(3_000, UsageEventType.LOCK, null, null),
            event(6_000, UsageEventType.SCREEN_ON, null, null),
            event(8_000, UsageEventType.UNLOCK, null, null)
        ), 0, 10_000)
        assertEquals(4_000L, snapshot.totals[alpha])
        assertEquals(setOf(alpha), snapshot.activePackages)
    }

    @Test fun lockedEndpointHasNoActiveChargeablePackage() {
        val snapshot = UsageEventReducer.reduce(listOf(
            event(1_000, UsageEventType.RESUME), event(3_000, UsageEventType.LOCK, null, null)
        ), 0, 10_000)
        assertEquals(2_000L, snapshot.totals[alpha])
        assertTrue(snapshot.activePackages.isEmpty())
    }

    @Test fun rebootDoesNotKeepOldActivityResumed() {
        val snapshot = UsageEventReducer.reduce(listOf(
            event(1_000, UsageEventType.RESUME),
            event(3_000, UsageEventType.SHUTDOWN, null, null),
            event(7_000, UsageEventType.STARTUP, null, null)
        ), 0, 10_000)
        assertEquals(2_000L, snapshot.totals[alpha])
        assertTrue(snapshot.activePackages.isEmpty())
    }

    @Test fun blockerIntervalsAreExcludedWithoutDoubleSubtractingOverlap() {
        val snapshot = UsageEventReducer.reduce(
            listOf(event(0, UsageEventType.RESUME)), 0, 10_000,
            listOf(UsageExclusion(alpha, 2_000, 6_000), UsageExclusion(alpha, 4_000, 8_000))
        )
        assertEquals(4_000L, snapshot.totals[alpha])
        assertEquals(setOf(alpha), snapshot.activePackages)
    }

    @Test fun blockingOnePackageDoesNotSubtractAnotherPackagesUsage() {
        val snapshot = UsageEventReducer.reduce(
            listOf(event(0, UsageEventType.RESUME), event(0, UsageEventType.RESUME, beta)),
            0, 10_000, listOf(UsageExclusion(alpha, 0, 10_000))
        )
        assertEquals(0L, snapshot.totals[alpha] ?: 0L)
        assertEquals(10_000L, snapshot.totals[beta])
    }

    @Test fun ongoingBlockerRemovesPackageFromChargeableActiveSet() {
        val snapshot = UsageEventReducer.reduce(
            listOf(event(0, UsageEventType.RESUME)), 0, 10_000,
            listOf(UsageExclusion(alpha, 2_000, Long.MAX_VALUE))
        )
        assertEquals(2_000L, snapshot.totals[alpha])
        assertFalse(snapshot.activePackages.contains(alpha))
    }

    @Test fun eventAtEndChangesStateWithoutAddingTime() {
        val snapshot = UsageEventReducer.reduce(listOf(
            event(0, UsageEventType.RESUME), event(10_000, UsageEventType.PAUSE)
        ), 0, 10_000)
        assertEquals(10_000L, snapshot.totals[alpha])
        assertTrue(snapshot.activePackages.isEmpty())
    }

    @Test fun emptyOrZeroDurationWindowAccruesNoUsage() {
        assertTrue(UsageEventReducer.reduce(emptyList(), 0, 10_000).totals.isEmpty())
        val snapshot = UsageEventReducer.reduce(listOf(event(0, UsageEventType.RESUME)), 0, 0)
        assertEquals(0L, snapshot.totals[alpha] ?: 0L)
    }

    @Test fun springDstDayUsesActualTwentyThreeHourMidnightWindow() {
        assertDayDuration("2026-03-08", 23)
    }

    @Test fun fallDstDayUsesActualTwentyFiveHourMidnightWindow() {
        assertDayDuration("2026-11-01", 25)
    }

    private fun assertDayDuration(date: String, hours: Long) {
        val zone = ZoneId.of("America/New_York")
        val localDate = LocalDate.parse(date)
        val start = localDate.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = localDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val snapshot = UsageEventReducer.reduce(listOf(event(start - 1_000, UsageEventType.RESUME)), start, end)
        assertEquals(hours * 3_600_000L, snapshot.totals[alpha])
    }
}
