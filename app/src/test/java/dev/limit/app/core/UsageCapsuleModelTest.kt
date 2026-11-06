package dev.limit.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Display projections must never become a second source of usage accounting. */
class UsageCapsuleModelTest {
    private val today = "2026-11-05"
    private val alpha = "example.alpha"
    private val beta = "example.beta"

    private fun observation(usedMs: Long, elapsedMs: Long = 1_000, pkg: String = alpha) =
        CapsuleObservation(pkg, today, usedMs, elapsedMs)

    private fun project(
        observation: CapsuleObservation,
        now: Long = observation.observedElapsedMs,
        date: String = today,
        foreground: String? = observation.packageName,
        screenUsable: Boolean = true,
        available: Boolean = true,
        blocking: Boolean = false
    ) = UsageCapsuleModel.project(observation, now, date, foreground, screenUsable, available, blocking)

    private fun select(
        focused: String? = alpha,
        active: Set<String> = setOf(alpha),
        eligible: Set<String> = setOf(alpha, beta),
        screenUsable: Boolean = true,
        available: Boolean = true,
        blocking: Boolean = false
    ) = UsageCapsuleModel.selectPackage(focused, active, eligible, screenUsable, available, blocking)

    @Test fun unselectedNormalAppCanShowItsDailyUsage() {
        // Selection depends on launchability and real foreground state, not a restriction rule.
        assertEquals(alpha, select())
        assertEquals(23L, project(observation(23 * 60_000L))!!.minutes)
    }

    @Test fun minutesAreFlooredAtExactBoundary() {
        assertEquals(0L, project(observation(0))!!.minutes)
        assertEquals(0L, project(observation(59_999))!!.minutes)
        assertEquals(1L, project(observation(60_000))!!.minutes)
        assertEquals(1L, project(observation(119_999))!!.minutes)
        assertEquals(2L, project(observation(120_000))!!.minutes)
    }

    @Test fun nextUpdateIsScheduledAtTheNextSpentMinute() {
        assertEquals(60_000L, project(observation(0))!!.nextUpdateMs)
        assertEquals(1L, project(observation(59_999))!!.nextUpdateMs)
        assertEquals(60_000L, project(observation(60_000))!!.nextUpdateMs)
        assertEquals(59_999L, project(observation(60_001))!!.nextUpdateMs)
    }

    @Test fun elapsedForegroundTimeMovesTheDisplayAcrossAMinuteBoundary() {
        val snapshot = observation(59_999, elapsedMs = 10_000)
        assertEquals(0L, project(snapshot, now = 10_000)!!.minutes)
        assertEquals(1L, project(snapshot, now = 10_001)!!.minutes)
        assertEquals(60_000L, project(snapshot, now = 10_001)!!.nextUpdateMs)
    }

    @Test fun projectionIsRepeatableAndDoesNotAccumulateOnRepeatedRenders() {
        val snapshot = observation(50_000, elapsedMs = 10_000)
        repeat(5) {
            assertEquals(1L, project(snapshot, now = 20_000)!!.minutes)
            assertEquals(50_000L, snapshot.usedMs)
            assertEquals(10_000L, snapshot.observedElapsedMs)
        }
        assertEquals(1L, project(snapshot, now = 21_000)!!.minutes)
    }

    @Test fun freshAuthoritativeSnapshotReplacesProjectionWithoutDoubleCounting() {
        val earlier = observation(59_000, elapsedMs = 10_000)
        assertEquals(1L, project(earlier, now = 15_000)!!.minutes)
        // Android can reconcile below an earlier display estimate; do not persist the estimate.
        val authoritative = observation(59_500, elapsedMs = 15_000)
        assertEquals(0L, project(authoritative, now = 15_000)!!.minutes)
        assertEquals(1L, project(authoritative, now = 15_500)!!.minutes)
        assertEquals(59_500L, authoritative.usedMs)
    }

    @Test fun displayedMinutesAreDailyTotalAndDoNotResetWithANewSession() {
        val firstSession = observation(10 * 60_000L + 30_000, elapsedMs = 1_000)
        assertEquals(10L, project(firstSession)!!.minutes)
        assertNull(project(firstSession, now = 50_000, foreground = beta))
        val laterSession = observation(12 * 60_000L + 5_000, elapsedMs = 100_000)
        assertEquals(12L, project(laterSession)!!.minutes)
        assertEquals(55_000L, project(laterSession)!!.nextUpdateMs)
    }

    @Test fun switchingAppsNeverShowsThePreviousAppsTotal() {
        val alphaObservation = observation(42 * 60_000L)
        assertNull(project(alphaObservation, foreground = beta))
        val betaObservation = observation(3 * 60_000L, pkg = beta)
        val frame = project(betaObservation, foreground = beta)!!
        assertEquals(beta, frame.packageName)
        assertEquals(3L, frame.minutes)
    }

    @Test fun staleOrAbsentForegroundCannotDisplayACapsule() {
        assertNull(select(focused = null))
        assertNull(select(active = emptySet()))
        assertNull(select(eligible = emptySet()))
        assertNull(select(focused = beta, active = setOf(alpha)))
        assertNull(project(observation(60_000), foreground = null))
    }

    @Test fun ownAppLauncherAndProtectedSurfacesAreExcluded() {
        for (surface in listOf("dev.limit.app", "com.android.launcher", "com.android.systemui")) {
            assertNull(select(focused = surface, active = setOf(surface)))
        }
    }

    @Test fun splitScreenSelectsOnlyTheEligibleFocusedApp() {
        assertEquals(beta, select(focused = beta, active = setOf(alpha, beta)))
        assertNull(select(focused = "protected.surface", active = setOf(alpha, "protected.surface")))
    }

    @Test fun lockedOrScreenOffHidesSelectionAndProjection() {
        assertNull(select(screenUsable = false))
        assertNull(project(observation(60_000), screenUsable = false))
    }

    @Test fun revokedPermissionOrUnavailableUsageHidesSelectionAndProjection() {
        assertNull(select(available = false))
        assertNull(project(observation(60_000), available = false))
    }

    @Test fun blockerHasPriorityOverTheCapsule() {
        assertNull(select(blocking = true))
        assertNull(project(observation(60_000), blocking = true))
    }

    @Test fun previousDayObservationIsHiddenUntilFreshDailyUsageArrives() {
        assertNull(project(observation(23 * 60_000L), date = "2026-11-06"))
        val newDay = CapsuleObservation(alpha, "2026-11-06", 0, 50_000)
        assertEquals(0L, project(newDay, date = "2026-11-06")!!.minutes)
    }

    @Test fun negativeUsageAndMonotonicClockReversalCannotCreateTime() {
        assertEquals(0L, project(observation(-500))!!.minutes)
        assertEquals(60_000L, project(observation(-500))!!.nextUpdateMs)
        val snapshot = observation(60_000, elapsedMs = 10_000)
        assertEquals(1L, project(snapshot, now = 5_000)!!.minutes)
        assertEquals(60_000L, project(snapshot, now = 5_000)!!.nextUpdateMs)
    }

    @Test fun veryLargeTotalsSaturateWithoutWrappingOrSchedulingInvalidDelays() {
        val frame = project(observation(Long.MAX_VALUE - 100), now = 2_000)!!
        assertEquals(Long.MAX_VALUE / 60_000, frame.minutes)
        assertTrue(frame.nextUpdateMs in 1L..60_000L)
    }
}
