package dev.limit.app.nudity

import org.junit.Assert.*
import org.junit.Test

class NuditySessionTest {
    @Test fun requestRequiresActiveSessionAndMatchingApp() {
        NuditySession.set(NudityPhase.INACTIVE)
        NudityBlockCoordinator.onForegroundChanged("test.one")
        NudityBlockCoordinator.flag("test.one")
        assertNull(NudityBlockCoordinator.requested.value)
        NuditySession.set(NudityPhase.ACTIVE)
        NudityBlockCoordinator.flag("test.other")
        assertNull(NudityBlockCoordinator.requested.value)
        NudityBlockCoordinator.flag("test.one")
        assertEquals("test.one", NudityBlockCoordinator.requested.value)
        NudityBlockCoordinator.onForegroundChanged("test.two")
        assertNull(NudityBlockCoordinator.requested.value)
        NuditySession.set(NudityPhase.INACTIVE)
    }

    @Test fun endingSessionClearsPendingBlockAndDoesNotPersistActiveState() {
        NuditySession.set(NudityPhase.ACTIVE)
        NudityBlockCoordinator.onForegroundChanged("test.app")
        NudityBlockCoordinator.flag("test.app")
        assertEquals("test.app", NudityBlockCoordinator.requested.value)
        NuditySession.set(NudityPhase.CONSENT_REQUIRED)
        assertEquals(NudityPhase.CONSENT_REQUIRED, NuditySession.status.value.phase)
        assertNull(NudityBlockCoordinator.requested.value)
        NuditySession.set(NudityPhase.INACTIVE)
        NudityBlockCoordinator.onForegroundChanged(null)
    }
}
