package dev.limit.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LimitEngineTest {
    private val today = "2026-11-04"
    private val tomorrow = "2026-11-05"
    private val alpha = "example.alpha"
    private val beta = "example.beta"

    private fun limited(vararg packages: String): LimitState = packages.fold(LimitState()) { state, pkg ->
        LimitEngine.setRule(state, today, pkg, AppRule(1))
    }

    private fun used(state: LimitState, amount: Long, pkg: String = alpha, date: String = today) =
        LimitEngine.reconcile(state, date, mapOf(pkg to amount))

    private fun budget(state: LimitState, pkg: String = alpha, date: String = today) =
        LimitEngine.budget(state, date, pkg)

    private fun assertRejected(block: () -> Unit) {
        var rejected = false
        try { block() } catch (_: IllegalStateException) { rejected = true }
        assertTrue("Invalid grants must be rejected", rejected)
    }

    @Test fun baseAllowanceChangesAtExactMillisecondBoundary() {
        val initial = limited(alpha)
        assertEquals(60_000L, budget(initial).remainingMs)
        val almost = used(initial, 59_999)
        assertEquals(BudgetPhase.AVAILABLE, budget(almost).phase)
        assertEquals(1L, budget(almost).remainingMs)
        val reached = used(almost, 60_000)
        assertEquals(BudgetPhase.LIMIT_REACHED, budget(reached).phase)
        assertEquals(0L, budget(reached).remainingMs)
        assertEquals(2, budget(reached).grantsRemaining)
    }

    @Test fun unrestrictedAndDisabledAppsCannotSpendAGrant() {
        val initial = limited(alpha)
        assertEquals(Long.MAX_VALUE, budget(initial, beta).remainingMs)
        assertRejected { LimitEngine.grant(initial, today, beta) }
        val disabled = LimitEngine.setRule(used(initial, 120_000), today, alpha, AppRule(1, false))
        assertEquals(BudgetPhase.AVAILABLE, budget(disabled).phase)
        assertRejected { LimitEngine.grant(disabled, today, alpha) }
    }

    @Test fun grantRequiresAnExhaustedAllowance() {
        assertRejected { LimitEngine.grant(limited(alpha), today, alpha) }
        val credited = LimitEngine.grant(used(limited(alpha), 60_000), today, alpha)
        assertRejected { LimitEngine.grant(credited, today, alpha) }
        assertEquals(1, budget(credited).grantsRemaining)
    }

    @Test fun exactlyTwoGlobalGrantsCanBeSharedAcrossApps() {
        var state = used(used(limited(alpha, beta), 60_000), 60_000, beta)
        state = LimitEngine.grant(state, today, alpha)
        assertEquals(1, budget(state, beta).grantsRemaining)
        state = LimitEngine.grant(state, today, beta)
        assertEquals(0, budget(state).grantsRemaining)
        assertEquals(BudgetPhase.EXCEPTION_ACTIVE, budget(state).phase)
        assertEquals(BudgetPhase.EXCEPTION_ACTIVE, budget(state, beta).phase)
        state = used(state, 360_000)
        assertEquals(BudgetPhase.EXCEPTION_EXHAUSTED, budget(state).phase)
        assertRejected { LimitEngine.grant(state, today, alpha) }
    }

    @Test fun bothGlobalGrantsMayBeUsedSequentiallyOnOneApp() {
        var state = LimitEngine.grant(used(limited(alpha), 60_000), today, alpha)
        state = used(state, 360_000)
        state = LimitEngine.grant(state, today, alpha)
        assertEquals(300_000L, budget(state).bonusRemainingMs)
        assertEquals(0, budget(state).grantsRemaining)
        state = used(state, 659_999)
        assertEquals(1L, budget(state).bonusRemainingMs)
        state = used(state, 660_000)
        assertEquals(0L, budget(state).bonusRemainingMs)
        assertEquals(BudgetPhase.EXCEPTION_EXHAUSTED, budget(state).phase)
        assertRejected { LimitEngine.grant(state, today, alpha) }
    }

    @Test fun historicalOverageDoesNotConsumeANewFiveMinuteGrant() {
        var state = used(limited(alpha), 1_800_000)
        state = LimitEngine.grant(state, today, alpha)
        assertEquals(300_000L, budget(state).bonusRemainingMs)
        state = used(state, 1_800_001)
        assertEquals(299_999L, budget(state).bonusRemainingMs)
        state = used(state, 2_100_000)
        assertEquals(0L, budget(state).bonusRemainingMs)
    }

    @Test fun loweringAnAlreadyExceededLimitStillAllowsFiveAdditionalMinutes() {
        var state = LimitEngine.setRule(LimitState(), today, alpha, AppRule(60))
        state = used(state, 600_000)
        state = LimitEngine.setRule(state, today, alpha, AppRule(1))
        assertEquals(BudgetPhase.LIMIT_REACHED, budget(state).phase)
        state = LimitEngine.grant(state, today, alpha)
        assertEquals(300_000L, budget(state).bonusRemainingMs)
        state = used(state, 900_000)
        assertEquals(0L, budget(state).bonusRemainingMs)
    }

    @Test fun repeatedAndOlderSnapshotsDoNotDoubleChargeOrRefundCredit() {
        var state = LimitEngine.grant(used(limited(alpha), 60_000), today, alpha)
        state = used(state, 180_000)
        repeat(5) { state = used(state, 180_000) }
        state = used(state, 90_000)
        assertEquals(180_000L, budget(state).usedMs)
        assertEquals(180_000L, budget(state).bonusRemainingMs)
        state = used(state, 240_000)
        assertEquals(120_000L, budget(state).bonusRemainingMs)
    }

    @Test fun otherAppsAndUnchangedTotalsLeaveExceptionCreditUntouched() {
        var state = LimitEngine.grant(used(limited(alpha, beta), 60_000), today, alpha)
        state = used(state, 3_600_000, beta)
        state = used(state, 60_000)
        assertEquals(300_000L, budget(state).bonusRemainingMs)
        assertEquals(1, budget(state, beta).grantsRemaining)
    }

    @Test fun raisingLimitPreservesCreditAndUsesNewBaseFirst() {
        var state = LimitEngine.grant(used(limited(alpha), 60_000), today, alpha)
        state = used(state, 90_000)
        state = LimitEngine.setRule(state, today, alpha, AppRule(2))
        assertEquals(270_000L, budget(state).bonusRemainingMs)
        state = used(state, 119_999)
        assertEquals(270_000L, budget(state).bonusRemainingMs)
        state = used(state, 120_001)
        assertEquals(269_999L, budget(state).bonusRemainingMs)
        assertEquals(1, budget(state).grantsRemaining)
    }

    @Test fun disabledIntervalsDoNotConsumeSavedCredit() {
        var state = LimitEngine.grant(used(limited(alpha), 60_000), today, alpha)
        state = LimitEngine.setRule(state, today, alpha, AppRule(1, false))
        state = used(state, 1_000_000)
        state = LimitEngine.setRule(state, today, alpha, AppRule(1))
        assertEquals(300_000L, budget(state).bonusRemainingMs)
        state = used(state, 1_000_001)
        assertEquals(299_999L, budget(state).bonusRemainingMs)
    }

    @Test fun removingAndReaddingRuleDoesNotRefundGlobalGrant() {
        var state = LimitEngine.grant(used(limited(alpha), 60_000), today, alpha)
        state = LimitEngine.setRule(state, today, alpha, null)
        assertEquals(Long.MAX_VALUE, budget(state).remainingMs)
        state = LimitEngine.setRule(state, today, alpha, AppRule(1))
        assertEquals(1, budget(state).grantsRemaining)
        assertEquals(300_000L, budget(state).bonusRemainingMs)
    }

    @Test fun nextDateStartsFreshAndDoesNotCarryExceptionCredit() {
        var state = LimitEngine.grant(used(limited(alpha), 60_000), today, alpha)
        state = used(state, 90_000)
        assertEquals(2, budget(state, date = tomorrow).grantsRemaining)
        assertEquals(0L, budget(state, date = tomorrow).usedMs)
        assertEquals(0L, budget(state, date = tomorrow).bonusRemainingMs)
        assertEquals(60_000L, budget(state, date = tomorrow).remainingMs)
        assertEquals(270_000L, budget(state).bonusRemainingMs)
    }

    @Test fun revisitingADateDoesNotIssueFreshGrants() {
        var state = LimitEngine.grant(used(limited(alpha), 60_000), today, alpha)
        state = used(state, 360_000)
        state = LimitEngine.grant(state, today, alpha)
        state = used(state, 660_000)
        state = used(state, 60_000, date = tomorrow)
        state = LimitEngine.grant(state, tomorrow, alpha)
        assertEquals(1, budget(state, date = tomorrow).grantsRemaining)
        assertEquals(0, budget(state).grantsRemaining)
        assertRejected { LimitEngine.grant(state, today, alpha) }
    }

    @Test fun unknownPackagesDoNotHideExistingUsageWhenSnapshotIsPartial() {
        var state = used(used(limited(alpha, beta), 30_000), 15_000, beta)
        state = LimitEngine.reconcile(state, today, emptyMap())
        assertEquals(30_000L, budget(state).usedMs)
        assertEquals(15_000L, budget(state, beta).usedMs)
    }

    @Test fun ruleRangeIncludesImmediateBlockingAndOneDay() {
        assertEquals(0, AppRule(0).limitMinutes)
        assertEquals(1, AppRule(1).limitMinutes)
        assertEquals(1440, AppRule(1440).limitMinutes)
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeMinuteRulesAreRejected() { AppRule(-1) }

    @Test fun zeroMinuteRuleBlocksImmediatelyAndCanReceiveFiveMinuteGrant() {
        val initial = LimitEngine.setRule(LimitState(), today, alpha, AppRule(0))
        assertEquals(BudgetPhase.LIMIT_REACHED, budget(initial).phase)
        val granted = LimitEngine.grant(initial, today, alpha)
        assertEquals(300_000L, budget(granted).remainingMs)
        assertEquals(BudgetPhase.EXCEPTION_ACTIVE, budget(granted).phase)
    }

    @Test fun negativeUsageSnapshotCannotCreateAllowanceOrRefundUsage() {
        var state = used(limited(alpha), -100)
        assertEquals(0L, budget(state).usedMs)
        state = used(state, 30_000)
        state = used(state, -100)
        assertEquals(30_000L, budget(state).usedMs)
    }

    @Test(expected = IllegalArgumentException::class)
    fun moreThanOneDayRulesAreRejected() { AppRule(1441) }
}
