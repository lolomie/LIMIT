package dev.limit.app.monitor

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.app.KeyguardManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dev.limit.app.LimitApplication
import dev.limit.app.MainActivity
import dev.limit.app.R
import dev.limit.app.data.ServiceDiagnostics
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

/** Visible, user-started companion. Never enables accessibility or bypasses force-stop. */
class ProtectionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lastText: String? = null
    private var registered = false
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { update() }
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Protection status", NotificationManager.IMPORTANCE_LOW))
        try {
            ServiceCompat.startForeground(this, ID, notification("Checking protection…"),
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        } catch (error: Exception) {
            ServiceDiagnostics.record(this, "protection_start", error)
            running = false
            stopSelf()
            return
        }
        running = true
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        androidx.core.content.ContextCompat.registerReceiver(this, screenReceiver, filter,
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        registered = true
        val repository = (application as LimitApplication).repository
        scope.launch { repository.ready.collect { update() } }
        scope.launch { repository.error.collect { update() } }
        scope.launch { LimitAccessibilityService.connected.collect { update() } }
        scope.launch { LimitAccessibilityService.failure.collect { update() } }
        // No wake lock: this loop does not wake a sleeping device or scan usage history.
        scope.launch {
            while (isActive) {
                LimitAccessibilityService.probe()
                update()
                delay(60_000)
            }
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    private fun update() {
        val app = application as LimitApplication
        val tracker = app.tracker
        val sleeping = !getSystemService(PowerManager::class.java).isInteractive ||
            getSystemService(KeyguardManager::class.java).isKeyguardLocked
        val text = dev.limit.app.core.ProtectionStatus.evaluate(
            tracker.hasUsageAccess() && tracker.isAccessibilityEnabled(),
            app.repository.ready.value && app.repository.error.value == null,
            LimitAccessibilityService.failure.value != null,
            tracker.isServiceConnected(), sleeping).message
        if (text != lastText) {
            lastText = text
            getSystemService(NotificationManager::class.java).notify(ID, notification(text))
        }
    }
    private fun notification(text: String) = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_limit).setContentTitle("LIMIT").setContentText(text)
        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        .setContentIntent(PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        .setOngoing(true).setOnlyAlertOnce(true).setCategory(NotificationCompat.CATEGORY_SERVICE).build()
    override fun onDestroy() {
        running = false
        if (registered) unregisterReceiver(screenReceiver)
        scope.cancel()
        super.onDestroy()
    }
    companion object {
        const val CHANNEL = "limit_protection"
        private const val ID = 107
        var running = false
            private set
    }
}
