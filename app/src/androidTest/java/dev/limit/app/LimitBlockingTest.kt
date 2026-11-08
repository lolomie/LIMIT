package dev.limit.app

import android.app.UiAutomation
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import dev.limit.app.core.LimitEngine
import dev.limit.app.monitor.LimitAccessibilityService
import java.time.LocalDate
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real cross-application smoke test, restricted to a disposable emulator. */
@RunWith(AndroidJUnit4::class)
class LimitBlockingTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val application get() = context.applicationContext as LimitApplication
    private val device = run {
        Configurator.getInstance().uiAutomationFlags = UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES
        UiDevice.getInstance(instrumentation)
    }
    private val fixture = "dev.limit.fixture"
    private val service = "dev.limit.app/dev.limit.app.monitor.LimitAccessibilityService"
    private val capsulePrefix = "LIMIT usage: $fixture; "

    private data class CapsuleProbe(val description: String, val bounds: Rect, val label: String)

    /** Android omits non-touchable windows from UiAutomation's accessibility window list. */
    private fun probeCapsule(): CapsuleProbe? {
        if (Build.VERSION.SDK_INT < 29) return null
        var result: CapsuleProbe? = null
        instrumentation.runOnMainSync {
            for (root in WindowInspector.getGlobalWindowViews()) {
                val description = root.contentDescription?.toString() ?: continue
                if (!description.startsWith(capsulePrefix) || !root.isAttachedToWindow ||
                    !root.isShown || root.windowVisibility != View.VISIBLE) continue
                val bounds = Rect()
                if (root.getGlobalVisibleRect(bounds) && !bounds.isEmpty) {
                    // View "global" coordinates are relative to its window's root, not the display.
                    val windowOrigin = IntArray(2)
                    root.rootView.getLocationOnScreen(windowOrigin)
                    bounds.offset(windowOrigin[0], windowOrigin[1])
                    val label = ((root as? ViewGroup)?.getChildAt(0) as? TextView)?.text?.toString().orEmpty()
                    result = CapsuleProbe(description, bounds, label)
                    break
                }
            }
        }
        return result
    }

    private suspend fun awaitCapsule(visible: Boolean, timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) {
            while ((probeCapsule() != null) != visible) delay(100)
            true
        } == true

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            .executeShellCommand(command)
    ).bufferedReader().use { it.readText().trim() }

    @Test fun exhaustedAppIsBlockedAndExceptionReturnsToTarget() = runBlocking {
        assumeTrue("Automatic permission setup is limited to emulators",
            Build.FINGERPRINT.contains("generic") || Build.HARDWARE.contains("ranchu") ||
                Build.MODEL.contains("sdk", ignoreCase = true))
        val intent = context.packageManager.getLaunchIntentForPackage(fixture)
        assumeTrue("Install :test-target:assembleDebug APK before this test", intent != null)
        withTimeout(15_000) { application.repository.ready.first { it } }
        val originalRule = application.repository.state.value.rules[fixture]
        val originalServices = shell("settings get secure enabled_accessibility_services")
        val originalEnabled = shell("settings get secure accessibility_enabled")
        val originalUsageMode = shell("appops get dev.limit.app GET_USAGE_STATS")
        val services = originalServices.takeUnless { it == "null" || it.isBlank() }
            ?.split(':').orEmpty().toMutableSet().apply { add(service) }.joinToString(":")
        try {
            shell("appops set dev.limit.app GET_USAGE_STATS allow")
            // UiAutomation executes tokens directly; shell quotes would become part of the component name.
            shell("settings put secure enabled_accessibility_services $services")
            shell("settings put secure accessibility_enabled 1")
            assertEquals("Accessibility setup must preserve a valid component list", services,
                shell("settings get secure enabled_accessibility_services"))
            withTimeout(30_000) { LimitAccessibilityService.connected.first { it } }

            if (Build.VERSION.SDK_INT >= 29) {
                // An unrestricted app has no finite allowance, but still shows its actual spent minutes.
                application.repository.setRule(fixture, null)
                assertNull(application.repository.state.value.rules[fixture])
                context.startActivity(intent!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                assertTrue("Unrestricted target must show the usage capsule", awaitCapsule(true, 20_000))
                withTimeout(20_000) {
                    while (true) {
                        val beforeSnapshot = application.tracker.snapshot()
                        val description = probeCapsule()?.description
                        val displayed = description?.removePrefix(capsulePrefix)?.removeSuffix(" Min.")?.toLongOrNull()
                        val afterSnapshot = application.tracker.snapshot()
                        val lower = (beforeSnapshot.totals[fixture] ?: 0L) / 60_000L
                        val upper = (afterSnapshot.totals[fixture] ?: 0L) / 60_000L
                        if (beforeSnapshot.available && afterSnapshot.available &&
                            displayed != null && displayed in lower..upper) break
                        delay(250)
                    }
                }
                val capsule = requireNotNull(probeCapsule())
                val capsuleBounds = capsule.bounds
                val statusBarId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
                val statusBarHeight = context.resources.getDimensionPixelSize(statusBarId)
                assertTrue("Minutes-only pill must fit within the status bar",
                    capsuleBounds.top >= 0 && capsuleBounds.bottom <= statusBarHeight)
                assertTrue("Compact pill must not retain an empty camera-sized mirror area",
                    capsuleBounds.width() < device.displayWidth / 3)
                assertTrue("Only the minute count and unit may be visible",
                    capsule.label.matches(Regex("[0-9]+ Min\\.")))
                // Containment above proves the capsule lies over SystemUI, outside app content.
                // Exercise the surface beneath it directly; app-button lookup is irrelevant here.
                assertTrue(device.swipe(capsuleBounds.centerX(), capsuleBounds.centerY(),
                    capsuleBounds.centerX(), device.displayHeight / 2, 30))
                assertTrue("A swipe through the camera capsule must reach the notification shade", device.wait(
                    Until.hasObject(By.res("com.android.systemui", "notification_stack_scroller")), 8_000
                ) == true)
                device.pressBack()
                device.pressHome()
                assertTrue("Launcher must not retain another app's capsule", awaitCapsule(false, 5_000))
            }

            // Removing LIMIT's own task must not stop its system-bound monitor or companion.
            context.startActivity(Intent(context, dev.limit.app.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            withTimeout(10_000) {
                while (!dev.limit.app.monitor.ProtectionService.running) delay(100)
            }
            instrumentation.runOnMainSync {
                context.getSystemService(android.app.ActivityManager::class.java).appTasks
                    .forEach { it.finishAndRemoveTask() }
            }
            delay(2_000)
            assertTrue("Companion must survive task removal", dev.limit.app.monitor.ProtectionService.running)
            assertTrue("Accessibility must survive task removal", LimitAccessibilityService.connected.value)

            application.repository.setRule(fixture, 0)
            val date = LocalDate.now().toString()
            val before = LimitEngine.budget(application.repository.state.value, date, fixture)
            assumeTrue("Fresh daily allowance required for exception smoke test", before.grantsRemaining > 0 && before.bonusRemainingMs == 0L)
            context.startActivity(intent!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            assertTrue("Exhausted target must show blocker", device.wait(
                Until.hasObject(By.text("Time to step away.")), 20_000
            ) == true)
            delay(5_500)
            assertTrue("Extension choice must survive the service's Home transition for over five seconds",
                device.hasObject(By.text("Time to step away.")))
            assertEquals("No exception is spent until the user chooses it", before.grantsRemaining,
                LimitEngine.budget(application.repository.state.value, date, fixture).grantsRemaining)
            if (Build.VERSION.SDK_INT >= 29) {
                assertTrue("The blocker must hide the usage capsule", awaitCapsule(false, 5_000))
            }
            val button = device.wait(Until.findObject(By.text("Use 5 minutes")), 5_000)
            assertTrue("An exception must be available", button != null && button.isEnabled)
            requireNotNull(button).click()
            assertTrue("Grant must reopen the target app", device.wait(
                Until.hasObject(By.textContains("LIMIT test target")), 15_000
            ) == true)
            assertFalse(device.hasObject(By.text("Time to step away.")))
            if (Build.VERSION.SDK_INT >= 29) {
                assertTrue("An exception must restore the target's usage capsule", awaitCapsule(true, 10_000))
            }
            val after = LimitEngine.budget(application.repository.state.value, date, fixture)
            assertEquals(before.grantsRemaining - 1, after.grantsRemaining)
            assertTrue("One grant provides at most five remaining minutes", after.bonusRemainingMs in 1..300_000L)
            withTimeout(25_000) {
                while ((application.repository.state.value.days[date]?.apps?.get(fixture)?.usedMs ?: 0L) <= after.usedMs) delay(250)
            }
            val accrued = LimitEngine.budget(application.repository.state.value, date, fixture)
            assertTrue("Foreground usage must consume the granted credit", accrued.bonusRemainingMs < after.bonusRemainingMs)
        } finally {
            device.pressHome()
            application.repository.setRule(fixture, originalRule?.limitMinutes)
            if (originalServices == "null" || originalServices.isBlank()) shell("settings delete secure enabled_accessibility_services")
            else shell("settings put secure enabled_accessibility_services $originalServices")
            if (originalEnabled == "null" || originalEnabled.isBlank()) shell("settings delete secure accessibility_enabled")
            else shell("settings put secure accessibility_enabled $originalEnabled")
            val restoreMode = when {
                originalUsageMode.contains("allow") -> "allow"
                originalUsageMode.contains("ignore") -> "ignore"
                originalUsageMode.contains("deny") -> "deny"
                else -> "default"
            }
            shell("appops set dev.limit.app GET_USAGE_STATS $restoreMode")
        }
    }
}
