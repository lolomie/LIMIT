package dev.limit.app.nudity

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dev.limit.app.MainActivity
import dev.limit.app.R

/** One user-authorized MediaProjection token per capture session. No persistence. */
class NudityDetectionService : Service() {
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var processor: ScreenFrameProcessor? = null
    private var thread: HandlerThread? = null
    private var finishing = false

    private val callback = object : MediaProjection.Callback() {
        override fun onStop() {
            NuditySession.set(NudityPhase.CONSENT_REQUIRED, "Bildschirmfreigabe beendet.")
            stopSelf()
        }
        override fun onCapturedContentResize(width: Int, height: Int) {
            // Android may resize captured content independently of device rotation.
            // Never silently continue with stretched/mismatched buffers.
            if (reader?.width != width || reader?.height != height) {
                NuditySession.set(NudityPhase.CONSENT_REQUIRED, "Bildschirmgröße geändert. Schutz erneut starten.")
                stopSelf()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "LIMIT Nacktheitsschutz", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            NuditySession.set(NudityPhase.INACTIVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action != START || projection != null) {
            if (projection == null) {
                NuditySession.set(NudityPhase.CONSENT_REQUIRED)
                stopSelf()
            }
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(),
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0)
            val resultCode = intent.getIntExtra(RESULT_CODE, Int.MIN_VALUE)
            val consent = if (Build.VERSION.SDK_INT >= 33)
                intent.getParcelableExtra(RESULT_DATA, Intent::class.java)
            else @Suppress("DEPRECATION") (intent.getParcelableExtra(RESULT_DATA) as? Intent)
            require(resultCode != Int.MIN_VALUE && consent != null) { "Missing user permission" }
            val manager = getSystemService(MediaProjectionManager::class.java)
            val p = manager.getMediaProjection(resultCode, consent)
                ?: error("Android declined the capture session")
            projection = p
            val worker = HandlerThread("limit-ephemeral-screen-frames")
            worker.start()
            thread = worker
            val handler = Handler(worker.looper)
            p.registerCallback(callback, handler)
            val metrics = resources.displayMetrics
            val wm = getSystemService(WindowManager::class.java)
            val bounds = if (Build.VERSION.SDK_INT >= 30) wm.currentWindowMetrics.bounds else null
            val w = bounds?.width() ?: metrics.widthPixels
            val h = bounds?.height() ?: metrics.heightPixels
            require(w > 0 && h > 0 && w.toLong() * h.toLong() < 20_000_000L) {
                "Invalid capture dimensions"
            }
            val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
            reader = r
            val frames = ScreenFrameProcessor(this)
            processor = frames
            r.setOnImageAvailableListener({ available ->
                try { frames.onFrame(available) } catch (_: Exception) {
                    NuditySession.set(NudityPhase.ERROR, "Lokale Bilderkennung fehlgeschlagen.")
                    stopSelf()
                }
            }, handler)
            display = p.createVirtualDisplay("LIMIT user-approved protection", w, h,
                metrics.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface,
                null, handler)
            check(display != null) { "Screen capture unavailable" }
            NuditySession.set(NudityPhase.ACTIVE)
        } catch (_: Exception) {
            NuditySession.set(NudityPhase.ERROR, "Aufnahme/Modell konnte nicht gestartet werden.")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        NuditySession.set(NudityPhase.CONSENT_REQUIRED, "Bildschirm gedreht. Schutz erneut starten.")
        stopSelf()
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1,
            Intent(this, NudityDetectionService::class.java).setAction(STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_limit)
            .setContentTitle("LIMIT: Nacktheitsschutz läuft")
            .setContentText("Offline-Bildschirmanalyse – jederzeit beenden")
            .setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(R.drawable.ic_limit, "Schutz beenden", stop)
            .build()
    }

    override fun onDestroy() {
        if (!finishing) {
            finishing = true
            // Stop accepting frames before releasing the virtual display or model.
            reader?.setOnImageAvailableListener(null, null)
            display?.release()
            display = null
            reader?.close()
            reader = null
            processor?.close()
            processor = null
            projection?.let { p ->
                runCatching { p.unregisterCallback(callback) }
                runCatching { p.stop() }
            }
            projection = null
            thread?.quitSafely()
            thread = null
            if (NuditySession.status.value.phase == NudityPhase.ACTIVE ||
                NuditySession.status.value.phase == NudityPhase.STARTING)
                NuditySession.set(NudityPhase.CONSENT_REQUIRED, "Aufnahme beendet. Erneut freigeben.")
        }
        super.onDestroy()
    }

    companion object {
        const val START = "dev.limit.app.nudity.START"
        const val STOP = "dev.limit.app.nudity.STOP"
        const val RESULT_CODE = "result_code"
        const val RESULT_DATA = "result_data"
        private const val CHANNEL = "limit_nudity_capture"
        private const val NOTIFICATION_ID = 109
    }
}
