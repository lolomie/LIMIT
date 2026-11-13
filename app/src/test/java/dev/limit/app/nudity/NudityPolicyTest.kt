package dev.limit.app.nudity

import org.junit.Assert.*
import org.junit.Test

class NudityPolicyTest {
    @Test fun exposedAnatomyCountsOnlyIfItIsTheBestClassForAnAnchor() {
        val scores = Array(NudityPolicy.CHANNELS) { FloatArray(2) }
        scores[4 + 4][0] = 0.88f // exposed genitalia
        scores[4 + 0][1] = 0.98f // covered genitalia
        scores[4 + 4][1] = 0.40f
        assertEquals(0.88f, NudityPolicy.exposedScore(scores), 0.0001f)
    }
    @Test fun coveredOrBenignBodyPartsDoNotTrigger() {
        val scores = Array(NudityPolicy.CHANNELS) { FloatArray(3) }
        scores[4 + 0][0] = 0.9f // covered genitalia
        scores[4 + 5][1] = 0.8f // male chest
        scores[4 + 13][2] = 0.8f // belly
        assertEquals(0f, NudityPolicy.exposedScore(scores), 0.0001f)
    }
    @Test fun rejectsUnexpectedModelOutput() {
        try {
            NudityPolicy.exposedScore(Array(5) { FloatArray(2) })
            fail("Incorrect class layout should fail closed")
        } catch (_: IllegalArgumentException) {}
    }
    @Test fun needsTwoConsecutiveObservations() {
        val smoother = NuditySmoother()
        assertFalse(smoother.observe(0.7f, 0.48f))
        assertTrue(smoother.observe(0.62f, 0.48f))
        assertFalse(smoother.observe(0.4f, 0.48f))
        smoother.reset()
        assertFalse(smoother.observe(0.8f, 0.48f))
    }
    @Test fun sensitivityOrdering() {
        assertTrue(NuditySensitivity.STRICT.threshold > NuditySensitivity.BALANCED.threshold)
        assertTrue(NuditySensitivity.BALANCED.threshold > NuditySensitivity.SENSITIVE.threshold)
    }
    @Test fun fallbackRemainsExplicit() {
        assertTrue(NudityPolicy.fallbackBlocks(true, false, setOf("example"), "example"))
        assertFalse(NudityPolicy.fallbackBlocks(false, false, setOf("example"), "example"))
        assertFalse(NudityPolicy.fallbackBlocks(true, true, setOf("example"), "example"))
    }
}
