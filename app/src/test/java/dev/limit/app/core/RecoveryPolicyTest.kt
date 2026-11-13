package dev.limit.app.core

import org.junit.Assert.*
import org.junit.Test

class RecoveryPolicyTest {
    @Test fun shortRetriesAreBoundedAndEventsRespectCooldown() {
        val policy = RecoveryPolicy()
        assertEquals(1_000L, policy.failureDelay(0))
        assertEquals(5_000L, policy.failureDelay(1_000))
        assertEquals(15_000L, policy.failureDelay(6_000))
        assertNull(policy.failureDelay(21_000))
        assertFalse(policy.allowEvent(80_999))
        assertTrue(policy.allowEvent(81_000))
        assertEquals(1_000L, policy.failureDelay(81_000))
    }
    @Test fun successfulRecoveryRestoresRetryBudget() {
        val policy = RecoveryPolicy()
        repeat(4) { policy.failureDelay(0) }
        policy.reset()
        assertTrue(policy.allowEvent(1))
        assertEquals(1_000L, policy.failureDelay(1))
    }
}
