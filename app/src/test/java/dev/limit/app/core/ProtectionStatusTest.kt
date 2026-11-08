package dev.limit.app.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ProtectionStatusTest {
    @Test fun enabledPermissionAloneNeverClaimsProtection() {
        assertEquals(ProtectionStatus.DISCONNECTED, ProtectionStatus.evaluate(true, true, false, false, false))
    }
    @Test fun sleepingConnectedServiceIsResting() {
        assertEquals(ProtectionStatus.RESTING, ProtectionStatus.evaluate(true, true, false, true, true))
    }
    @Test fun failuresCannotBeMaskedBySleepOrConnection() {
        assertEquals(ProtectionStatus.RECOVERING, ProtectionStatus.evaluate(true, true, true, true, true))
    }
    @Test fun missingPermissionsAndStorageTakePrecedence() {
        assertEquals(ProtectionStatus.SETUP_REQUIRED, ProtectionStatus.evaluate(false, true, false, true, false))
        assertEquals(ProtectionStatus.STORAGE_UNAVAILABLE, ProtectionStatus.evaluate(true, false, false, true, false))
    }
    @Test fun healthyAwakeServiceIsActive() {
        assertEquals(ProtectionStatus.ACTIVE, ProtectionStatus.evaluate(true, true, false, true, false))
    }
}
