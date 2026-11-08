package dev.limit.app.monitor

import android.app.AppOpsManager
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import dev.limit.app.data.ServiceDiagnostics
import dev.limit.app.data.StateRepository
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAppOpsManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class,
    shadows = [UsageTrackerFailureTest.ThrowingAppOpsShadow::class])
class UsageTrackerFailureTest {
    @get:Rule val folder = TemporaryFolder()
    private val context: Context by lazy {
        object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = folder.root
        }
    }
    private val repository by lazy { StateRepository(context) }

    @After fun closeRepository() = runBlocking { repository.close() }

    @Test fun systemPermissionFailureIsReportedWithoutEscapingToService() {
        val tracker = UsageTracker(context, repository)
        assertFalse("An AppOps failure must be treated as unavailable permission", tracker.hasUsageAccess())
        val diagnostic = ServiceDiagnostics.read(context)
        assertTrue(diagnostic.contains("usage_permission"))
        assertTrue(diagnostic.contains("java.lang.SecurityException"))
        assertFalse(diagnostic.contains("sensitive package name"))
    }

    @Test fun permissionFailureMakesSnapshotUnavailableWithoutThrowing() = runBlocking {
        val snapshot = UsageTracker(context, repository).snapshot()
        assertFalse(snapshot.available)
        assertTrue(snapshot.totals.isEmpty())
        assertTrue(snapshot.activePackages.isEmpty())
        assertTrue(ServiceDiagnostics.read(context).contains("usage_permission"))
    }

    @Implements(AppOpsManager::class)
    class ThrowingAppOpsShadow : ShadowAppOpsManager() {
        @Implementation(minSdk = 29)
        override fun unsafeCheckOpNoThrow(op: String, uid: Int, packageName: String): Int {
            throw SecurityException("sensitive package name")
        }
    }
}
