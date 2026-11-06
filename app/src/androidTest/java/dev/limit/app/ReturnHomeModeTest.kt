package dev.limit.app

import android.app.UiAutomation
import android.content.Intent
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import dev.limit.app.core.LimitEngine
import dev.limit.app.core.LimitReachedAction
import dev.limit.app.data.AppCatalog
import dev.limit.app.monitor.LimitAccessibilityService
import java.time.LocalDate
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Permission changes and cross-app navigation are confined to a disposable emulator. */
@RunWith(AndroidJUnit4::class)
class ReturnHomeModeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val application get() = context.applicationContext as LimitApplication
    private val device = run {
        Configurator.getInstance().uiAutomationFlags = UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES
        UiDevice.getInstance(instrumentation)
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            .executeShellCommand(command)
    ).bufferedReader().use { it.readText().trim() }

    @Test fun automaticReturnHomeDoesNotShowExtensionOrSpendException() = runBlocking {
        assumeTrue("Automatic permission setup is limited to emulators",
            Build.FINGERPRINT.contains("generic") || Build.HARDWARE.contains("ranchu") ||
                Build.MODEL.contains("sdk", ignoreCase = true))
        withTimeout(15_000) { application.repository.ready.first { it } }
        val date = LocalDate.now().toString()
        // The exception smoke test owns the fixture's credit. Use a different ordinary app
        // so this test is independent of class order and never rewrites usage or grants.
        val target = AppCatalog(context).load().filter {
            !it.protected && it.packageName != "dev.limit.fixture" &&
                LimitEngine.budget(application.repository.state.value, date, it.packageName).bonusRemainingMs == 0L
        }.sortedBy { if (it.packageName.contains("calculator")) 0 else 1 }.firstOrNull {
            context.packageManager.getLaunchIntentForPackage(it.packageName)?.component != null
        }
        assumeTrue("A second ordinary launchable emulator app is required", target != null)
        val pkg = requireNotNull(target).packageName
        val launch = requireNotNull(context.packageManager.getLaunchIntentForPackage(pkg))
        val home = requireNotNull(context.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0
        )?.activityInfo?.packageName)
        val originalRule = application.repository.state.value.rules[pkg]
        val originalAction = application.repository.state.value.limitReachedAction
        val originalServices = shell("settings get secure enabled_accessibility_services")
        val originalEnabled = shell("settings get secure accessibility_enabled")
        val originalUsageMode = shell("appops get dev.limit.app GET_USAGE_STATS")
        val services = originalServices.takeUnless { it == "null" || it.isBlank() }
            ?.split(':').orEmpty().toMutableSet().apply {
                add("dev.limit.app/dev.limit.app.monitor.LimitAccessibilityService")
            }.joinToString(":")
        try {
            shell("appops set dev.limit.app GET_USAGE_STATS allow")
            shell("settings put secure enabled_accessibility_services $services")
            shell("settings put secure accessibility_enabled 1")
            withTimeout(30_000) { LimitAccessibilityService.connected.first { it } }
            application.repository.setRule(pkg, null)
            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            assertTrue("The test target must actually launch before restricting it",
                device.wait(Until.hasObject(By.pkg(pkg)), 20_000) == true)
            device.pressHome()
            assertTrue(device.wait(Until.hasObject(By.pkg(home)), 10_000) == true)

            application.repository.setLimitReachedAction(LimitReachedAction.RETURN_HOME)
            application.repository.setRule(pkg, 0)
            val grantsBefore = application.repository.state.value.days[date]?.grantsUsed ?: 0
            val launchResult = shell("am start -n ${requireNotNull(launch.component).flattenToString()}")
            assertTrue("Activity Manager must accept the restricted target launch: $launchResult",
                launchResult.contains("Starting: Intent") && !launchResult.contains("Error:"))
            withTimeout(20_000) {
                while (!device.hasObject(By.pkg(home))) {
                    assertFalse("Automatic mode must not show the extension popup",
                        device.hasObject(By.text("Time to step away.")))
                    delay(100)
                }
            }
            // Observe beyond the original half-second dismissal and across queued window events.
            repeat(25) {
                assertFalse("Automatic mode must remain free of extension popups",
                    device.hasObject(By.text("Time to step away.")))
                delay(200)
            }
            assertTrue("The launcher must remain foreground", device.hasObject(By.pkg(home)))
            assertEquals("Automatic closing cannot consume a global exception", grantsBefore,
                application.repository.state.value.days[date]?.grantsUsed ?: 0)
            assertEquals(0L, LimitEngine.budget(application.repository.state.value, date, pkg).bonusRemainingMs)
            assertTrue("Monitoring must remain connected after automatic closing",
                LimitAccessibilityService.connected.value)
        } finally {
            device.pressHome()
            application.repository.setRule(pkg, originalRule?.limitMinutes)
            application.repository.setLimitReachedAction(originalAction)
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
