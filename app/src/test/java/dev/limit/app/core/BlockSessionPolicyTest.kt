package dev.limit.app.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockSessionPolicyTest {
    private fun dismiss(pkg: String?, active: Set<String> = emptySet(), expected: Long = 3) =
        BlockSessionPolicy.shouldDismiss(expected, 3, "target", pkg, setOf("home", "com.miui.home"),
            setOf("systemui", "settings"), setOf("keyboard"), setOf("settings", "emergency"), active)

    @Test fun homeAndMiuiLauncherTransitionsKeepChoiceOpen() {
        assertFalse(dismiss("home", setOf("home")))
        assertFalse(dismiss("com.miui.home", setOf("com.miui.home")))
    }
    @Test fun systemUiAndKeyboardDoNotDismiss() {
        assertFalse(dismiss("systemui", setOf("systemui")))
        assertFalse(dismiss("keyboard", setOf("keyboard")))
    }
    @Test fun otherAppRequiresForegroundConfirmation() {
        assertFalse(dismiss("other"))
        assertTrue(dismiss("other", setOf("other")))
    }
    @Test fun settingsAndEmergencyStayReachable() {
        assertTrue(dismiss("settings"))
        assertTrue(dismiss("emergency"))
    }
    @Test fun oldSessionCannotCloseNewChoice() {
        assertFalse(dismiss("settings", expected = 2))
        assertFalse(dismiss("other", setOf("other"), expected = 2))
    }
    @Test fun sameOrUnknownWindowDoesNotDismiss() {
        assertFalse(dismiss("target", setOf("target")))
        assertFalse(dismiss(null))
    }
}
