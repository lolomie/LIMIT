package dev.limit.app.data

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ServiceDiagnosticsTest {
    @Test fun emptyHistoryStillProducesCopyableReport() {
        val report = ServiceDiagnostics.report(context) { emptyList() }
        assertTrue(report.contains("LIMIT diagnostics"))
        assertTrue(report.contains("No local error recorded"))
        assertTrue(report.contains("No exit reason supplied"))
    }

    @Test fun exitHistoryIsBoundedAndShowsAnrWithoutPrivateDetails() {
        val report = ServiceDiagnostics.report(context) {
            (1L..7L).map { ServiceDiagnostics.ExitSummary(it * 1000, android.app.ApplicationExitInfo.REASON_ANR, 0) }
        }
        assertEquals(3, Regex("reason=ANR").findAll(report).count())
        assertTrue(report.contains("1970-01-01T00:00:07Z"))
        assertFalse(report.contains("1970-01-01T00:00:04Z"))
    }

    @Test fun unavailableExitHistoryCannotHideDiagnosticsOrLeakMessages() {
        val report = ServiceDiagnostics.report(context) { throw SecurityException("private detail") }
        assertTrue(report.contains("exit history unavailable"))
        assertFalse(report.contains("private detail"))
    }

    @Test fun unknownExitReasonIsNotMisclassifiedAsManufacturerTermination() {
        val report = ServiceDiagnostics.report(context) { listOf(ServiceDiagnostics.ExitSummary(0, 999, 9)) }
        assertTrue(report.contains("OTHER_OR_UNKNOWN code=999 status=9"))
    }

    @Test @Config(sdk = [29]) fun olderAndroidStillProvidesReportWithoutQueryingUnavailableApi() {
        val report = ServiceDiagnostics.report(context) { error("Must not query API30 on API29") }
        assertTrue(report.contains("Requires Android 11"))
    }

    @get:Rule val folder = TemporaryFolder()
    private val context: Context by lazy {
        object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = folder.root
        }
    }

    @Test fun retainsOnlyLastThreeErrorsWithinSizeBound() {
        repeat(7) { ServiceDiagnostics.record(context, "phase_$it", IllegalStateException("private usage $it")) }
        val file = File(folder.root, ServiceDiagnostics.FILE_NAME)
        assertEquals(3, JSONArray(file.readText()).length())
        assertTrue(file.length() <= 16 * 1024)
        val report = ServiceDiagnostics.read(context)
        assertTrue(report.contains("phase_6"))
        assertTrue(report.contains("phase_4"))
        assertFalse(report.contains("phase_3"))
        assertFalse(report.contains("private usage"))
    }

    @Test fun omitsMessagesFilenamesAndUntrustedStackFrames() {
        val error = IllegalStateException("example.secret usage=999")
        error.stackTrace = arrayOf(
            StackTraceElement("example.secret.Main", "secretMethod", "private-file", 123),
            StackTraceElement("dev.limit.app.monitor.Service", "tick", "private-file", 42),
        )
        ServiceDiagnostics.record(context, "tick", error)
        val report = ServiceDiagnostics.read(context)
        assertTrue(report.contains("java.lang.IllegalStateException"))
        assertTrue(report.contains("dev.limit.app.monitor.Service.tick:42"))
        assertFalse(report.contains("example.secret"))
        assertFalse(report.contains("private-file"))
        assertFalse(report.contains("usage=999"))
    }

    @Test fun missingOrDamagedLogCannotPreventNewFailureFromBeingRecorded() {
        assertEquals("", ServiceDiagnostics.read(context))
        File(folder.root, ServiceDiagnostics.FILE_NAME).writeText("broken json")
        assertEquals("", ServiceDiagnostics.read(context))
        ServiceDiagnostics.record(context, "recovery", IllegalArgumentException())
        assertTrue(ServiceDiagnostics.read(context).contains("recovery"))
    }
}
