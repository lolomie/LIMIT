package dev.limit.app.core

import kotlin.math.max
import kotlin.math.min

data class AppRule(val limitMinutes: Int, val enabled: Boolean = true, val hours: AllowedHours = AllowedHours()) {
    init { require(limitMinutes in 0..1440) { "Daily limit must be between 0 and 1440 minutes" } }
    val limitMs: Long get() = limitMinutes * 60_000L
}

data class AppUsage(val usedMs: Long = 0, val bonusRemainingMs: Long = 0)

data class DayLedger(
    val date: String,
    val grantsUsed: Int = 0,
    val apps: Map<String, AppUsage> = emptyMap(),
)

enum class LimitReachedAction { OFFER_EXTENSION, RETURN_HOME }

data class LimitState(
    val rules: Map<String, AppRule> = emptyMap(),
    val days: Map<String, DayLedger> = emptyMap(),
    val theme: String = "SYSTEM",
    val limitReachedAction: LimitReachedAction = LimitReachedAction.OFFER_EXTENSION,
)

enum class BudgetPhase { AVAILABLE, LIMIT_REACHED, EXCEPTION_ACTIVE, EXCEPTION_EXHAUSTED }

data class Budget(
    val usedMs: Long,
    val remainingMs: Long,
    val bonusRemainingMs: Long,
    val phase: BudgetPhase,
    val grantsRemaining: Int,
)

/** Immutable accounting. All callers must serialize read/modify/persist operations. */
object LimitEngine {
    const val DAILY_GRANTS = 2
    const val EXCEPTION_MS = 300_000L

    /** Replacing OS snapshots never refunds observed usage or previously consumed credit. */
    fun reconcile(state: LimitState, date: String, totals: Map<String, Long>): LimitState {
        val day = state.days[date] ?: DayLedger(date)
        val apps = day.apps.toMutableMap()
        totals.forEach { (pkg, snapshotMs) ->
            val old = apps[pkg] ?: AppUsage()
            val observed = max(old.usedMs.coerceAtLeast(0), snapshotMs.coerceAtLeast(0))
            val rule = state.rules[pkg]
            val spent = if (rule?.enabled == true) {
                // Only this newly observed interval can consume an existing exception.
                // Historical overage before a grant must never consume the new grant.
                (observed - max(old.usedMs.coerceAtLeast(0), rule.limitMs)).coerceAtLeast(0)
            } else 0L
            apps[pkg] = AppUsage(observed, (old.bonusRemainingMs.coerceAtLeast(0) - spent).coerceAtLeast(0))
        }
        return state.copy(days = state.days + (date to day.copy(apps = apps.toMap())))
    }

    /** Call reconcile before changing rules so prior usage is charged under the prior rule. */
    fun setRule(state: LimitState, date: String, pkg: String, rule: AppRule?): LimitState {
        require(pkg.isNotBlank()) { "Package name is required" }
        val day = state.days[date] ?: DayLedger(date)
        val rules = if (rule == null) state.rules - pkg else state.rules + (pkg to rule)
        return state.copy(rules = rules, days = state.days + (date to day))
    }

    fun budget(state: LimitState, date: String, pkg: String): Budget {
        val day = state.days[date] ?: DayLedger(date)
        val usage = day.apps[pkg] ?: AppUsage()
        val used = usage.usedMs.coerceAtLeast(0)
        val bonus = usage.bonusRemainingMs.coerceAtLeast(0)
        val slots = (DAILY_GRANTS - day.grantsUsed).coerceIn(0, DAILY_GRANTS)
        val rule = state.rules[pkg]
        if (rule == null || !rule.enabled) {
            return Budget(used, Long.MAX_VALUE, bonus, BudgetPhase.AVAILABLE, slots)
        }
        val base = (rule.limitMs - used).coerceAtLeast(0)
        val phase = when {
            base > 0 -> BudgetPhase.AVAILABLE
            bonus > 0 -> BudgetPhase.EXCEPTION_ACTIVE
            slots == 0 -> BudgetPhase.EXCEPTION_EXHAUSTED
            else -> BudgetPhase.LIMIT_REACHED
        }
        return Budget(used, saturatedAdd(base, bonus), bonus, phase, slots)
    }

    /** A successful persisted result is required before launching the allowed application. */
    fun grant(state: LimitState, date: String, pkg: String): LimitState {
        val rule = state.rules[pkg]
        check(rule?.enabled == true) { "Application is not restricted" }
        val day = state.days[date] ?: DayLedger(date)
        val budget = budget(state, date, pkg)
        check(day.grantsUsed in 0 until DAILY_GRANTS) { "No daily exceptions remain" }
        check(budget.phase == BudgetPhase.LIMIT_REACHED) { "Application must have exhausted its allowance" }
        val usage = day.apps[pkg] ?: AppUsage()
        val updated = day.copy(
            grantsUsed = day.grantsUsed + 1,
            apps = day.apps + (pkg to usage.copy(bonusRemainingMs = EXCEPTION_MS)),
        )
        return state.copy(days = state.days + (date to updated))
    }

    private fun saturatedAdd(a: Long, b: Long): Long = a + min(b, Long.MAX_VALUE - a)
}
