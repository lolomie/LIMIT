package dev.limit.app.nudity

import org.junit.Assert.*
import org.junit.Test

class NudityPolicyTest {
    @Test fun onlyPornChannelCounts() {
        assertEquals(0.1f, NudityPolicy.nudityProxy(floatArrayOf(0.0f, 0.8f, 0.0f, 0.1f, 0.1f)), 0.0001f)
        assertEquals(0.0f, NudityPolicy.nudityProxy(floatArrayOf(0f, 0f, 1f, Float.NaN, 0f)), 0f)
    }

    @Test fun needsTwoConsecutiveHits() {
        val smoother = NuditySmoother()
        assertFalse(smoother.observe(0.94f, 0.85f))
        assertFalse(smoother.observe(0.4f, 0.85f))
        assertFalse(smoother.observe(0.91f, 0.85f))
        assertTrue(smoother.observe(0.87f, 0.85f))
        smoother.reset()
        assertFalse(smoother.observe(1f, 0.85f))
    }

    @Test fun sensitivityModesAreOrdered() {
        assertTrue(NuditySensitivity.STRICT.threshold > NuditySensitivity.BALANCED.threshold)
        assertTrue(NuditySensitivity.BALANCED.threshold > NuditySensitivity.SENSITIVE.threshold)
    }

    @Test fun fallbackIsExplicitAndDisabledDuringCapture() {
        val apps = setOf("example.social")
        assertFalse(NudityPolicy.fallbackBlocks(false, false, apps, "example.social"))
        assertFalse(NudityPolicy.fallbackBlocks(true, true, apps, "example.social"))
        assertFalse(NudityPolicy.fallbackBlocks(true, false, apps, "example.other"))
        assertTrue(NudityPolicy.fallbackBlocks(true, false, apps, "example.social"))
    }
}
