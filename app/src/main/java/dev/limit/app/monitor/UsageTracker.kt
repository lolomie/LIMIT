package dev.limit.app.monitor

import android.app.AppOpsManager
import android.app.KeyguardManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings
import dev.limit.app.core.UsageEvent
import dev.limit.app.core.UsageEventReducer
import dev.limit.app.core.UsageEventType
import dev.limit.app.data.StateRepository
import dev.limit.app.data.ServiceDiagnostics
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

data class TrackedUsage(
    val date: String,
    val totals: Map<String, Long> = emptyMap(),
    val activePackages: Set<String> = emptySet(),
    val available: Boolean,
    val message: String? = null,
    val observedElapsedMs: Long = 0,
)

class UsageTracker(private val context: Context, private val repository: StateRepository) {
    fun isServiceConnected(): Boolean = LimitAccessibilityService.connected.value

    fun hasUsageAccess(): Boolean = try {
        val manager = context.getSystemService(AppOpsManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= 29) manager.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName
        ) else @Suppress("DEPRECATION") manager.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        mode == AppOpsManager.MODE_ALLOWED
    } catch (error: Exception) {
        ServiceDiagnostics.record(context, "usage_permission", error)
        false
    }

    fun isAccessibilityEnabled(): Boolean = try {
        val target = ComponentName(context, LimitAccessibilityService::class.java)
        Settings.Secure.getString(context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)?.split(':')?.any {
                ComponentName.unflattenFromString(it) == target
            } == true
    } catch (error: Exception) {
        ServiceDiagnostics.record(context, "accessibility_permission", error)
        false
    }

    @Suppress("DEPRECATION")
    suspend fun snapshot(): TrackedUsage = withContext(Dispatchers.IO) {
        val observedElapsedMs = SystemClock.elapsedRealtime()
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val date = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
      try {
            if (!hasUsageAccess()) {
                return@withContext TrackedUsage(date.toString(), available = false,
                    message = "Usage access is required to measure app time.")
            }
            if (context.getSystemService(UserManager::class.java)?.isUserUnlocked == false) {
                return@withContext TrackedUsage(date.toString(), available = false,
                    message = "Unlock your phone to resume monitoring.")
            }
            val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
            val seed = date.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val stream = context.getSystemService(UsageStatsManager::class.java)
                .queryEvents(seed, now) ?: return@withContext TrackedUsage(date.toString(), available = false,
                    message = "Android usage history is temporarily unavailable.")
            val event = UsageEvents.Event()
            val events = mutableListOf<UsageEvent>()
            while (stream.hasNextEvent()) {
                stream.getNextEvent(event)
                val kind = when (event.eventType) {
                    UsageEvents.Event.MOVE_TO_FOREGROUND -> UsageEventType.RESUME
                    UsageEvents.Event.MOVE_TO_BACKGROUND -> UsageEventType.PAUSE
                    23 -> UsageEventType.STOP
                    UsageEvents.Event.SCREEN_INTERACTIVE -> UsageEventType.SCREEN_ON
                    UsageEvents.Event.SCREEN_NON_INTERACTIVE -> UsageEventType.SCREEN_OFF
                    UsageEvents.Event.KEYGUARD_SHOWN -> UsageEventType.LOCK
                    UsageEvents.Event.KEYGUARD_HIDDEN -> UsageEventType.UNLOCK
                    26 -> UsageEventType.SHUTDOWN
                    27 -> UsageEventType.STARTUP
                    else -> null
                } ?: continue
                events += UsageEvent(event.timeStamp, event.packageName, event.className, kind)
            }
            val result = UsageEventReducer.reduce(events, start, now, repository.exclusions(now))
            val interactive = context.getSystemService(android.os.PowerManager::class.java).isInteractive
            val unlocked = !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
            TrackedUsage(date.toString(), result.totals,
                if (interactive && unlocked) result.activePackages else emptySet(), true,
                observedElapsedMs = observedElapsedMs)
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: Exception) {
            ServiceDiagnostics.record(context, "usage_history", error)
            TrackedUsage(date.toString(), available = false,
                message = "Android usage history could not be read. Monitoring is paused.")
        }
    }
}
